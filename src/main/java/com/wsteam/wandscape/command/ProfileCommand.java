package com.wsteam.wandscape.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.CommandNode;
import com.wsteam.wandscape.shared.util.TickProfiler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * Debug command: control TickProfiler recording.
 *
 * <p>Usage:
 * <pre>
 *   /wandscape profile on|start     — enable tick profiling (writes to logs/wandscape-ticks.csv)
 *   /wandscape profile off|stop     — disable tick profiling
 *   /wandscape profile status       — show status and recorded spans count
 *   /wandscape profile clear        — clear recorded buffer
 * </pre>
 */
public final class ProfileCommand {

    private ProfileCommand() {}

    public static CommandNode<CommandSourceStack> node() {
        var builder = Commands.literal("profile")
                .executes(ProfileCommand::showStatus)
                .then(Commands.literal("start")
                        .executes(ProfileCommand::turnOn))
                .then(Commands.literal("on")
                        .executes(ProfileCommand::turnOn))
                .then(Commands.literal("stop")
                        .executes(ProfileCommand::turnOff))
                .then(Commands.literal("off")
                        .executes(ProfileCommand::turnOff))
                .then(Commands.literal("status")
                        .executes(ProfileCommand::showStatus))
                .then(Commands.literal("clear")
                        .executes(ProfileCommand::clear));
        return builder.build();
    }

    private static int showStatus(CommandContext<CommandSourceStack> ctx) {
        boolean on = TickProfiler.INSTANCE.isEnabled();
        long count = TickProfiler.INSTANCE.getRecordedSpanCount();
        ctx.getSource().sendSuccess(() -> Component.literal(
                String.format("[TickProfiler] status=%s, recordedSpans=%d, target=%s",
                        on ? "ON" : "OFF", count, TickProfiler.INSTANCE.getCsvPath())), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int turnOn(CommandContext<CommandSourceStack> ctx) {
        TickProfiler.INSTANCE.enable();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[TickProfiler] Profiling ENABLED. Output: " + TickProfiler.INSTANCE.getCsvPath()), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int turnOff(CommandContext<CommandSourceStack> ctx) {
        long count = TickProfiler.INSTANCE.getRecordedSpanCount();
        TickProfiler.INSTANCE.disable();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[TickProfiler] Profiling DISABLED. Recorded spans: " + count), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int clear(CommandContext<CommandSourceStack> ctx) {
        TickProfiler.INSTANCE.clear();
        ctx.getSource().sendSuccess(() -> Component.literal("[TickProfiler] Cleared buffer."), false);
        return Command.SINGLE_SUCCESS;
    }
}
