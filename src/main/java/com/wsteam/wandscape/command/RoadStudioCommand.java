package com.wsteam.wandscape.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.CommandNode;
import com.wsteam.wandscape.road.network.SplineEditorEnterPacket;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Test command for the native Road Studio overlay (no ImGui dependency).
 * <p>Usage:
 * <pre>
 *   /wandscape roadstudio       - enter road studio (opens both ImGui + native overlay for comparison)
 * </pre>
 * <p>Once inside, press F11 to toggle between ImGui and native overlay.
 */
public final class RoadStudioCommand {
    private RoadStudioCommand() {}

    public static CommandNode<CommandSourceStack> node() {
        return Commands.literal("roadstudio")
                .requires(src -> src.hasPermission(2))
                .executes(RoadStudioCommand::enter)
                .build();
    }

    private static int enter(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("§cPlayer-only command"));
            return 0;
        }

        // Enter edit mode on the client (reuses existing SplineEditorEnterPacket)
        PacketDistributor.sendToPlayer(player, new SplineEditorEnterPacket(true));

        ctx.getSource().sendSuccess(() -> Component.literal(
                "§aRoad Studio opened!\n" +
                "§7Press §eF11§7 to toggle between ImGui and native overlay.\n" +
                "§7ESC to exit."),
                true);
        return 1;
    }
}
