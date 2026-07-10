package com.wsteam.wandscape.command;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.CommandNode;
import com.wsteam.wandscape.element.internal.ElementValueGenerator;
import com.wsteam.wandscape.element.internal.ElementValueGenerator.GenerationReport;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import com.wsteam.wandscape.shared.log.Log;

public final class GenerateElementMappingsCommand {
    private static final String TAG = "GenerateElementMappingsCommand";
    private static final String SEEDS_RESOURCE = "data/wandscape/element_seeds.json";

    private GenerateElementMappingsCommand() {}

    public static CommandNode<CommandSourceStack> node() {
        return Commands.literal("generate_element_mappings")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> execute(ctx, false, false))
                .then(Commands.literal("--dry-run")
                        .executes(ctx -> execute(ctx, true, false))
                        .then(Commands.literal("--force")
                                .executes(ctx -> execute(ctx, true, true))))
                .then(Commands.literal("--force")
                        .executes(ctx -> execute(ctx, false, true))
                        .then(Commands.literal("--dry-run")
                                .executes(ctx -> execute(ctx, true, true))))
                .build();
    }

    private static int execute(CommandContext<CommandSourceStack> ctx, boolean dryRun, boolean force) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getServer().overworld();
        if (level == null) {
            src.sendFailure(Component.literal("[Wandscape] No overworld available"));
            return 0;
        }

        // Load seed JSON from classpath
        String seedJson;
        try {
            ClassLoader cl = GenerateElementMappingsCommand.class.getClassLoader();
            InputStream is = cl.getResourceAsStream(SEEDS_RESOURCE);
            if (is == null) {
                src.sendFailure(Component.literal("[Wandscape] Seed file not found: " + SEEDS_RESOURCE));
                return 0;
            }
            seedJson = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            is.close();
        } catch (IOException e) {
            src.sendFailure(Component.literal("[Wandscape] Failed to read seed file: " + e.getMessage()));
            return 0;
        }

        // Write directly to src/main/resources so files are immediately available
        // (this is a dev-only command; in production the bundled resources are read-only)
        Path gameDir = src.getServer().getServerDirectory().toAbsolutePath();
        Path srcDir = gameDir.resolve("..").resolve("src").resolve("main").resolve("resources").normalize();
        Path outputDir = srcDir.resolve("data").resolve("wandscape").resolve("element_mappings");
        Path manualDir = outputDir; // same dir for scanning existing files

        src.sendSystemMessage(Component.literal("[Wandscape] Generating element mappings..."));
        src.sendSystemMessage(Component.literal("  dry-run: " + dryRun + "  force: " + force));

        try {
            ElementValueGenerator gen = new ElementValueGenerator(level, dryRun, force, outputDir);
            GenerationReport report = gen.run(manualDir, seedJson);

            // Build report message
            StringBuilder sb = new StringBuilder();
            sb.append("=== Element Mapping Generation ===\n");
            sb.append("  Seeds loaded: ").append(report.seedsLoaded()).append("\n");
            sb.append("  Recipes analyzed: ").append(report.recipesProcessed()).append("\n");
            sb.append("  Iterations: ").append(report.iterationsRequired())
                    .append(report.iterationsRequired() >= 50 ? " (MAX — possible cycle)" : " (converged)").append("\n");
            sb.append("  Items resolved: ").append(report.itemsResolved()).append("\n");
            sb.append("  Items unresolved: ").append(report.itemsUnresolved()).append("\n");
            sb.append("  Files written: ").append(report.filesWritten())
                    .append(dryRun ? " (dry-run, no files written)" : "").append("\n");
            sb.append("  Skipped (manual exists): ").append(report.filesSkipped()).append("\n");
            if (!report.unresolvedSample().isEmpty()) {
                sb.append("  Unresolved sample:\n");
                for (String id : report.unresolvedSample()) {
                    sb.append("    - ").append(id).append("\n");
                }
            }
            if (!report.rootCauses().isEmpty()) {
                sb.append("  Missing seeds (").append(report.rootCauses().size()).append("):\n");
                int shown = 0;
                for (var entry : report.rootCauses().entrySet()) {
                    if (shown++ >= 10) {
                        sb.append("    ... and ").append(report.rootCauses().size() - 10).append(" more (see missing_seeds.txt)\n");
                        break;
                    }
                    sb.append("    ").append(entry.getKey())
                      .append(" ← blocks ").append(entry.getValue().size()).append(" item(s)\n");
                }
            }

            String msg = sb.toString();
            Log.info(TAG, "[Wandscape]\n{}", msg);
            src.sendSuccess(() -> Component.literal(msg.trim()), true);

        } catch (Exception e) {
            Log.error(TAG, "[Wandscape] Generation failed", e);
            src.sendFailure(Component.literal("[Wandscape] Generation failed: " + e.getMessage()));
            return 0;
        }

        return Command.SINGLE_SUCCESS;
    }
}
