package com.wsteam.wandscape.content.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import com.wsteam.wandscape.foundation.log.LogFilter;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.Set;
/**
 * Debug command: runtime log tag whitelist filter.
 *
 * <p>When the filter is ON, only tags in the whitelist produce log output.
 * warn/error always pass through regardless of filter state.
 *
 * <p>Usage:
 * <pre>
 *   /wandscape logfilter on                          — enable filter (empty whitelist = block all)
 *   /wandscape logfilter off                         — disable filter (all logs pass)
 *   /wandscape logfilter add Scheduler                — whitelist one tag
 *   /wandscape logfilter add Scheduler Preview Bar    — whitelist multiple tags
 *   /wandscape logfilter remove Scheduler             — remove tag
 *   /wandscape logfilter clear                       — clear whitelist
 *   /wandscape logfilter list                        — show current whitelist
 *   /wandscape logfilter preview                     — preset for preview debugging
 * </pre>
 */
public final class LogFilterCommand {

    private LogFilterCommand() {}

    public static CommandNode<CommandSourceStack> node() {
        var builder = Commands.literal("logfilter")
                .executes(LogFilterCommand::showStatus)
                .then(Commands.literal("on")
                        .executes(LogFilterCommand::turnOn))
                .then(Commands.literal("off")
                        .executes(LogFilterCommand::turnOff))
                .then(Commands.literal("add")
                        .then(Commands.argument("tag", StringArgumentType.greedyString())
                                .executes(LogFilterCommand::addTag)))
                .then(Commands.literal("remove")
                        .then(Commands.argument("tag", StringArgumentType.string())
                                .executes(LogFilterCommand::removeTag)))
                .then(Commands.literal("clear")
                        .executes(LogFilterCommand::clear))
                .then(Commands.literal("list")
                        .executes(LogFilterCommand::list))
                .then(Commands.literal("preview")
                        .executes(LogFilterCommand::presetPreview));
        return builder.build();
    }

    private static int showStatus(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        boolean on = LogFilter.isEnabled();
        int count = LogFilter.size();
        src.sendSuccess(() -> Component.literal(
                "[LogFilter] filter=" + (on ? "ON" : "OFF")
                        + " whitelist=" + count + " tags"),
                false);
        if (on && count > 0) {
            src.sendSuccess(() -> Component.literal("[LogFilter] Whitelisted tags:"), false);
            for (String tag : LogFilter.getWhitelist()) {
                src.sendSuccess(() -> Component.literal("  " + tag), false);
            }
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int turnOn(CommandContext<CommandSourceStack> ctx) {
        LogFilter.setEnabled(true);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[LogFilter] ON — " + LogFilter.size() + " tags whitelisted"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int turnOff(CommandContext<CommandSourceStack> ctx) {
        LogFilter.setEnabled(false);
        ctx.getSource().sendSuccess(() ->
                Component.literal("[LogFilter] OFF — all logs pass"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int addTag(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String raw = StringArgumentType.getString(ctx, "tag");
        String[] tags = raw.split("\\s+");
        int before = LogFilter.size();
        for (String t : tags) {
            if (!t.isEmpty()) {
                LogFilter.add(t);
            }
        }
        int added = LogFilter.size() - before;
        srcFeedback(ctx, "[LogFilter] Added " + added + " tag(s) — whitelist=" + LogFilter.size());
        return Command.SINGLE_SUCCESS;
    }

    private static int removeTag(CommandContext<CommandSourceStack> ctx) {
        String tag = StringArgumentType.getString(ctx, "tag");
        LogFilter.remove(tag);
        srcFeedback(ctx, "[LogFilter] Removed '" + tag + "' — whitelist=" + LogFilter.size());
        return Command.SINGLE_SUCCESS;
    }

    private static int clear(CommandContext<CommandSourceStack> ctx) {
        LogFilter.clear();
        ctx.getSource().sendSuccess(() ->
                Component.literal("[LogFilter] Whitelist cleared"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        Set<String> wl = LogFilter.getWhitelist();
        if (wl.isEmpty()) {
            ctx.getSource().sendSuccess(() ->
                    Component.literal("[LogFilter] Whitelist is empty"), false);
        } else {
            ctx.getSource().sendSuccess(() ->
                    Component.literal("[LogFilter] Whitelist (" + wl.size() + " tags):"), false);
            for (String tag : wl) {
                ctx.getSource().sendSuccess(() -> Component.literal("  " + tag), false);
            }
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int presetPreview(CommandContext<CommandSourceStack> ctx) {
        LogFilter.presetPreviewDebug();
        ctx.getSource().sendSuccess(() ->
                Component.literal("[LogFilter] Preview preset applied: "
                        + LogFilter.size() + " tags"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static void srcFeedback(CommandContext<CommandSourceStack> ctx, String msg) {
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
    }
}
