package com.wsteam.wandscape.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.engine.boundary.WandscapeMovementOps;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/**
 * Debug command: make the nearest NPC pathfind to the nearest emerald block.
 *
 * <p>Usage: {@code /wandscape navtest}
 */
public final class NavTestCommand {

    private NavTestCommand() {}

    /** Build the subcommand node (attach to existing root literal). */
    public static com.mojang.brigadier.tree.CommandNode<CommandSourceStack> node() {
        return net.minecraft.commands.Commands.literal("navtest")
                .executes(NavTestCommand::execute)
                .build();
    }

    private static int execute(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        var level = src.getLevel();
        BlockPos origin = BlockPos.containing(src.getPosition());

        // 1. Find nearest WandscapeNpc within 64 blocks
        WandscapeNpc nearestNpc = null;
        double bestNpcDist = Double.MAX_VALUE;
        for (WandscapeNpc npc : level.getEntitiesOfClass(WandscapeNpc.class,
                new AABB(origin).inflate(64))) {
            double d = npc.position().distanceToSqr(src.getPosition());
            if (d < bestNpcDist) {
                bestNpcDist = d;
                nearestNpc = npc;
            }
        }

        if (nearestNpc == null) {
            src.sendFailure(Component.literal(
                    "[Wandscape] No NPC found within 64 blocks"));
            return 0;
        }

        // 2. Find nearest emerald block within 128 blocks of the NPC
        BlockPos npcPos = nearestNpc.blockPosition();
        BlockPos nearestEmerald = null;
        double bestEmeraldDist = Double.MAX_VALUE;

        for (int dx = -128; dx <= 128; dx++) {
            for (int dy = -32; dy <= 32; dy++) {
                for (int dz = -128; dz <= 128; dz++) {
                    BlockPos checkPos = npcPos.offset(dx, dy, dz);
                    if (level.getBlockState(checkPos).is(Blocks.EMERALD_BLOCK)) {
                        double d = checkPos.distSqr(npcPos);
                        if (d < bestEmeraldDist) {
                            bestEmeraldDist = d;
                            nearestEmerald = checkPos;
                        }
                    }
                }
            }
        }

        if (nearestEmerald == null) {
            src.sendFailure(Component.literal(
                    "[Wandscape] No emerald block found within 128 blocks of NPC at " + npcPos));
            return 0;
        }

        // 3. Trigger pathfinding via MovementOps
        WandscapeMovementOps mov = WandscapeEngine.getMovementOps();
        if (mov == null) {
            src.sendFailure(Component.literal(
                    "[Wandscape] MovementOps not initialized — engine not bootstrapped?"));
            return 0;
        }

        long npcId = nearestNpc.ecsEntityId;
        if (npcId <= 0) {
            src.sendFailure(Component.literal(
                    "[Wandscape] NPC has no ECS entity ID — not registered with engine"));
            return 0;
        }

        mov.navigateTo(npcId, nearestEmerald.getX(), nearestEmerald.getY(), nearestEmerald.getZ());

        final String npcName = nearestNpc.getNpcName();
        final BlockPos target = nearestEmerald;
        double dx = nearestNpc.getX() - (target.getX() + 0.5);
        double dz = nearestNpc.getZ() - (target.getZ() + 0.5);
        double hDist = Math.sqrt(dx * dx + dz * dz);
        final String mode = hDist <= 32 ? "pathfinding" : "ritual teleport";

        final double finalHDist = hDist;
        src.sendSuccess(() -> Component.literal(
                "[Wandscape] NPC \"" + npcName + "\" (ecsId=" + npcId + ")"
                + " → emerald at " + target
                + " (hDist=" + String.format("%.1f", finalHDist) + ", " + mode + ")"),
                true);
        return Command.SINGLE_SUCCESS;
    }
}
