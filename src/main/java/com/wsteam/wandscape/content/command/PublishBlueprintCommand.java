package com.wsteam.wandscape.content.command;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.wsteam.wandscape.impl.WandscapeEngine;
import com.wsteam.wandscape.content.building.source.BlueprintConfigLoader;
import com.wsteam.wandscape.content.task.engine.pool.TaskRequest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
/**
 * Publish a blueprint as a global task.
 *
 * <p>Usage: {@code /wandscape publish <blueprint_id> [key=value ...] [priority]}
 *
 * <p>Examples:
 * <pre>{@code
 * /wandscape publish demo:tnt_platform anchor=[127,-61,11]
 * /wandscape publish demo:tnt_platform anchor=[10,64,0] 20
 * }</pre>
 */
public final class PublishBlueprintCommand {

    private PublishBlueprintCommand() {}

    /** Build the "publish" sub-command node. Attach to a parent {@code /wandscape} literal. */
    public static LiteralArgumentBuilder<CommandSourceStack> buildNode() {
        return Commands.literal("publish")
                .then(Commands.argument("rest", StringArgumentType.greedyString())
                        .executes(PublishBlueprintCommand::execute));
    }

    /** Suggest known blueprint IDs (called when user types the first word of the greedy arg). */
    public static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestBlueprints(
            CommandContext<CommandSourceStack> ctx,
            com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        BlueprintConfigLoader loader = WandscapeEngine.getBlueprintConfigLoader();
        if (loader != null) {
            for (String id : loader.getAll().keySet()) {
                builder.suggest(id);
            }
        }
        return builder.buildFuture();
    }

    private static int execute(CommandContext<CommandSourceStack> ctx) {
        String rest = StringArgumentType.getString(ctx, "rest");
        CommandSourceStack src = ctx.getSource();

        // Split on whitespace
        String[] tokens = rest.split("\\s+");
        if (tokens.length == 0) {
            src.sendFailure(Component.literal(
                    "[Wandscape] Usage: /wandscape publish <blueprint_id> [key=value ...]"));
            return 0;
        }

        String blueprintId = tokens[0];

        // Parse remaining tokens: key=value pairs or bare priority number
        Map<String, JsonElement> params = new LinkedHashMap<>();
        int priority = 10;

        int i = 1;
        while (i < tokens.length) {
            String token = tokens[i];
            int eq = token.indexOf('=');

            if (eq > 0) {
                // Normal: key=value
                String key = token.substring(0, eq);
                String rawValue = token.substring(eq + 1);
                params.put(key, parseValue(rawValue));
                i++;
            } else if (eq == 0 && !params.isEmpty()) {
                // "=value" — attach to last param (unlikely but handle)
                // Walk back to find the last key
                List<String> keys = List.copyOf(params.keySet());
                String lastKey = keys.get(keys.size() - 1);
                String rawValue = token.substring(1);
                params.put(lastKey, parseValue(rawValue));
                i++;
            } else if (token.equals("=")) {
                // "key = value" — '=' is a separate token. Previous token is the key,
                // next token is the value.
                if (i >= 2 && i + 1 < tokens.length) {
                    String key = tokens[i - 1];
                    // Remove the key-only entry (it was added as a string maybe, or we
                    // need to re-process). Actually we haven't added it yet — the parser
                    // would have errored on the bare key at i-1 position.
                    // Let's extract the key and value from surrounding tokens.
                    String rawValue = tokens[i + 1];
                    params.put(key, parseValue(rawValue));
                    i += 2;
                } else {
                    src.sendFailure(Component.literal(
                            "[Wandscape] Malformed '=': expected 'key = value'"));
                    return 0;
                }
            } else {
                // No '=' in token
                if (i == tokens.length - 1) {
                    // Last token — try as priority
                    try {
                        priority = Integer.parseInt(token);
                    } catch (NumberFormatException e) {
                        src.sendFailure(Component.literal(
                                "[Wandscape] Unrecognized param (expected key=value): " + token));
                        return 0;
                    }
                    i++;
                } else {
                    // Check if next token is '='
                    if (i + 1 < tokens.length && tokens[i + 1].equals("=")) {
                        // "key = value" — handled in the next iteration
                        // But we need to remember this key
                        String key = token;
                        i++; // skip the '='
                        i++; // skip to value
                        if (i < tokens.length) {
                            String rawValue = tokens[i];
                            params.put(key, parseValue(rawValue));
                            i++;
                        } else {
                            src.sendFailure(Component.literal(
                                    "[Wandscape] Missing value after 'key ='"));
                            return 0;
                        }
                    } else {
                        src.sendFailure(Component.literal(
                                "[Wandscape] Unrecognized param (expected key=value): " + token));
                        return 0;
                    }
                }
            }
        }

        // Submit
        var world = WandscapeEngine.getWorld();
        if (world == null || world.taskPool == null) {
            src.sendFailure(Component.literal("[Wandscape] Engine not bootstrapped."));
            return 0;
        }

        try {
            long taskId = world.taskPool.addTask(
                    new TaskRequest(blueprintId, params, priority,
                            com.wsteam.wandscape.api.WandscapeApis.colonyAt(
                                    src.getPlayer() != null ? src.getPlayer().blockPosition() : null)));
            final int finalPriority = priority;
            final int paramCount = params.size();
            src.sendSuccess(() -> Component.literal(
                    "[Wandscape] Published '" + blueprintId
                    + "' → task #" + taskId
                    + " (priority=" + finalPriority + ", params=" + paramCount + ")"),
                    true);
            return Command.SINGLE_SUCCESS;
        } catch (Exception e) {
            src.sendFailure(Component.literal(
                    "[Wandscape] Failed: " + e.getMessage()));
            return 0;
        }
    }

    // ── Value parser ──

    static JsonElement parseValue(String raw) {
        if (raw.startsWith("[[")) return parsePosList(raw);
        if (raw.startsWith("[")) return parsePos(raw);
        if (raw.startsWith("{")) return new JsonPrimitive(raw); // pass-through for Gson
        try { return new JsonPrimitive(Integer.parseInt(raw)); }
        catch (NumberFormatException ignored) {}
        return new JsonPrimitive(raw);
    }

    private static JsonElement parsePos(String raw) {
        String inner = raw.substring(1, raw.length() - 1);
        String[] parts = inner.split(",");
        if (parts.length == 3) {
            JsonArray arr = new JsonArray();
            for (String p : parts) arr.add(Integer.parseInt(p.trim()));
            return arr;
        }
        return new JsonPrimitive(raw);
    }

    private static JsonElement parsePosList(String raw) {
        String inner = raw.substring(1, raw.length() - 1);
        JsonArray result = new JsonArray();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (char c : inner.toCharArray()) {
            if (c == '[') { depth++; if (depth == 1) continue; }
            if (c == ']') {
                depth--;
                if (depth == 0) { result.add(parsePos("[" + current + "]")); current.setLength(0); continue; }
            }
            if (depth > 0) current.append(c);
        }
        return result;
    }
}
