package com.wsteam.wandscape.content.command;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.CommandNode;
import com.wsteam.wandscape.content.element.internal.ElementValueGenerator;
import com.wsteam.wandscape.content.element.internal.ElementValueGenerator.GenerationReport;
import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Re-derives the element mappings for one seed and everything crafted from it,
 * instead of the whole 1100+ file set that {@link GenerateElementMappingsCommand}
 * rewrites.
 *
 * <p>Use it after changing a single line of {@code element_seeds.json}: the seed file is
 * authoritative, so its own value must be edited there first, then this command pushes
 * that value down the vanilla recipe graph (quartz → quartz block/slab/stairs → diorite
 * → granite → ...) and reports exactly which files changed.
 *
 * <p>Unlike the full run, existing files inside the subtree are overwritten on purpose —
 * re-deriving them is the whole point. Files outside the subtree are never touched.
 */
public final class GenerateSeedMappingsCommand {
    private static final String TAG = "GenerateSeedMappingsCommand";
    private static final String SEEDS_RESOURCE = "data/wandscape/element_seeds.json";

    private GenerateSeedMappingsCommand() {}

    public static CommandNode<CommandSourceStack> node() {
        return Commands.literal("generate_seed_mappings")
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("seed", StringArgumentType.string())
                        .executes(ctx -> execute(ctx, false))
                        .then(Commands.literal("--dry-run")
                                .executes(ctx -> execute(ctx, true))))
                .build();
    }

    private static int execute(CommandContext<CommandSourceStack> ctx, boolean dryRun) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getServer().overworld();
        if (level == null) {
            src.sendFailure(Component.literal("[Wandscape] No overworld available"));
            return 0;
        }

        String seedId = normalize(StringArgumentType.getString(ctx, "seed"));

        String seedJson;
        try {
            ClassLoader cl = GenerateSeedMappingsCommand.class.getClassLoader();
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

        // Fail early on a typo instead of silently writing nothing: seeds are the roots
        // of the derivation graph, so a non-seed id has no authoritative value to push.
        if (!isSeed(seedJson, seedId)) {
            src.sendFailure(Component.literal("[Wandscape] " + seedId
                    + " is not in " + SEEDS_RESOURCE
                    + " — add or fix its entry there first (a seed's own value is never derived)."));
            return 0;
        }

        // Dev-only command: writes straight into src/main/resources so the files land in
        // the working tree. Same convention as GenerateElementMappingsCommand.
        Path gameDir = src.getServer().getServerDirectory().toAbsolutePath();
        Path srcDir = gameDir.resolve("..").resolve("src").resolve("main").resolve("resources").normalize();
        Path outputDir = srcDir.resolve("data").resolve("wandscape").resolve("element_mappings");

        src.sendSystemMessage(Component.literal("[Wandscape] Deriving element mappings under " + seedId
                + (dryRun ? " (dry-run)" : "") + "..."));

        try {
            ElementValueGenerator gen = new ElementValueGenerator(level, dryRun, false, outputDir);
            GenerationReport report = gen.run(outputDir, seedJson, seedId);

            List<String> lines = new ArrayList<>();
            lines.add("=== Rooted Element Mapping Generation ===");
            lines.add("  seed: " + seedId + "  " + seedValuesOf(seedJson, seedId));
            lines.add("  subtree: " + report.subtreeSize() + " item(s) derived from this seed");
            lines.add("  recipes analyzed: " + report.recipesProcessed());
            lines.add("  iterations: " + report.iterationsRequired()
                    + (report.iterationsRequired() >= 50 ? " (MAX — possible cycle)" : " (converged)"));
            lines.add("  files written: " + report.filesWritten()
                    + (dryRun ? " (dry-run, no files written)" : ""));
            lines.add("  values changed: " + report.changeSummaries().size());

            if (report.changeSummaries().isEmpty()) {
                lines.add("  (nothing changed — did you rebuild after editing element_seeds.json?"
                        + " the command reads seeds from the classpath, not from src/)");
            } else {
                for (String summary : report.changeSummaries()) {
                    lines.add("    " + summary);
                }
            }

            String msg = String.join("\n", lines);
            Log.info(TAG, "{}", msg);
            src.sendSuccess(() -> Component.literal(msg), false);

        } catch (Exception e) {
            Log.error(TAG, "Rooted generation failed", e);
            src.sendFailure(Component.literal("[Wandscape] Rooted generation failed: " + e.getMessage()));
            return 0;
        }

        return Command.SINGLE_SUCCESS;
    }

    /** Accepts both "quartz" and "minecraft:quartz"; anything else is left as typed. */
    private static String normalize(String id) {
        return id.indexOf(':') >= 0 ? id : "minecraft:" + id;
    }

    private static boolean isSeed(String seedJson, String id) {
        return seedValuesOf(seedJson, id) != null;
    }

    /** "fire 8/wind 8" for the seed, or null when the id is not a seed. */
    private static String seedValuesOf(String seedJson, String id) {
        try {
            JsonObject root = JsonParser.parseString(seedJson).getAsJsonObject();
            for (JsonElement elem : root.getAsJsonArray("seeds")) {
                JsonObject obj = elem.getAsJsonObject();
                if (!obj.has("item") || !id.equals(obj.get("item").getAsString())) continue;
                if (!obj.has("values")) return "(no values)";
                List<String> parts = new ArrayList<>();
                for (var entry : obj.getAsJsonObject("values").entrySet()) {
                    parts.add(entry.getKey() + " " + entry.getValue().getAsString());
                }
                return String.join("/", parts);
            }
        } catch (RuntimeException e) {
            Log.warn(TAG, "Could not read seed values for {}: {}", id, e.getMessage());
        }
        return null;
    }
}
