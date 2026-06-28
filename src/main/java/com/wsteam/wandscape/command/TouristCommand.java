package com.wsteam.wandscape.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.wsteam.wandscape.tourist.entity.TouristEntity;
import com.wsteam.wandscape.tourist.internal.TouristSpawnSystem;
import com.wsteam.wandscape.tourist.internal.TouristState;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;
/**
 * Debug commands for tourist NPC testing.
 *
 * <pre>
 * /wandscape tourist list
 * /wandscape tourist spawn
 * /wandscape tourist state &lt;name|all&gt; &lt;state&gt;
 * </pre>
 */
public final class TouristCommand {

    private TouristCommand() {}

    public static com.mojang.brigadier.tree.CommandNode<CommandSourceStack> node() {
        return Commands.literal("tourist")
                .then(Commands.literal("list")
                        .executes(TouristCommand::list))
                .then(Commands.literal("spawn")
                        .executes(TouristCommand::forceSpawn))
                .then(Commands.literal("state")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("state", StringArgumentType.word())
                                        .suggests(TouristCommand::suggestStates)
                                        .executes(TouristCommand::forceState))))
                .build();
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();

        List<TouristEntity> tourists = findTourists(level);
        List<String> lines = new ArrayList<>();
        lines.add("=== Tourists: " + tourists.size() + " active ===");

        for (TouristEntity t : tourists) {
            String appearance = t.isMage() ? "法师" : "市民";
            lines.add(String.format("  %s | %s | %s | Lv.%d | 精力%d | 满意%d%%",
                    t.getTouristName(), appearance,
                    t.getCurrentState().getDisplayName(),
                    t.getLevel(), t.getEnergy(), t.getSatisfaction()));
        }

        String msg = String.join("\n", lines);
        src.sendSuccess(() -> Component.literal(msg), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int forceSpawn(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();

        int before = countTourists(level);
        TouristSpawnSystem.forceSpawn(level);
        // forceSpawn is sync, count after
        int after = countTourists(level);
        int spawned = after - before;

        src.sendSuccess(() -> Component.literal(
                "[Tourist] Spawn triggered. Before=" + before
                        + ", After=" + after + ", New=" + spawned), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int countTourists(ServerLevel level) {
        int count = 0;
        for (var entity : level.getAllEntities()) {
            if (entity instanceof TouristEntity t && t.isAlive()) {
                count++;
            }
        }
        return count;
    }

    private static int forceState(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        String stateName = StringArgumentType.getString(ctx, "state");

        TouristState targetState;
        try {
            targetState = TouristState.valueOf(stateName.toUpperCase());
        } catch (IllegalArgumentException e) {
            src.sendFailure(Component.literal("Unknown state: " + stateName
                    + ". Valid: visiting, exploring, wandering, idle, sleeping"));
            return 0;
        }

        ServerLevel level = src.getLevel();
        List<TouristEntity> tourists = findTourists(level);

        if ("all".equalsIgnoreCase(name)) {
            for (TouristEntity t : tourists) {
                t.applyState(targetState);
            }
            src.sendSuccess(() -> Component.literal(
                    "[Tourist] All " + tourists.size() + " tourists → " + targetState.getDisplayName()), false);
            return Command.SINGLE_SUCCESS;
        }

        for (TouristEntity t : tourists) {
            if (t.getTouristName().startsWith(name)) {
                t.applyState(targetState);
                src.sendSuccess(() -> Component.literal(
                        "[Tourist] " + t.getTouristName() + " → " + targetState.getDisplayName()), false);
                return Command.SINGLE_SUCCESS;
            }
        }
        src.sendFailure(Component.literal("No tourist matching '" + name + "'"));
        return 0;
    }

    private static List<TouristEntity> findTourists(ServerLevel level) {
        List<TouristEntity> result = new ArrayList<>();
        for (var entity : level.getAllEntities()) {
            if (entity instanceof TouristEntity t && t.isAlive()) {
                result.add(t);
            }
        }
        return result;
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestStates(
            CommandContext<CommandSourceStack> ctx,
            com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        builder.suggest("visiting");
        builder.suggest("exploring");
        builder.suggest("wandering");
        builder.suggest("idle");
        builder.suggest("sleeping");
        return builder.buildFuture();
    }
}
