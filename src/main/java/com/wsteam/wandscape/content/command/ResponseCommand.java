package com.wsteam.wandscape.content.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.CommandNode;
import com.wsteam.wandscape.content.magic.worldresponse.WorldResponseProtectionSavedData;
import com.wsteam.wandscape.foundation.ui.I18n;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 移山填海的「别动这些方块」名单（按玩家各存一份，见 {@link WorldResponseProtectionSavedData}）：
 * <ul>
 *   <li>/wandscape response protect [&lt;block&gt;] — 加进名单；省略参数 = 你准星正对着的那个方块（水面/岩浆面也算）</li>
 *   <li>/wandscape response unprotect [&lt;block&gt;] — 移出名单</li>
 *   <li>/wandscape response list — 看自己当前的名单</li>
 * </ul>
 *
 * <p>**刻意不挂 op**：这是玩家自己的偏好，改它不影响服务器上的别人，也不需要权限——与
 * `/wandscape test` 下那批调试指令不是一类东西，所以挂在面向玩家的根节点上。
 */
public final class ResponseCommand {

    private ResponseCommand() {}

    /** 准星对多远的方块算数：指令用的手，比生存伸手（4.5）略宽一点，方便对着水面点。 */
    private static final double REACH = 5.0;

    private static final SuggestionProvider<CommandSourceStack> BLOCK_SUGGESTIONS = (ctx, builder) ->
            SharedSuggestionProvider.suggestResource(BuiltInRegistries.BLOCK.keySet().stream(), builder);

    public static CommandNode<CommandSourceStack> node() {
        return Commands.literal("response")
                .then(Commands.literal("protect")
                        .executes(ctx -> edit(ctx, null, true))
                        .then(Commands.argument("block", StringArgumentType.word())
                                .suggests(BLOCK_SUGGESTIONS)
                                .executes(ctx -> edit(ctx, StringArgumentType.getString(ctx, "block"), true))))
                .then(Commands.literal("unprotect")
                        .executes(ctx -> edit(ctx, null, false))
                        .then(Commands.argument("block", StringArgumentType.word())
                                .suggests(BLOCK_SUGGESTIONS)
                                .executes(ctx -> edit(ctx, StringArgumentType.getString(ctx, "block"), false))))
                .then(Commands.literal("list")
                        .executes(ResponseCommand::list))
                .build();
    }

    private static int edit(CommandContext<CommandSourceStack> ctx, @Nullable String rawId, boolean protect) {
        ServerPlayer player = CommandUtil.player(ctx.getSource());
        if (player == null) {
            ctx.getSource().sendFailure(playersOnly());
            return 0;
        }

        boolean targeted = rawId == null || rawId.isBlank();
        Block block = targeted ? targetedBlock(player) : byId(rawId);
        if (block == null) {
            if (targeted) {
                ctx.getSource().sendFailure(I18n.name("message.wandscape.command.response_no_block",
                        "[魔法小镇] 准星没对着方块 —— 补一个方块 id，或者对着方块/水面再来一次"));
            } else {
                ctx.getSource().sendFailure(I18n.name("message.wandscape.command.response_unknown_block",
                        "[魔法小镇] 找不到这个方块：%s", rawId));
            }
            return 0;
        }

        String id = BuiltInRegistries.BLOCK.getKey(block).toString();
        boolean changed = protect
                ? WorldResponseProtectionSavedData.add(player.serverLevel(), player.getUUID(), block)
                : WorldResponseProtectionSavedData.remove(player.serverLevel(), player.getUUID(), block);
        if (!changed) {
            ctx.getSource().sendSuccess(() -> I18n.name(
                    protect ? "message.wandscape.command.response_protect_already"
                            : "message.wandscape.command.response_unprotect_missing",
                    protect ? "[魔法小镇] %s 本来就在名单里" : "[魔法小镇] %s 不在名单里", id), false);
            return 0;
        }
        ctx.getSource().sendSuccess(() -> I18n.name(
                protect ? "message.wandscape.command.response_protected"
                        : "message.wandscape.command.response_unprotected",
                protect ? "[魔法小镇] 移山填海不会再动 %s" : "[魔法小镇] 移山填海可以动 %s 了", id), false);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = CommandUtil.player(ctx.getSource());
        if (player == null) {
            ctx.getSource().sendFailure(playersOnly());
            return 0;
        }
        List<String> ids = WorldResponseProtectionSavedData.ids(player.serverLevel(), player.getUUID());
        if (ids.isEmpty()) {
            ctx.getSource().sendSuccess(() -> I18n.name("message.wandscape.command.response_list_empty",
                    "[魔法小镇] 名单是空的 —— 用 /wandscape response protect <方块> 往里加"), false);
            return 0;
        }
        ctx.getSource().sendSuccess(() -> I18n.name("message.wandscape.command.response_list",
                "[魔法小镇] 移山填海不会动这 %s 种方块：%s",
                String.valueOf(ids.size()), String.join(", ", ids)), false);
        return ids.size();
    }

    private static Component playersOnly() {
        return I18n.name("message.wandscape.command.response_players_only",
                "[魔法小镇] 这份名单是每个玩家自己的，请在游戏里执行");
    }

    /** 准星正对着的方块；对着水面/岩浆面时停在液体上（{@code includeFluids = true}，否则会穿过去打到底下的方块）。 */
    @Nullable
    private static Block targetedBlock(ServerPlayer player) {
        HitResult hit = player.pick(REACH, 0.0F, true);
        if (!(hit instanceof BlockHitResult blockHit)) return null;
        BlockState state = player.serverLevel().getBlockState(blockHit.getBlockPos());
        return state.isAir() ? null : state.getBlock();
    }

    @Nullable
    private static Block byId(String rawId) {
        ResourceLocation id = ResourceLocation.tryParse(rawId.trim());
        return id == null ? null : BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
    }
}
