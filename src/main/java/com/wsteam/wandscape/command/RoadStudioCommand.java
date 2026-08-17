package com.wsteam.wandscape.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.CommandNode;
import com.wsteam.wandscape.road.network.RoadStudioEnterPacket;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Command for the native self-drawn Road Studio overlay.
 * <p>Usage:
 * <pre>
 *   /wandscape roadstudio        - enter road studio (native UI, no ImGui)
 *   /wandscape roadstudio edit   - enter road studio
 *   /wandscape roadstudio done   - exit road studio
 *   /wandscape roadstudio exit   - exit road studio
 * </pre>
 */
public final class RoadStudioCommand {
    private RoadStudioCommand() {}

    public static CommandNode<CommandSourceStack> node() {
        return Commands.literal("roadstudio")
                .requires(src -> src.hasPermission(2))
                .executes(RoadStudioCommand::enter)
                .then(Commands.literal("edit").executes(RoadStudioCommand::enter))
                .then(Commands.literal("done").executes(RoadStudioCommand::done))
                .then(Commands.literal("exit").executes(RoadStudioCommand::done))
                .build();
    }

    private static int enter(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("§cPlayer-only command"));
            return 0;
        }

        // Direct entry to native Road Studio overlay (NO ImGui!)
        PacketDistributor.sendToPlayer(player, new RoadStudioEnterPacket(true));

        ctx.getSource().sendSuccess(() -> Component.literal(
                "§aEntered Road Studio (Native UI)!\n" +
                "§7• Hold RMB to rotate camera | WASD to fly | Scroll to zoom\n" +
                "§7• Click right-side panel to edit tools and spline properties\n" +
                "§7• Click 3D world to place/select points\n" +
                "§7• Press ESC or click Close to exit."),
                true);
        return 1;
    }

    private static int done(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("§cPlayer-only command"));
            return 0;
        }

        PacketDistributor.sendToPlayer(player, new RoadStudioEnterPacket(false));
        ctx.getSource().sendSuccess(() -> Component.literal("§eExited Road Studio."), true);
        return 1;
    }
}
