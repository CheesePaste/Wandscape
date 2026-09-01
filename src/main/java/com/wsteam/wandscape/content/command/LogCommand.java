package com.wsteam.wandscape.content.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.CommandNode;
import com.wsteam.wandscape.foundation.log.LogCategory;
import com.wsteam.wandscape.foundation.log.LogConfig;
import com.wsteam.wandscape.foundation.log.LogLevel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Command for runtime inspection and fine-grained level configuration of Wandscape logging.
 *
 * <p>Usage:
 * <pre>
 *   /wandscape log status                        - show levels for all categories
 *   /wandscape log level task DEBUG              - set task domain log level to DEBUG
 *   /wandscape log level all INFO                - set all domains to INFO
 *   /wandscape log reset                         - reset all levels to default INFO
 *   /wandscape log reload                        - reload from config file
 *   /wandscape log save                          - write current settings to config file
 * </pre>
 */
public final class LogCommand {

    private LogCommand() {}

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_CATEGORIES = (ctx, builder) -> {
        List<String> suggestions = new ArrayList<>();
        suggestions.add("all");
        for (LogCategory cat : LogCategory.values()) {
            suggestions.add(cat.getId());
        }
        return SharedSuggestionProvider.suggest(suggestions, builder);
    };

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_LEVELS = (ctx, builder) ->
            SharedSuggestionProvider.suggest(
                    Arrays.stream(LogLevel.values()).map(Enum::name).toList(),
                    builder
            );

    public static CommandNode<CommandSourceStack> node() {
        return Commands.literal("log")
                .executes(LogCommand::showStatus)
                .then(Commands.literal("status")
                        .executes(LogCommand::showStatus))
                .then(Commands.literal("reset")
                        .executes(LogCommand::resetDefaults))
                .then(Commands.literal("reload")
                        .executes(LogCommand::reloadConfig))
                .then(Commands.literal("save")
                        .executes(LogCommand::saveConfig))
                .then(Commands.literal("level")
                        .then(Commands.argument("target", StringArgumentType.word())
                                .suggests(SUGGEST_CATEGORIES)
                                .then(Commands.argument("level", StringArgumentType.word())
                                        .suggests(SUGGEST_LEVELS)
                                        .executes(LogCommand::setLevel))))
                .then(LogFilterCommand.node())
                .build();
    }

    private static int showStatus(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        src.sendSuccess(() -> Component.literal(
                "[LogConfig] Root level: " + LogConfig.getRootLevel().name()), false);

        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (LogCategory cat : LogCategory.values()) {
            LogLevel lvl = LogConfig.getLevel(cat);
            sb.append(cat.getId()).append(": ").append(lvl.name()).append("  ");
            count++;
            if (count % 4 == 0) {
                String line = sb.toString();
                src.sendSuccess(() -> Component.literal("  " + line), false);
                sb.setLength(0);
            }
        }
        if (sb.length() > 0) {
            String line = sb.toString();
            src.sendSuccess(() -> Component.literal("  " + line), false);
        }

        return Command.SINGLE_SUCCESS;
    }

    private static int setLevel(CommandContext<CommandSourceStack> ctx) {
        String target = StringArgumentType.getString(ctx, "target");
        String levelStr = StringArgumentType.getString(ctx, "level");
        LogLevel level = LogLevel.fromString(levelStr, null);

        if (level == null) {
            ctx.getSource().sendFailure(Component.literal("Unknown log level: " + levelStr
                    + " (Valid: DEBUG, INFO, WARN, ERROR, OFF)"));
            return 0;
        }

        if ("all".equalsIgnoreCase(target)) {
            LogConfig.setAllLevels(level);
            LogConfig.save();
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "[LogConfig] Set ALL category levels to " + level.name() + " (saved)"), false);
            return Command.SINGLE_SUCCESS;
        }

        LogCategory cat = null;
        for (LogCategory c : LogCategory.values()) {
            if (c.getId().equalsIgnoreCase(target)) {
                cat = c;
                break;
            }
        }

        if (cat == null) {
            ctx.getSource().sendFailure(Component.literal("Unknown log category: " + target));
            return 0;
        }

        LogConfig.setLevel(cat, level);
        LogConfig.save();
        LogCategory finalCat = cat;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[LogConfig] Category '" + finalCat.getId() + "' set to " + level.name() + " (saved)"), false);

        return Command.SINGLE_SUCCESS;
    }

    private static int resetDefaults(CommandContext<CommandSourceStack> ctx) {
        LogConfig.resetToDefaults();
        LogConfig.save();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[LogConfig] Reset all category levels to INFO (saved)"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int reloadConfig(CommandContext<CommandSourceStack> ctx) {
        LogConfig.load();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[LogConfig] Reloaded configuration from " + LogConfig.getConfigFilePath()), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int saveConfig(CommandContext<CommandSourceStack> ctx) {
        LogConfig.save();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[LogConfig] Saved configuration to " + LogConfig.getConfigFilePath()), false);
        return Command.SINGLE_SUCCESS;
    }
}
