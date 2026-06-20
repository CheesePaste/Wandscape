package com.wsteam.wandscape.command;

import java.util.Map;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.wsteam.wandscape.building.be.AbstractWandscapeBE;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.building.internal.EnqueueHelper;
import com.wsteam.wandscape.shared.data.WorkItem;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Debug command: place N building blocks spaced A apart along +X axis,
 * registering and enqueuing blueprint work for each one.
 *
 * <p>Usage: {@code /wandscape fill <buildingType> <spacing> <count>}
 * <ul>
 *   <li>{@code buildingType} — config id (e.g. "town_hall", "forest_node")</li>
 *   <li>{@code spacing} — blocks between each anchor (≥ 1)</li>
 *   <li>{@code count} — number of buildings to place (1–64)</li>
 * </ul>
 */
public final class FillBuildingCommand {

    private FillBuildingCommand() {}

    public static final String NAME = "wandscape";

    /** Build the "fill" sub-command node. Attach to a parent {@code /wandscape} literal. */
    public static LiteralArgumentBuilder<CommandSourceStack> buildNode() {
        return net.minecraft.commands.Commands.literal("fill")
                .then(net.minecraft.commands.Commands.argument("buildingType",
                                StringArgumentType.word())
                        .suggests(FillBuildingCommand::suggestTypes)
                        .then(net.minecraft.commands.Commands.argument("spacing",
                                        IntegerArgumentType.integer(1, 32))
                                .then(net.minecraft.commands.Commands.argument("count",
                                                IntegerArgumentType.integer(1, 64))
                                        .executes(FillBuildingCommand::execute))));
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

        Block block = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getOptional(net.minecraft.resources.ResourceLocation.tryParse(config.blockId()))
                .orElse(null);
        if (block == null) {
            src.sendFailure(Component.literal(
                    "[Wandscape] Block not found: " + config.blockId()));
            return 0;
        }

        var level = src.getLevel();
        int placed = 0;

        for (int i = 0; i < count; i++) {
            BlockPos pos = origin.offset(i * spacing, 0, 0);
            BlockState state = block.defaultBlockState();

            // Place the block
            level.setBlock(pos, state, 3);

            // Register with BuildingApi + enqueue blueprint work
            var be = level.getBlockEntity(pos);
            if (be instanceof AbstractWandscapeBE buildingBe) {
                EnqueueHelper.registerIfAbsent(pos, config, type);
                WorkItem work = EnqueueHelper.buildWorkItem(config, pos, type, 10);
                buildingBe.enqueueWork(work);
                placed++;
            }
        }

        int finalPlaced = placed;
        src.sendSuccess(() -> Component.literal(
                "[Wandscape] Placed " + finalPlaced + "/" + count
                + " " + type + " buildings (spacing=" + spacing + ") → "
                + finalPlaced + " tasks enqueued"),
                true);
        return Command.SINGLE_SUCCESS;
    }
}
