package com.wsteam.wandscape.command;
import com.wsteam.wandscape.content.task.ecs.World;
import com.wsteam.wandscape.content.building.data.WorkItem;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.content.building.data.BuildingConfig;
import com.wsteam.wandscape.content.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.content.building.internal.EnqueueHelper;
import com.wsteam.wandscape.impl.WandscapeEngine;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.registry.WandscapeConstants;
import com.wsteam.wandscape.content.task.engine.pool.TaskRequest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.MobSpawnType;

/**
 * Stress-test command: spawn N NPCs and create M town_hall building tasks at once.
 *
 * <p>Usage: {@code /wandscape stresstest <npcCount> <taskCount>}
 * <ul>
 *   <li>{@code npcCount} — number of WandscapeNpc to spawn (1–1000)</li>
 *   <li>{@code taskCount} — number of town_hall building tasks to enqueue (1–1000)</li>
 * </ul>
 *
 * <p>NPCs are spawned in a square grid with 2-block spacing.
 * Tasks use the full {@code build:clear_and_build} blueprint via town_hall
 * building config — clear boundary → place pattern blocks. Anchors are spread
 * in a 3D cube to distribute chunk load.
 */
public final class StressTestCommand {

    private StressTestCommand() {}

    /** Build the "stresstest" sub-command node. */
    public static LiteralArgumentBuilder<CommandSourceStack> buildNode() {
        return Commands.literal("stresstest")
                .then(Commands.argument("npcCount",
                                IntegerArgumentType.integer(1, 1000))
                        .then(Commands.argument("taskCount",
                                        IntegerArgumentType.integer(1, 1000))
                                .executes(StressTestCommand::execute)));
    }

    private static int execute(CommandContext<CommandSourceStack> ctx) {
        int npcCount = IntegerArgumentType.getInteger(ctx, "npcCount");
        int taskCount = IntegerArgumentType.getInteger(ctx, "taskCount");

        CommandSourceStack src = ctx.getSource();
        var level = src.getLevel();
        BlockPos origin = BlockPos.containing(src.getPosition());

        var world = WandscapeEngine.getWorld();
        if (world == null || world.taskPool == null) {
            src.sendFailure(Component.literal(
                    "[Wandscape] Engine not bootstrapped — stress test aborted"));
            return 0;
        }

        // Pre-load government building config as the stress-test target (fail fast if missing)
        BuildingConfig config = BuildingConfigLoader.getInstance()
                .getByCategory(WandscapeConstants.BUILDING_CATEGORY_GOVERNMENT);
        if (config == null) {
            src.sendFailure(Component.literal(
                    "[Wandscape] no government building config found "
                            + "(need a building JSON with category=government)"));
            return 0;
        }

        // ── Phase 1: spawn NPCs in a square grid ──
        int spawned = 0;
        int gridSize = (int) Math.ceil(Math.sqrt(npcCount));
        long startNanos = System.nanoTime();

        for (int i = 0; i < npcCount; i++) {
            int row = i / gridSize;
            int col = i % gridSize;
            BlockPos pos = origin.offset(col * 2, 0, row * 2);

            var npc = Wandscape.WANDSCAPE_NPC.get().spawn(level, pos, MobSpawnType.COMMAND);
            if (npc != null) {
                npc.setInvulnerable(true);
                npc.setPersistenceRequired();
                spawned++;
            }
        }

        long npcElapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        // ── Phase 2: create town_hall building tasks (3D cube distribution) ──
        int created = 0;
        startNanos = System.nanoTime();

        int cubeSide = (int) Math.ceil(Math.cbrt(taskCount));
        int spacing = 12; // town_hall boundary is 3×2×3, 12 blocks gives room
        // 压力测试也归属玩家殖民地（避免触发"建筑型任务无殖民地"告警），无玩家 → 无主
        java.util.UUID colonyId = com.wsteam.wandscape.api.WandscapeApis.colonyAt(
                src.getPlayer() != null ? src.getPlayer().blockPosition() : null);

        for (int i = 0; i < taskCount; i++) {
            try {
                int cx = i % cubeSide;
                int cy = (i / cubeSide) % cubeSide;
                int cz = i / (cubeSide * cubeSide);

                BlockPos anchor = origin.offset(cx * spacing, cy * spacing, cz * spacing);

                var workItem = EnqueueHelper.buildWorkItem(config, anchor, config.id(), 10);
                world.taskPool.addTask(
                        new TaskRequest(workItem.blueprintId(), workItem.params(), workItem.priority(), colonyId));
                created++;
            } catch (Exception e) {
                Log.warn("Wandscape", "[StressTest] Task #{} failed: {}", i, e.getMessage());
            }
        }

        long taskElapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        // ── Report ──
        int finalSpawned = spawned;
        int finalCreated = created;
        src.sendSuccess(() -> Component.literal(
                "[Wandscape] Stress test complete — "
                + "NPCs: " + finalSpawned + "/" + npcCount
                + " (" + npcElapsedMs + "ms), "
                + "town_hall tasks: " + finalCreated + "/" + taskCount
                + " (" + taskElapsedMs + "ms)"),
                true);

        Log.info("Wandscape", "[StressTest] NPCs={}/{} ({}ms) tasks={}/{} ({}ms) poolSize={}",
                finalSpawned, npcCount, npcElapsedMs,
                finalCreated, taskCount, taskElapsedMs,
                world.taskPool.size());

        return Command.SINGLE_SUCCESS;
    }
}
