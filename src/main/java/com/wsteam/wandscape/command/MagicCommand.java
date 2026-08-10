package com.wsteam.wandscape.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.CommandNode;

import com.wsteam.wandscape.magic.data.MagicDef;
import com.wsteam.wandscape.magic.internal.MagicSpellExecutors;
import com.wsteam.wandscape.magic.internal.SpellbookLoader;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * 魔法测试命令：/wandscape magic cast <spell_id>
 * 直接对命令发送玩家施加指定魔法效果（治疗、陨石、石化、光束等）。
 */
public final class MagicCommand {

    private MagicCommand() {}

    private static final SuggestionProvider<CommandSourceStack> SPELL_SUGGESTIONS = (ctx, builder) ->
            SharedSuggestionProvider.suggest(SpellbookLoader.getAllSpecs().keySet(), builder);

    public static CommandNode<CommandSourceStack> node() {
        return Commands.literal("magic")
                .then(Commands.literal("cast")
                        .then(Commands.argument("spell_id", StringArgumentType.word())
                                .suggests(SPELL_SUGGESTIONS)
                                .executes(MagicCommand::castSpell)))
                .build();
    }

    private static int castSpell(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            String spellId = StringArgumentType.getString(ctx, "spell_id");

            MagicDef def = SpellbookLoader.getSpec(spellId);
            if (def == null) {
                ctx.getSource().sendFailure(Component.literal("[Wandscape] 未找到魔法: " + spellId));
                return 0;
            }

            boolean ok = MagicSpellExecutors.castForPlayer(player, def);
            if (ok) {
                ctx.getSource().sendSuccess(() -> Component.literal("[Wandscape] 已对玩家成功施加魔法: " + spellId), true);
                return 1;
            } else {
                ctx.getSource().sendFailure(Component.literal("[Wandscape] 施加魔法失败: " + spellId));
                return 0;
            }
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("[Wandscape] 仅玩家可执行该魔法测试命令"));
            return 0;
        }
    }
}
