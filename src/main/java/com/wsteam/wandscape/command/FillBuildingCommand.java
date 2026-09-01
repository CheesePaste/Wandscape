package com.wsteam.wandscape.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.wsteam.wandscape.content.building.data.BuildingConfig;
import com.wsteam.wandscape.content.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.content.building.internal.EnqueueHelper;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.content.building.data.WorkItem;
import com.wsteam.wandscape.content.task.engine.pool.TaskRequest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
/**
 * Debug command: register N buildings spaced A apart along +X axis,
 * submitting blueprint work directly to the engine task pool.
 *
 * <p>No blocks are placed — buildings are registered logically and
 * tasks are submitted to {@code GlobalTaskPool} for NPC execution.
 *
 * <p>Usage: {@code /wandscape fill <buildingType> <spacing> <count>}
 * <ul>
 *   <li>{@code buildingType} — config id (e.g. "townhall1", "nodewood")</li>
 *   <li>{@code spacing} — blocks between each anchor (≥ 1)</li>
 *   <li>{@code count} — number of buildings to register (1–64)</li>
 * </ul>
 */
public final class FillBuildingCommand {

    private FillBuildingCommand() {}

    public static final String NAME = "wandscape";

    /** Call from {@code CommandRegistryEvent} — registers the full command tree. */
    public static void register(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                net.minecraft.commands.Commands.literal(NAME)
                        .then(fillNode())
                        .requires(src -> src.hasPermission(2)));
    }

    /** Build the "fill" subcommand node for central registration. */
    public static com.mojang.brigadier.tree.CommandNode<CommandSourceStack> fillNode() {
        return net.minecraft.commands.Commands.literal("fill")
                .then(net.minecraft.commands.Commands.argument("buildingType",
                                StringArgumentType.word())
                        .suggests(FillBuildingCommand::suggestTypes)
                        .then(net.minecraft.commands.Commands.argument("spacing",
                                        IntegerArgumentType.integer(1, 32))
                                .then(net.minecraft.commands.Commands.argument("count",
                                                IntegerArgumentType.integer(1, 64))
                                        .executes(FillBuildingCommand::execute))))
                .build();
    }

    /** Suggest known building type IDs. */
    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestTypes(
            CommandContext<CommandSourceStack> ctx,
            com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        for (String id : BuildingConfigLoader.getInstance().getAll().keySet()) {
            builder.suggest(id);
        }
        return builder.buildFuture();
    }

    private static int execute(CommandContext<CommandSourceStack> ctx) {
        String type = StringArgumentType.getString(ctx, "buildingType");
        int spacing = IntegerArgumentType.getInteger(ctx, "spacing");
        int count = IntegerArgumentType.getInteger(ctx, "count");

        CommandSourceStack src = ctx.getSource();
        BlockPos origin = BlockPos.containing(src.getPosition());

        BuildingConfigLoader configLoader = BuildingConfigLoader.getInstance();
        BuildingConfig config = configLoader.get(type);
        if (config == null) {
            src.sendFailure(Component.literal(
                    "[Wandscape] Unknown building type: " + type
                    + ". Known: " + configLoader.getAll().keySet()));
            return 0;
        }

        World world = WandscapeEngine.getWorld();
        if (world == null || world.taskPool == null) {
            src.sendFailure(Component.literal(
                    "[Wandscape] Engine not bootstrapped — task pool unavailable"));
            return 0;
        }

        int submitted = 0;
        // 调试命令也按玩家位置解析殖民地归属（无玩家/不在任何殖民地 → 无主任务）
        java.util.UUID colonyId = com.wsteam.wandscape.api.WandscapeApis.colonyAt(
                src.getPlayer() != null ? src.getPlayer().blockPosition() : null);

        for (int i = 0; i < count; i++) {
            BlockPos pos = origin.offset(i * spacing, 0, 0);

            EnqueueHelper.registerIfAbsent(pos, config, type);
            WorkItem work = EnqueueHelper.buildWorkItem(config, pos, type, 10);
            TaskRequest request = new TaskRequest(
                    work.blueprintId(), work.params(), work.priority(), colonyId);
            world.taskPool.addTask(request);
            submitted++;
        }

        int finalSubmitted = submitted;
        src.sendSuccess(() -> Component.literal(
                "[Wandscape] Registered " + finalSubmitted + "/" + count
                + " " + type + " buildings (spacing=" + spacing + ") → "
                + finalSubmitted + " tasks submitted to pool"),
                true);
        return Command.SINGLE_SUCCESS;
    }
}
