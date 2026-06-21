package com.wsteam.wandscape.command;

import java.util.Random;

import org.slf4j.Logger;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.building.internal.BuildingSavedData;
import com.wsteam.wandscape.building.internal.EnqueueHelper;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.task.TaskRequest;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.shared.data.WorkItem;
import com.wsteam.wandscape.shared.registry.WandscapeApis;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

/**
 * End-to-end test command for building + road pipeline.
 *
 * <p>Submits building blueprint tasks for N buildings placed in random
 * scattered positions around the command source, with optional random
 * Y variation to test terrain adaptation.
 * NPCs build them → {@code build_complete} fires → RoadEventListener → roads.
 *
 * <p>Usage:
 * <pre>
 *   /wandscape roadtest &lt;spacing&gt; &lt;count&gt;
 *   /wandscape roadtest &lt;spacing&gt; &lt;count&gt; &lt;buildingType&gt;
 *   /wandscape roadtest &lt;spacing&gt; &lt;count&gt; &lt;buildingType&gt; &lt;maxYVar&gt;
 * </pre>
 *
 * <p>Default building type: town_hall (tiny 3×2×3, fast to build).
 * <p>maxYVar: max random Y offset from command source (default 0 = flat, 10 = rolling hills).
 * <p>Check progress: {@code /wandscape road info}
 */
public final class RoadTestCommand {

    private static final Logger LOGGER = LogUtils.getLogger();

    private RoadTestCommand() {}

    public static CommandNode<CommandSourceStack> node() {
        var maxYVarNode = Commands.argument("maxYVar", IntegerArgumentType.integer(0, 30))
                .executes(ctx -> {
                    String bt = com.mojang.brigadier.arguments.StringArgumentType
                            .getString(ctx, "buildingType");
                    int yv = IntegerArgumentType.getInteger(ctx, "maxYVar");
                    return execute(ctx, bt, yv);
                });

        var buildingTypeNode = Commands.argument("buildingType",
                        com.mojang.brigadier.arguments.StringArgumentType.word())
                .executes(ctx -> {
                    String bt = com.mojang.brigadier.arguments.StringArgumentType
                            .getString(ctx, "buildingType");
                    return execute(ctx, bt, 0);
                })
                .then(maxYVarNode);

        var countNode = Commands.argument("count", IntegerArgumentType.integer(3, 16))
                .executes(ctx -> execute(ctx, "town_hall", 0))
                .then(buildingTypeNode);

        var spacingNode = Commands.argument("spacing", IntegerArgumentType.integer(5, 64))
                .then(countNode);

        return Commands.literal("roadtest")
                .requires(src -> src.hasPermission(2))
                .then(spacingNode)
                .build();
    }

    private static int execute(CommandContext<CommandSourceStack> ctx,
                                String buildingType, int maxYVar) {
        int spacing = IntegerArgumentType.getInteger(ctx, "spacing");
        int count = IntegerArgumentType.getInteger(ctx, "count");
        CommandSourceStack src = ctx.getSource();

        // ── 1. Validate building type ──
        BuildingConfigLoader configLoader = BuildingConfigLoader.getInstance();
        BuildingConfig config = configLoader.get(buildingType);
        if (config == null) {
            src.sendFailure(Component.literal(
                    "[RoadTest] Unknown building type: " + buildingType
                    + ". Known: " + configLoader.getAll().keySet()));
            return 0;
        }

        // ── 2. Get engine references ──
        World world = WandscapeEngine.getWorld();
        if (world == null || world.taskPool == null) {
            src.sendFailure(Component.literal(
                    "[RoadTest] Engine not bootstrapped"));
            return 0;
        }

        // ── 3. Register buildings + submit blueprint tasks, randomly scattered ──
        BlockPos center = BlockPos.containing(src.getPosition());
        int baseY = center.getY();
        int submitted = 0;
        int minRadius = spacing / 2;
        Random rng = new Random();

        for (int i = 0; i < count; i++) {
            double angle = 2.0 * Math.PI * rng.nextDouble();
            int radius = minRadius + rng.nextInt(spacing - minRadius + 1);
            int dx = (int) Math.round(radius * Math.cos(angle));
            int dz = (int) Math.round(radius * Math.sin(angle));
            int dy = maxYVar > 0
                    ? rng.nextInt(-maxYVar, maxYVar + 1)
                    : 0;
            BlockPos pos = center.offset(dx, dy, dz);

            // Register in BuildingSavedData (initially structureIntact=false)
            EnqueueHelper.registerIfAbsent(pos, config, buildingType);

            // Submit the blueprint task so NPCs actually build the building
            WorkItem work = EnqueueHelper.buildWorkItem(config, pos, buildingType, 10);
            TaskRequest request = new TaskRequest(
                    work.blueprintId(), work.params(), work.priority());
            world.taskPool.addTask(request);
            submitted++;
        }

        // ── 4. Report ──
        int threshold = WandscapeApis.getRoadApi().getBuildingThreshold();
        int poolSize = world.taskPool.size();
        String terrainHint = maxYVar > 0
                ? String.format("  Terrain: maxYVar=%d (random Y offsets applied)\n", maxYVar)
                : "  Terrain: flat (all buildings at same Y)\n";
        String msg = String.format(
                "[RoadTest] %d building tasks submitted (building=%s, maxRadius=%d)\n"
                        + "%s"
                        + "  Total tasks in pool: %d\n"
                        + "  Road threshold: %d\n"
                        + "\n-> NPCs will build buildings first\n"
                        + "-> After %d buildings complete, MST roads auto-trigger\n"
                        + "-> Check /wandscape road info for progress",
                submitted, buildingType, spacing,
                terrainHint,
                poolSize, threshold, threshold);

        src.sendSuccess(() -> Component.literal("§a" + msg), false);
        return Command.SINGLE_SUCCESS;
    }
}
