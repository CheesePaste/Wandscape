package com.wsteam.wandscape.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.CommandNode;
import com.wsteam.wandscape.magic.internal.MagicCaster;
import com.wsteam.wandscape.magic.internal.MagicCircleLoader;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * 调试命令：施放一座魔法阵，动画结束后信标光束射向准星目标。
 *
 * <pre>
 *   /wandscape magic                — 施放默认法阵 arcane_hexagram
 *   /wandscape magic &lt;circle&gt;        — 指定法阵 spec id
 *   /wandscape magic &lt;circle&gt; &lt;color&gt; — 指定光束颜色（hex，# 可省略）
 * </pre>
 */
public final class MagicCommand {

    private MagicCommand() {}

    public static CommandNode<CommandSourceStack> node() {
        return Commands.literal("magic")
                .executes(ctx -> doCast(ctx, MagicCaster.DEFAULT_CIRCLE, null))
                .then(Commands.argument("circle", StringArgumentType.word())
                        .executes(ctx -> doCast(ctx, StringArgumentType.getString(ctx, "circle"), null))
                        .then(Commands.argument("color", StringArgumentType.string())
                                .executes(ctx -> doCast(ctx, StringArgumentType.getString(ctx, "circle"),
                                        StringArgumentType.getString(ctx, "color")))))
                .build();
    }

    private static int doCast(CommandContext<CommandSourceStack> ctx, String circleId, String colorHex) {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayer();
        if (player == null) {
            src.sendFailure(Component.literal("[Wandscape] 仅玩家可施放魔法阵"));
            return 0;
        }
        if (MagicCircleLoader.getSpec(circleId) == null) {
            src.sendFailure(Component.literal("[Wandscape] 未找到法阵 " + circleId));
            return 0;
        }
        if (MagicCaster.cast(player.serverLevel(), player, circleId, colorHex)) {
            src.sendSuccess(() -> Component.literal("[Wandscape] 施放法阵 " + circleId), false);
        } else {
            src.sendFailure(Component.literal("[Wandscape] 已有施法进行中"));
        }
        return Command.SINGLE_SUCCESS;
    }
}
