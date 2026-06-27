package com.wsteam.wandscape.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.wsteam.wandscape.citizen.CitizenManager;
import com.wsteam.wandscape.citizen.CitizenState;
import com.wsteam.wandscape.tourist.entity.TouristEntity;

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
        lines.add("=== Tourists: " + mgr.countActive() + " active ===");

        for (TouristEntity t : mgr.getActiveCitizens()) {
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

    private static int forceState(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        String stateName = StringArgumentType.getString(ctx, "state");

        CitizenState targetState;
        try {
            targetState = CitizenState.valueOf(stateName.toUpperCase());
        } catch (IllegalArgumentException e) {
            src.sendFailure(Component.literal("Unknown state: " + stateName
                    + ". Valid: visiting, exploring, wandering, idle, sleeping"));
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
        builder.suggest("visiting");
        builder.suggest("exploring");
        builder.suggest("wandering");
        builder.suggest("idle");
        builder.suggest("sleeping");
        return builder.buildFuture();
    }
}
