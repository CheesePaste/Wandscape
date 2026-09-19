package com.wsteam.wandscape.content.colony.exploration;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Configuration definition for an exploration region loaded from JSON.
 *
 * <p>Split in two: the top level says <b>which region this is</b> ({@code name} plus the
 * loot-table patterns that select it), the {@code reward} block says <b>what it pays out</b>
 * (see {@link ExplorationRewardSpec}).
 *
 * <pre>
 * {
 *   "name": "远古之城",
 *   "loot_table_patterns": [".*chests/ancient_city.*"],
 *   "reward": { "mode": "fixed", "value": { "dark": 200 }, "exp_ratio": 15.0, ... }
 * }
 * </pre>
 */
public record ExplorationRegionConfig(
        String id,
        String name,
        ExplorationRewardSpec reward,
        List<String> lootTablePatterns,
        List<Pattern> compiledPatterns
) {
    /** Display name used when a loot table id carries no readable structure name at all. */
    public static final String FALLBACK_NAME = "荒野遗迹";

    /** Path segments that only say "this is a container table" and carry no structure name. */
    private static final Set<String> PATH_NOISE = Set.of("chest", "chests", "loot", "loot_table", "loot_tables");

    public static ExplorationRegionConfig fromJson(String id, JsonElement json) {
        if (json == null || !json.isJsonObject()) {
            return new ExplorationRegionConfig(id, id, ExplorationRewardSpec.DEFAULT, List.of(), List.of());
        }
        JsonObject obj = json.getAsJsonObject();
        String name = obj.has("name") ? obj.get("name").getAsString() : id;
        ExplorationRewardSpec reward = ExplorationRewardSpec.fromJson(id, obj);

        List<String> rawPatterns = new ArrayList<>();
        List<Pattern> compiled = new ArrayList<>();
        if (obj.has("loot_table_patterns") && obj.get("loot_table_patterns").isJsonArray()) {
            JsonArray arr = obj.getAsJsonArray("loot_table_patterns");
            for (JsonElement e : arr) {
                if (e.isJsonPrimitive()) {
                    String p = e.getAsString();
                    rawPatterns.add(p);
                    try {
                        compiled.add(Pattern.compile(p));
                    } catch (Exception ex) {
                        compiled.add(Pattern.compile(Pattern.quote(p)));
                    }
                }
            }
        }
        return new ExplorationRegionConfig(
                id, name, reward,
                Collections.unmodifiableList(rawPatterns),
                Collections.unmodifiableList(compiled)
        );
    }

    /**
     * How specific this region's best matching pattern is for the given loot table id,
     * or -1 when no pattern matches. More literal characters means more specific, so a
     * catch-all pattern such as {@code ".*"} only wins once no concrete region fits —
     * the outcome does not depend on registry iteration order.
     */
    public int matchSpecificity(String lootTableId) {
        if (lootTableId == null) return -1;
        int best = -1;
        for (Pattern p : compiledPatterns) {
            if (p.matcher(lootTableId).matches() || p.matcher(lootTableId).find()) {
                best = Math.max(best, literalLength(p.pattern()));
            }
        }
        return best;
    }

    /** Count the literal characters of a regex, ignoring anchors and {@code .*} wildcards. */
    private static int literalLength(String pattern) {
        return pattern.replace(".*", "").replace(".", "").replace("^", "")
                .replace("$", "").replace("\\", "").length();
    }

    /**
     * Human-readable name derived from a loot table id, used when no region config matches.
     * Container-only path segments are dropped so only the structure name remains, e.g.
     * {@code "somemod:chests/dragon_den"} becomes {@code "Dragon Den"}.
     * Falls back to the namespace, then to {@link #FALLBACK_NAME}.
     */
    public static String deriveDisplayName(String lootTableId) {
        if (lootTableId == null || lootTableId.isBlank()) return FALLBACK_NAME;

        String namespace = "";
        String path = lootTableId;
        int colon = lootTableId.indexOf(':');
        if (colon >= 0) {
            namespace = lootTableId.substring(0, colon);
            path = lootTableId.substring(colon + 1);
        }

        List<String> words = new ArrayList<>();
        for (String segment : path.split("/")) {
            if (segment.isBlank() || PATH_NOISE.contains(segment.toLowerCase(Locale.ROOT))) continue;
            for (String word : segment.split("_")) {
                if (word.isBlank()) continue;
                words.add(Character.toUpperCase(word.charAt(0)) + word.substring(1));
            }
        }

        if (!words.isEmpty()) return String.join(" ", words);
        return namespace.isBlank() ? FALLBACK_NAME : namespace;
    }
}
