package com.wsteam.wandscape.content.colony.exploration;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wsteam.wandscape.content.element.data.ElementType;
import com.wsteam.wandscape.content.element.internal.ElementMaps;
import com.wsteam.wandscape.foundation.log.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reward payout rule of an exploration region, parsed from the {@code reward} block of a
 * region JSON. Pure data: no Minecraft imports.
 *
 * <p>The spec only decides where the <b>element value vector</b> comes from — the three
 * parameters ({@code expRatio}, {@code dangerMultiplier}, {@code variance}) shape that
 * vector into EXP and min/max ranges for every mode alike, so a region can switch value
 * source without touching its tuning.
 *
 * <pre>
 * "reward": {
 *   "mode": "derived",                 // value 从哪来
 *   "value": { "dark": 200 },          // fixed 直接用；additive 加在派生值上
 *   "exp_ratio": 15.0,                 // 元素 → 殖民经验的换算比
 *   "danger_multiplier": 2.5,          // 只放大经验，不放大元素
 *   "variance": 0.25                   // 上下限 ±%
 * }
 * </pre>
 */
public record ExplorationRewardSpec(
        Mode mode,
        Map<ElementType, Long> value,
        double expRatio,
        double dangerMultiplier,
        double variance
) {
    /** Where the element value vector comes from. */
    public enum Mode {
        /** Sample the loot table and average it — the value is never written down. */
        DERIVED,
        /** Sampled value plus the declared {@code value}; a floor on top of the loot. */
        ADDITIVE,
        /** The declared {@code value} only; the loot table is not sampled at all. */
        FIXED;

        static Mode parse(String raw) {
            if (raw == null) return DERIVED;
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return DERIVED;
            }
        }
    }

    private static final String TAG = "ExplorationRewardSpec";

    public static final double DEFAULT_EXP_RATIO = 15.0;
    public static final double DEFAULT_DANGER = 1.0;
    public static final double DEFAULT_VARIANCE = 0.25;

    /** Rule applied to a region that declares no {@code reward} block: sample, no extras. */
    public static final ExplorationRewardSpec DEFAULT =
            new ExplorationRewardSpec(Mode.DERIVED, Map.of(), DEFAULT_EXP_RATIO, DEFAULT_DANGER, DEFAULT_VARIANCE);

    /** Tuning keys that sat at the top level before the {@code reward} block existed. */
    private static final List<String> LEGACY_KEYS =
            List.of("danger_multiplier", "variance", "element_to_exp_ratio");

    public ExplorationRewardSpec {
        value = Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }

    /**
     * Parse the {@code reward} block. A missing block yields {@link #DEFAULT}; a declared
     * {@code value} on a {@code derived} region is dropped with a warning rather than
     * silently ignored, because "wrote a value but nothing happened" is the expensive
     * kind of quiet.
     */
    public static ExplorationRewardSpec fromJson(String regionId, JsonObject region) {
        if (!region.has("reward") || !region.get("reward").isJsonObject()) {
            warnIfLegacyShape(regionId, region);
            return DEFAULT;
        }
        JsonObject reward = region.getAsJsonObject("reward");

        Mode mode = Mode.parse(reward.has("mode") ? reward.get("mode").getAsString() : null);
        Map<ElementType, Long> value = ElementMaps.parse(reward, "value");
        double expRatio = readDouble(reward, "exp_ratio", DEFAULT_EXP_RATIO);
        double danger = readDouble(reward, "danger_multiplier", DEFAULT_DANGER);
        double variance = readDouble(reward, "variance", DEFAULT_VARIANCE);

        if (mode == Mode.DERIVED && !value.isEmpty()) {
            Log.warn(TAG, "Region '{}' declares reward.value but mode is 'derived' — the value is ignored. "
                    + "Use \"mode\": \"fixed\" or \"additive\" to make it count.", regionId);
            value = Map.of();
        }

        return new ExplorationRewardSpec(mode, value, expRatio, danger, variance);
    }

    private static double readDouble(JsonObject obj, String key, double fallback) {
        if (!obj.has(key)) return fallback;
        JsonElement e = obj.get(key);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            Log.warn(TAG, "reward.{} is not a number, falling back to {}", key, fallback);
            return fallback;
        }
        return e.getAsDouble();
    }

    /**
     * Warn when a region still carries the pre-{@code reward} shape. Those keys are gone for
     * real — no silent aliasing — but a file written against the old shape would otherwise
     * just quietly lose its tuning and fall back to the defaults.
     */
    private static void warnIfLegacyShape(String regionId, JsonObject region) {
        List<String> present = new ArrayList<>();
        for (String key : LEGACY_KEYS) {
            if (region.has(key)) present.add(key);
        }
        if (!present.isEmpty()) {
            Log.warn(TAG, "Region '{}' has top-level {} — those moved into the \"reward\" block and are being "
                    + "ignored. Move them under \"reward\" to keep the tuning.", regionId, present);
        }
    }

    /** True when the declared value should be added on top of the sampled one. */
    public boolean addsToDerived() {
        return mode == Mode.ADDITIVE;
    }

    /** True when the loot table never needs sampling for this region. */
    public boolean skipsSampling() {
        return mode == Mode.FIXED;
    }
}
