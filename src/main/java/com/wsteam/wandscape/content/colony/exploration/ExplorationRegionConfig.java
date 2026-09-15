package com.wsteam.wandscape.content.colony.exploration;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Configuration definition for an exploration region loaded from JSON.
 * Defines danger multiplier, variance spread, element-to-EXP ratio, and loot table regex patterns.
 */
public record ExplorationRegionConfig(
        String id,
        String name,
        double dangerMultiplier,
        double variance,
        double elementToExpRatio,
        List<String> lootTablePatterns,
        List<Pattern> compiledPatterns
) {
    public static ExplorationRegionConfig fromJson(String id, JsonElement json) {
        if (json == null || !json.isJsonObject()) {
            return new ExplorationRegionConfig(id, id, 1.0, 0.25, 15.0, List.of(), List.of());
        }
        JsonObject obj = json.getAsJsonObject();
        String name = obj.has("name") ? obj.get("name").getAsString() : id;
        double dangerMultiplier = obj.has("danger_multiplier") ? obj.get("danger_multiplier").getAsDouble() : 1.0;
        double variance = obj.has("variance") ? obj.get("variance").getAsDouble() : 0.25;
        double elementToExpRatio = obj.has("element_to_exp_ratio") ? obj.get("element_to_exp_ratio").getAsDouble() : 15.0;

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
                id, name, dangerMultiplier, variance, elementToExpRatio,
                Collections.unmodifiableList(rawPatterns),
                Collections.unmodifiableList(compiled)
        );
    }

    /** Check if this region matches the given loot table ResourceLocation string. */
    public boolean matches(String lootTableId) {
        if (lootTableId == null) return false;
        for (Pattern p : compiledPatterns) {
            if (p.matcher(lootTableId).matches() || p.matcher(lootTableId).find()) {
                return true;
            }
        }
        return false;
    }
}
