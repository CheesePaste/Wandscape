package com.wsteam.wandscape.content.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.CommandNode;
import com.wsteam.wandscape.content.npc.component.MagicState;
import com.wsteam.wandscape.content.magic.data.MagicDef;
import com.wsteam.wandscape.content.magic.internal.MagicSpellExecutors;
import com.wsteam.wandscape.content.magic.internal.SpellbookLoader;
import com.wsteam.wandscape.content.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.foundation.ui.I18n;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * 魔法测试命令：
 * <ul>
 *   <li>/wandscape magic freecast [on|off|true|false|status] — 切换/设置全局无CD无蓝耗测试模式</li>
 *   <li>/wandscape magic nocd — freecast 别名</li>
 *   <li>/wandscape magic nomana — freecast 别名</li>
 *   <li>/wandscape magic clear_cd — 清除周围 64 格内 NPC 的冷却与施法互斥锁</li>
 *   <li>/wandscape magic fill_mana — 补满周围 64 格内 NPC 的法力值</li>
 *   <li>/wandscape magic cast &lt;spell_id&gt; — 对玩家自身测试施放魔法</li>
 * </ul>
 */
public final class MagicCommand {

    private MagicCommand() {}

    private static final SuggestionProvider<CommandSourceStack> SPELL_SUGGESTIONS = (ctx, builder) ->
            SharedSuggestionProvider.suggest(SpellbookLoader.getAllSpecs().keySet(), builder);

    private static final SuggestionProvider<CommandSourceStack> TOGGLE_SUGGESTIONS = (ctx, builder) ->
            SharedSuggestionProvider.suggest(List.of("on", "off", "true", "false", "status"), builder);

    public static CommandNode<CommandSourceStack> node() {
        return Commands.literal("magic")
                .then(Commands.literal("freecast")
                        .executes(ctx -> toggleFreeCast(ctx, null))
                        .then(Commands.argument("state", StringArgumentType.word())
                                .suggests(TOGGLE_SUGGESTIONS)
                                .executes(ctx -> toggleFreeCast(ctx, StringArgumentType.getString(ctx, "state")))))
                .then(Commands.literal("nocd")
                        .executes(ctx -> toggleFreeCast(ctx, null))
                        .then(Commands.argument("state", StringArgumentType.word())
                                .suggests(TOGGLE_SUGGESTIONS)
                                .executes(ctx -> toggleFreeCast(ctx, StringArgumentType.getString(ctx, "state")))))
                .then(Commands.literal("nomana")
                        .executes(ctx -> toggleFreeCast(ctx, null))
                        .then(Commands.argument("state", StringArgumentType.word())
                                .suggests(TOGGLE_SUGGESTIONS)
                                .executes(ctx -> toggleFreeCast(ctx, StringArgumentType.getString(ctx, "state")))))
                .then(Commands.literal("clear_cd")
                        .executes(MagicCommand::clearCooldowns))
                .then(Commands.literal("fill_mana")
                        .executes(MagicCommand::fillMana))
                .then(Commands.literal("cast")
                        .then(Commands.argument("spell_id", StringArgumentType.word())
                                .suggests(SPELL_SUGGESTIONS)
                                .executes(MagicCommand::castSpell)))
                .build();
    }

    private static int toggleFreeCast(CommandContext<CommandSourceStack> ctx, String stateStr) {
        boolean newState;
        if (stateStr == null || stateStr.isBlank()) {
            newState = MagicState.toggleFreeCast();
        } else {
            String s = stateStr.trim().toLowerCase(java.util.Locale.ROOT);
            if ("status".equals(s)) {
                boolean cur = MagicState.isFreeCast();
                ctx.getSource().sendSuccess(() -> I18n.name(
                        "message.wandscape.command.magic_freecast_status",
                        "[魔法小镇] 魔法测试模式（无CD无耗蓝）当前状态: %s", cur ? "开启 (ON)" : "关闭 (OFF)"), false);
                return cur ? 1 : 0;
            }
            newState = "on".equals(s) || "true".equals(s) || "1".equals(s);
            MagicState.setFreeCast(newState);
        }

        ctx.getSource().sendSuccess(() -> I18n.name(
                "message.wandscape.command.magic_freecast_toggled",
                "[魔法小镇] 魔法测试模式（无CD无耗蓝）已: %s", newState ? "开启 (ON)" : "关闭 (OFF)"), true);
        return newState ? 1 : 0;
    }

    private static int clearCooldowns(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            ServerLevel level = player.serverLevel();
            List<WandscapeNpc> npcs = level.getEntitiesOfClass(
                    WandscapeNpc.class, player.getBoundingBox().inflate(64.0));
            int count = 0;
            for (WandscapeNpc npc : npcs) {
                npc.magic.setLockTicks(0);
                npc.magic.getCooldowns().clear();
                count++;
            }
            int finalCount = count;
            ctx.getSource().sendSuccess(() -> I18n.name(
                    "message.wandscape.command.magic_cd_cleared",
                    "[魔法小镇] 已重置附近 %d 名法师的冷却与施法互斥锁", finalCount), true);
            return count;
        } catch (Exception e) {
            ctx.getSource().sendFailure(I18n.name(
                    "message.wandscape.command.magic_players_only",
                    "[魔法小镇] 仅玩家可执行该命令"));
            return 0;
        }
    }

    private static int fillMana(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            ServerLevel level = player.serverLevel();
            List<WandscapeNpc> npcs = level.getEntitiesOfClass(
                    WandscapeNpc.class, player.getBoundingBox().inflate(64.0));
            int count = 0;
            for (WandscapeNpc npc : npcs) {
                npc.magic.setMana(npc.getMaxMana());
                count++;
            }
            int finalCount = count;
            ctx.getSource().sendSuccess(() -> I18n.name(
                    "message.wandscape.command.magic_mana_filled",
                    "[魔法小镇] 已补满附近 %d 名法师的法力值", finalCount), true);
            return count;
        } catch (Exception e) {
            ctx.getSource().sendFailure(I18n.name(
                    "message.wandscape.command.magic_players_only",
                    "[魔法小镇] 仅玩家可执行该命令"));
            return 0;
        }
    }

    private static int castSpell(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            String spellId = StringArgumentType.getString(ctx, "spell_id");

            MagicDef def = SpellbookLoader.getSpec(spellId);
            if (def == null) {
                ctx.getSource().sendFailure(I18n.name(
                        "message.wandscape.command.magic_not_found",
                        "[魔法小镇] 未找到魔法: %s", spellId));
                return 0;
            }

            boolean ok = MagicSpellExecutors.castForPlayer(player, def);
            if (ok) {
                ctx.getSource().sendSuccess(() -> I18n.name(
                        "message.wandscape.command.magic_cast_ok",
                        "[魔法小镇] 已对玩家成功施加魔法: %s", spellId), true);
                return 1;
            } else {
                ctx.getSource().sendFailure(I18n.name(
                        "message.wandscape.command.magic_cast_failed",
                        "[魔法小镇] 施加魔法失败: %s", spellId));
                return 0;
            }
        } catch (Exception e) {
            ctx.getSource().sendFailure(I18n.name(
                    "message.wandscape.command.magic_players_only",
                    "[魔法小镇] 仅玩家可执行该魔法测试命令"));
            return 0;
        }
    }
}
