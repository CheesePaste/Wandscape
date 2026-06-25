package com.wsteam.wandscape.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.wsteam.wandscape.citizen.CitizenEntity;
import com.wsteam.wandscape.citizen.CitizenManager;
import com.wsteam.wandscape.citizen.CitizenState;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Debug commands for citizen NPC testing.
 *
 * <pre>
 * /wandscape citizen list
 * /wandscape citizen state &lt;name|all&gt; &lt;state&gt;
 * </pre>
 */
public final class CitizenCommand {

    private CitizenCommand() {}

    public static com.mojang.brigadier.tree.CommandNode<CommandSourceStack> node() {
        return Commands.literal("citizen")
                .then(Commands.literal("list")
                        .executes(CitizenCommand::list))
                .then(Commands.literal("state")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("state", StringArgumentType.word())
                                        .suggests(CitizenCommand::suggestStates)
                                        .executes(CitizenCommand::forceState))))
                .build();
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        CitizenManager mgr = CitizenManager.getInstance();

        List<String> lines = new ArrayList<>();
        lines.add("=== Citizens: " + mgr.countActive() + " active + "
                + mgr.countStored() + " stored = " + mgr.countTotal() + " total ===");

        // Active
        for (CitizenEntity c : mgr.getActiveCitizens()) {
            lines.add(String.format("  [ACTIVE] %s | %s | %s | mood=%d",
                    c.getCitizenName(), c.getProfession().getDisplayName(),
                    c.getCurrentState().getDisplayName(), c.getMood()));
        }

        // Stored
        for (var e : mgr.getStoredCitizens().entrySet()) {
            var sc = e.getValue();
            lines.add(String.format("  [STORED] %s | %s | %s | mood=%d",
                    sc.name(), sc.profession().getDisplayName(),
                    sc.storedState().getDisplayName(), sc.mood()));
        }

        String msg = String.join("\n", lines);
        src.sendSuccess(() -> Component.literal(msg), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int forceState(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        String stateName = StringArgumentType.getString(ctx, "state");

        CitizenState targetState;
        try {
            targetState = CitizenState.valueOf(stateName.toUpperCase());
        } catch (IllegalArgumentException e) {
            src.sendFailure(Component.literal("Unknown state: " + stateName
                    + ". Valid: commuting, working, leisure, idle, sleeping"));
            return 0;
        }

        ServerLevel level = src.getLevel();
        String result = CitizenManager.getInstance().debugForceState(name, targetState, level);
        src.sendSuccess(() -> Component.literal("[Citizen] " + result), false);
        return Command.SINGLE_SUCCESS;
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestStates(
            CommandContext<CommandSourceStack> ctx,
            com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        builder.suggest("commuting");
        builder.suggest("working");
        builder.suggest("leisure");
        builder.suggest("idle");
        builder.suggest("sleeping");
        return builder.buildFuture();
    }
}
