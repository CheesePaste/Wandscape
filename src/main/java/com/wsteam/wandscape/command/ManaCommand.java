package com.wsteam.wandscape.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.CommandNode;
import com.wsteam.wandscape.core.component.ManaPool;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.engine.WandscapeEngine;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * Debug command: query mana, adjust regen rate, toggle mana chat feedback.
 *
 * <p>Usage:
 * <pre>
 *   /wandscape mana                  — show mana of all NPCs
 *   /wandscape mana regen &lt;rate&gt;    — set regen-per-tick for all NPCs
 *   /wandscape mana debug             — toggle mana debug chat messages
 * </pre>
 */
public final class ManaCommand {

    private ManaCommand() {}

    public static CommandNode<CommandSourceStack> node() {
        return Commands.literal("mana")
                .executes(ManaCommand::queryMana)
                .then(Commands.literal("regen")
                        .then(Commands.argument("rate", FloatArgumentType.floatArg(0, 1000))
                                .executes(ManaCommand::setRegen)))
                .then(Commands.literal("debug")
                        .executes(ManaCommand::toggleDebug))
                .build();
    }

    private static int queryMana(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        World world = WandscapeEngine.getWorld();
        if (world == null) {
            src.sendFailure(Component.literal("[Wandscape] Engine not bootstrapped"));
            return 0;
        }

        List<Long> entities = world.query(ManaPool.class);
        if (entities.isEmpty()) {
            src.sendSuccess(() -> Component.literal("[Wandscape] No entities with mana"), false);
            return Command.SINGLE_SUCCESS;
        }

        src.sendSuccess(() -> Component.literal("[Wandscape] ── Mana (" + entities.size() + " entities) ──"), false);
        for (long entity : entities) {
            ManaPool pool = world.get(entity, ManaPool.class);
            if (pool != null) {
                final long id = entity;
                final String line = String.format("  NPC-%d: %.1f/%d (+%.1f/tick)",
                        id, pool.current(), pool.max(), pool.regenPerTick());
                src.sendSuccess(() -> Component.literal(line), false);
            }
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int setRegen(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        float rate = FloatArgumentType.getFloat(ctx, "rate");

        World world = WandscapeEngine.getWorld();
        if (world == null) {
            src.sendFailure(Component.literal("[Wandscape] Engine not bootstrapped"));
            return 0;
        }

        int count = 0;
        for (long entity : world.query(ManaPool.class)) {
            ManaPool pool = world.get(entity, ManaPool.class);
            if (pool != null) {
                pool.setRegenPerTick(rate);
                count++;
            }
        }

        final int finalCount = count;
        final String rateStr = String.format("%.1f", rate);
        src.sendSuccess(() -> Component.literal(
                "[Wandscape] Mana regen set to " + rateStr + "/tick for " + finalCount + " NPCs"),
                true);
        return Command.SINGLE_SUCCESS;
    }

    private static int toggleDebug(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayer();
        if (player == null) {
            src.sendFailure(Component.literal("[Wandscape] Player-only command"));
            return 0;
        }

        WandscapeEngine.setManaDebug(!WandscapeEngine.isManaDebug());
        if (WandscapeEngine.isManaDebug()) {
            WandscapeEngine.setManaDebugTarget(player);
            src.sendSuccess(() -> Component.literal("[Wandscape] Mana debug ON — values printed to your chat every 5s"),
                    false);
        } else {
            WandscapeEngine.setManaDebugTarget(null);
            src.sendSuccess(() -> Component.literal("[Wandscape] Mana debug OFF"), false);
        }
        return Command.SINGLE_SUCCESS;
    }
}
