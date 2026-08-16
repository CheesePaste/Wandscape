package com.wsteam.wandscape.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.CommandNode;
import com.wsteam.wandscape.road.network.SplineEditorEnterPacket;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Commands to enter and exit the Spline Road Editor.
 *
 * <p>Usage:
 * <pre>
 *   /wandscape spline edit     - enter spline editor
 *   /wandscape spline done     - exit spline editor
 * </pre>
 */
public final class SplineEditorCommand {
    private static final String TAG = "SplineEditorCommand";

    private SplineEditorCommand() {}

    public static CommandNode<CommandSourceStack> node() {
        return Commands.literal("spline")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("edit")
                        .executes(SplineEditorCommand::edit))
                .then(Commands.literal("done")
                        .executes(SplineEditorCommand::done))
                .then(Commands.literal("mui")
                        .executes(SplineEditorCommand::openMuiTest))
                .build();
    }

    public static int openMuiTest(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("§cPlayer-only command"));
            return 0;
        }

        PacketDistributor.sendToPlayer(player, new com.wsteam.wandscape.road.network.ModernUITestPacket());
        ctx.getSource().sendSuccess(() -> Component.literal("§a[ModernUI] Opened Road Studio test screen on client."), true);
        return 1;
    }

    private static int edit(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("§cPlayer-only command"));
            return 0;
        }

        PacketDistributor.sendToPlayer(player, new SplineEditorEnterPacket(true));

        ctx.getSource().sendSuccess(() -> Component.literal(
                "§aEntered spline editor mode!\n" +
                "§7Left-click: add anchor point (Add mode) | Click point to select (Edit mode)\n" +
                "§7Drag RGB arrow shafts to move coordinates | Delete/Backspace to remove\n" +
                "§7Hold Shift while dragging to temporarily break symmetric locking\n" +
                "§7Close editor using ESC or the panel button."),
                true);
        return 1;
    }

    private static int done(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("§cPlayer-only command"));
            return 0;
        }

        PacketDistributor.sendToPlayer(player, new SplineEditorEnterPacket(false));

        ctx.getSource().sendSuccess(() -> Component.literal("§eExited spline editor mode."), true);
        return 1;
    }
}
