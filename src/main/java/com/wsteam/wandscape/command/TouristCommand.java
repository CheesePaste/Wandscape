package com.wsteam.wandscape.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.wsteam.wandscape.shared.data.BarRatio;
import com.wsteam.wandscape.tourist.entity.TouristEntity;
import com.wsteam.wandscape.tourist.internal.TouristCooldownDebug;
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
 * /wandscape tourist cooldown &lt;service|visited|preference|all&gt; &lt;on|off&gt;
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
                .then(Commands.literal("cooldown")
                        .then(Commands.argument("layer", StringArgumentType.word())
                                .suggests(TouristCommand::suggestLayers)
                                .then(Commands.argument("toggle", StringArgumentType.word())
                                        .suggests(TouristCommand::suggestToggle)
                                        .executes(TouristCommand::cooldownToggle))))
                .build();
    }

    // ── list / spawn / state ──

    private static int list(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();

        List<TouristEntity> tourists = findTourists(level);
        List<String> lines = new ArrayList<>();
        lines.add("=== Tourists: " + tourists.size() + " active ===");

        for (TouristEntity t : tourists) {
            String appearance = t.isMage() ? "法师" : "市民";
            BarRatio br = BarRatio.of(t.getComfortSat(), t.getComfortNeed(),
                    t.getMagicSat(), t.getMagicNeed(), t.getWonderSat(), t.getWonderNeed());
            lines.add(String.format("  %s | %s | %s | Lv.%d | 精力%d | C%d%% M%d%% W%d%%",
                    t.getTouristName(), appearance,
                    t.getCurrentState().getDisplayName(),
                    t.getLevel(), t.getEnergy(), br.comfort(), br.magic(), br.wonder()));
        }

        // Show debug flag state
        lines.add("");
        lines.add("--- Cooldown Debug ---");
        lines.add("  service : " + (TouristCooldownDebug.skipServiceCooldown ? "DISABLED (skip)" : "ENABLED (normal)"));
        lines.add("  visited : " + (TouristCooldownDebug.skipVisitedBuildings ? "DISABLED (skip)" : "ENABLED (normal)"));
        lines.add("  pref    : " + (TouristCooldownDebug.skipPreferenceDecay ? "DISABLED (skip)" : "ENABLED (normal)"));

        String msg = String.join("\n", lines);
        src.sendSuccess(() -> Component.literal(msg), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int forceSpawn(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();

        int before = countTourists(level);
        TouristSpawnSystem.forceSpawn(level);
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
                t.forceMoveMode(targetState);
            }
            src.sendSuccess(() -> Component.literal(
                    "[Tourist] All " + tourists.size() + " tourists → " + targetState.getDisplayName()), false);
            return Command.SINGLE_SUCCESS;
        }

        for (TouristEntity t : tourists) {
            if (t.getTouristName().startsWith(name)) {
                t.forceMoveMode(targetState);
                src.sendSuccess(() -> Component.literal(
                        "[Tourist] " + t.getTouristName() + " → " + targetState.getDisplayName()), false);
                return Command.SINGLE_SUCCESS;
            }
        }
        src.sendFailure(Component.literal("No tourist matching '" + name + "'"));
        return 0;
    }

    // ── cooldown toggle ──

    private static int cooldownToggle(CommandContext<CommandSourceStack> ctx) {
        String layer = StringArgumentType.getString(ctx, "layer").toLowerCase();
        String toggle = StringArgumentType.getString(ctx, "toggle").toLowerCase();

        boolean enable;
        if ("on".equals(toggle)) {
            enable = true;
        } else if ("off".equals(toggle)) {
            enable = false;
        } else {
            ctx.getSource().sendFailure(Component.literal(
                    "Expected 'on' or 'off', got '" + toggle + "'"));
            return 0;
        }

        switch (layer) {
            case "service" -> {
                TouristCooldownDebug.skipServiceCooldown = !enable;
            }
            case "visited" -> {
                TouristCooldownDebug.skipVisitedBuildings = !enable;
            }
            case "preference", "pref" -> {
                TouristCooldownDebug.skipPreferenceDecay = !enable;
            }
            case "all" -> {
                if (enable) {
                    TouristCooldownDebug.enableAll();
                } else {
                    TouristCooldownDebug.disableAll();
                }
            }
            default -> {
                ctx.getSource().sendFailure(Component.literal(
                        "Unknown layer: '" + layer + "'. Valid: service, visited, preference, all"));
                return 0;
            }
        }

        String state = enable ? "ENABLED (normal)" : "DISABLED (debug skip)";
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[Tourist] Cooldown '" + layer + "' -> " + state), true);

        // Also log to server console for traceability
        com.wsteam.wandscape.shared.log.Log.info("TouristCommand",
                "[Debug] Cooldown '{}' set to {}", layer, enable ? "on" : "off");

        return Command.SINGLE_SUCCESS;
    }

    // ── Suggestions ──

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

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestLayers(
            CommandContext<CommandSourceStack> ctx,
            com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        builder.suggest("service");
        builder.suggest("visited");
        builder.suggest("preference");
        builder.suggest("all");
        return builder.buildFuture();
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestToggle(
            CommandContext<CommandSourceStack> ctx,
            com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        builder.suggest("on");
        builder.suggest("off");
        return builder.buildFuture();
    }

    // ── Helpers ──

    private static List<TouristEntity> findTourists(ServerLevel level) {
        List<TouristEntity> result = new ArrayList<>();
        for (var entity : level.getAllEntities()) {
            if (entity instanceof TouristEntity t && t.isAlive()) {
                result.add(t);
            }
        }
        return result;
    }
}
