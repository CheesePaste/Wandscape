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
 *   "loot_share": 0.5                  // 元素总额里沿用战利品比例的那一半
 * }
 * </pre>
 *
 * <p>Apart from {@code loot_share}, which shapes how the element total is distributed at roll
 * time, every parameter here shapes the value vector into EXP and min/max ranges.
 */
public record ExplorationRewardSpec(
        Mode mode,
        Map<ElementType, Long> value,
        double expRatio,
        double dangerMultiplier,
        double variance,
        double lootShare
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
    /**
     * Default fraction of the rolled element total that keeps the loot table's distribution;
     * the rest is spread evenly over all seven elements. Vanilla chest loot is heavily
     * metal-based, so a fully loot-derived split starves the other six elements.
     */
    public static final double DEFAULT_LOOT_SHARE = 0.5;

    /** Rule applied to a region that declares no {@code reward} block: price the loot table, no extras. */
    public static final ExplorationRewardSpec DEFAULT = new ExplorationRewardSpec(
            Mode.DERIVED, Map.of(), DEFAULT_EXP_RATIO, DEFAULT_DANGER, DEFAULT_VARIANCE, DEFAULT_LOOT_SHARE);

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
        double lootShare = readShare(regionId, reward, "loot_share", DEFAULT_LOOT_SHARE);

        if (mode == Mode.DERIVED && !value.isEmpty()) {
            Log.warn(TAG, "Region '{}' declares reward.value but mode is 'derived' — the value is ignored. "
                    + "Use \"mode\": \"fixed\" or \"additive\" to make it count.", regionId);
            value = Map.of();
        }

        return new ExplorationRewardSpec(mode, value, expRatio, danger, variance, lootShare);
    }

    /** Read a 0..1 ratio, clamping and warning rather than letting a stray 50 silently mean "all loot". */
    private static double readShare(String regionId, JsonObject obj, String key, double fallback) {
        double raw = readDouble(obj, key, fallback);
        if (raw < 0.0 || raw > 1.0) {
            Log.warn(TAG, "Region '{}' has reward.{} = {} outside [0, 1], clamped to {}",
                    regionId, key, raw, Math.max(0.0, Math.min(1.0, raw)));
            return Math.max(0.0, Math.min(1.0, raw));
        }
        return raw;
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
