package com.wsteam.wandscape.command;

import java.util.UUID;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.CommandNode;
import com.wsteam.wandscape.core.road.RoadEdge;
import com.wsteam.wandscape.core.road.RoadNetwork;
import com.wsteam.wandscape.road.network.RoadEditorNetwork;
import com.wsteam.wandscape.shared.api.RoadApi;
import com.wsteam.wandscape.shared.registry.WandscapeApis;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Road system debug commands.
 *
 * <p>Usage:
 * <pre>
 *   /wandscape road info     — show road network statistics
 *   /wandscape road rebuild  — trigger full MST rebuild
 *   /wandscape road edit     — toggle road editor mode
 * </pre>
 */
public final class RoadCommand {

    private RoadCommand() {}

    public static CommandNode<CommandSourceStack> node() {
        return Commands.literal("road")
                .then(Commands.literal("info")
                        .executes(RoadCommand::info))
                .then(Commands.literal("rebuild")
                        .requires(src -> src.hasPermission(2))
                        .executes(RoadCommand::rebuild))
                .then(Commands.literal("edit")
                        .executes(RoadCommand::toggleEdit))
                .build();
    }

    private static int info(CommandContext<CommandSourceStack> ctx) {
        try {
            RoadApi api = WandscapeApis.getRoadApi();
            RoadNetwork network = api.getNetwork(null);
            final int nodeCount = network.nodeCount();
            final int edgeCount = network.edgeCount();

            final int[] planned = {0}, building = {0}, completed = {0};
            final int[] totalPathLen = {0};
            for (RoadEdge edge : network.getEdges().values()) {
                switch (edge.getStatus()) {
                    case PLANNED -> planned[0]++;
                    case BUILDING -> building[0]++;
                    case COMPLETE -> completed[0]++;
                }
                totalPathLen[0] += edge.getPath().size();
            }

            final int p = planned[0], b = building[0], c = completed[0], t = totalPathLen[0];
            ctx.getSource().sendSuccess(() -> Component.literal(
                    String.format("§6Road Network: §f%d nodes, §f%d edges "
                                    + "(§a%d complete§f, §e%d building§f, §7%d planned§f), "
                                    + "total length: §f%d tiles",
                            nodeCount, edgeCount, c, b, p, t)),
                    false);
        } catch (IllegalStateException e) {
            ctx.getSource().sendFailure(Component.literal(
                    "§cRoad system not loaded"));
        }
        return 1;
    }

    private static int rebuild(CommandContext<CommandSourceStack> ctx) {
        try {
            RoadApi api = WandscapeApis.getRoadApi();
            // V1: use null colonyId (colony system not yet implemented)
            api.requestFullRebuild(null);

            ctx.getSource().sendSuccess(() -> Component.literal(
                    "§aRoad rebuild triggered — computed MST, diff applied, "
                            + "new segments enqueued"), true);
        } catch (IllegalStateException e) {
            ctx.getSource().sendFailure(Component.literal(
                    "§cRoad system not loaded"));
        }
        return 1;
    }

    private static int toggleEdit(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer player = ctx.getSource().getPlayer();
            if (player == null) {
                ctx.getSource().sendFailure(Component.literal(
                        "§cThis command can only be used by a player"));
                return 0;
            }

            if (RoadEditorNetwork.isEditing(player)) {
                // Exit edit mode
                RoadEditorNetwork.removeEditing(player);
                RoadEditorNetwork.sendExitToPlayer(player);
                ctx.getSource().sendSuccess(() -> Component.literal(
                        "§eRoad edit mode: §cOFF"), true);
            } else {
                // Enter edit mode
                RoadEditorNetwork.addEditing(player);
                RoadEditorNetwork.sendSyncToPlayer(player);
                ctx.getSource().sendSuccess(() -> Component.literal(
                        "§aRoad edit mode: §2ON §7— edges=green/yellow/blue, "
                                + "left-click edge to remove"), true);
            }
        } catch (IllegalStateException e) {
            ctx.getSource().sendFailure(Component.literal(
                    "§cRoad system not loaded"));
        }
        return 1;
    }
}
