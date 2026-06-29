package com.wsteam.wandscape.tourist.internal;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.shared.data.Emotion;
import com.wsteam.wandscape.shared.log.Log;

import javax.annotation.Nullable;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Two-level template resolution for tourist narrative generation.
 *
 * <h3>Resolution order</h3>
 * <ol>
 *   <li>Building-specific: {@code data/wandscape/narratives/buildings/<buildingId>.json}</li>
 *   <li>Global category fallback: {@code data/wandscape/narratives/<locale>.json} → category_templates</li>
 *   <li>Hardcoded fallback in Java — guaranteed never to return null or crash</li>
 * </ol>
 *
 * <p>Templates use {@code {name}}, {@code {building}}, {@code {item}}, {@code {emotion_adj}}
 * as placeholder variables.
 */
public final class NarrativeTemplates {

    private static final String TAG = "NarrativeTemplates";
    private static final Gson GSON = new Gson();
    private static final String LOCALE = "zh_cn";
    private static final String BASE_PATH = "data/wandscape/narratives/";
    private static final Random RNG = new Random();

    // ── Hardcoded fallbacks (never crash) ──

    private static final String FALLBACK_VISIT = "{name} 访问了 {building}";
    private static final String FALLBACK_ARRIVAL_MORNING = "{name} 来到了殖民地";
    private static final String FALLBACK_ARRIVAL_AFTERNOON = "{name} 来到了殖民地";
    private static final String FALLBACK_ARRIVAL_NIGHT = "{name} 来到了殖民地";
    private static final String FALLBACK_DEPARTURE = "{name} 离开了殖民地";
    private static final String FALLBACK_HOTEL_CHECKIN = "✨ {name} 入住了 {building}";
    private static final String FALLBACK_HOTEL_WAKEUP = "{name} 在 {building} 醒来";
    private static final String FALLBACK_SATISFACTION_50 = "{name} 的满意度达到 50%";
    private static final String FALLBACK_SATISFACTION_70 = "{name} 的满意度达到 70%";
    private static final String FALLBACK_SATISFACTION_100 = "{name} 的满意度达到 100%！";

    // ── Loaded templates ──

    /** category → (eventType → template list). e.g. "shop" → ("visit" → [...]) */
    private final Map<String, Map<String, List<String>>> categoryTemplates = new ConcurrentHashMap<>();
    /** buildingTypeId → (eventType → template list). e.g. "tavern" → ("visit" → [...]) */
    private final Map<String, Map<String, List<String>>> buildingTemplates = new ConcurrentHashMap<>();
    /** eventType → template list. e.g. "departure_delighted" → [...] */
    private final Map<String, List<String>> genericTemplates = new ConcurrentHashMap<>();
    /** Emotion → adjective list. e.g. DELIGHTED → ["欣喜若狂", ...] */
    private final Map<String, List<String>> emotionAdjectives = new ConcurrentHashMap<>();

    private volatile boolean loaded;

    // ── Singleton ──

    private static final NarrativeTemplates INSTANCE = new NarrativeTemplates();

    private NarrativeTemplates() {}

    public static NarrativeTemplates getInstance() {
        if (!INSTANCE.loaded) {
            INSTANCE.loadAll();
        }
        return INSTANCE;
    }

    /**
     * Force reload all templates on /reload.
     */
    public static void reload() {
        INSTANCE.loaded = false;
        INSTANCE.categoryTemplates.clear();
        INSTANCE.buildingTemplates.clear();
        INSTANCE.genericTemplates.clear();
        INSTANCE.emotionAdjectives.clear();
        INSTANCE.loadAll();
    }

    // ── Loading ──

    private synchronized void loadAll() {
        if (loaded) return;
        loadGlobal();
        loaded = true;
    }

    private void loadGlobal() {
        String path = BASE_PATH + LOCALE + ".json";
        try (Reader reader = openResource(path)) {
            if (reader == null) {
                Log.warn(TAG, "Global narrative template not found: {}", path);
                return;
            }
            JsonObject root = GSON.fromJson(reader, JsonObject.class);

            // Parse category_templates
            if (root.has("category_templates")) {
                JsonObject cats = root.getAsJsonObject("category_templates");
                for (var catEntry : cats.entrySet()) {
                    String category = catEntry.getKey(); // "shop", "service", "hotel"
                    JsonObject eventMap = catEntry.getValue().getAsJsonObject();
                    Map<String, List<String>> catMap = new HashMap<>();
                    for (var evEntry : eventMap.entrySet()) {
                        catMap.put(evEntry.getKey(), parseStringList(evEntry.getValue()));
                    }
                    categoryTemplates.put(category, catMap);
                }
            }

            // Parse generic templates
            if (root.has("generic")) {
                JsonObject gen = root.getAsJsonObject("generic");
                for (var genEntry : gen.entrySet()) {
                    genericTemplates.put(genEntry.getKey(), parseStringList(genEntry.getValue()));
                }
            }

            // Parse emotion adjectives
            if (root.has("emotion_adjectives")) {
                JsonObject emo = root.getAsJsonObject("emotion_adjectives");
                for (var emoEntry : emo.entrySet()) {
                    emotionAdjectives.put(emoEntry.getKey(), parseStringList(emoEntry.getValue()));
                }
            }

            Log.info(TAG, "Loaded global narratives: {} categories, {} generic, {} emotion pools",
                    categoryTemplates.size(), genericTemplates.size(), emotionAdjectives.size());
        } catch (Exception e) {
            Log.warn(TAG, "Failed to load global narrative template {}: {}", path, e.getMessage());
        }
    }

    /** Load a building-specific template file lazily and cache it. */
    private synchronized void loadBuilding(String buildingTypeId) {
        if (buildingTemplates.containsKey(buildingTypeId)) return;

        String path = BASE_PATH + "buildings/" + buildingTypeId + ".json";
        try (Reader reader = openResource(path)) {
            if (reader == null) {
                buildingTemplates.put(buildingTypeId, Map.of()); // mark as empty
                return;
            }
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            Map<String, List<String>> tmpl = new HashMap<>();
            if (root.has("templates")) {
                JsonObject tObj = root.getAsJsonObject("templates");
                for (var entry : tObj.entrySet()) {
                    tmpl.put(entry.getKey(), parseStringList(entry.getValue()));
                }
            }
            buildingTemplates.put(buildingTypeId, tmpl);
            Log.debug(TAG, "Loaded building narrative: {} ({} events)", buildingTypeId, tmpl.size());
        } catch (Exception e) {
            buildingTemplates.put(buildingTypeId, Map.of()); // mark as empty on error
            Log.warn(TAG, "Failed to load building narrative {}: {}", path, e.getMessage());
        }
    }

    // ── Public lookup API (two-level resolution) ──

    /**
     * Get a random template for the given event, or a hardcoded fallback.
     *
     * @param buildingTypeId building type (e.g. "tavern"), nullable
     * @param category       building category (e.g. "shop"), nullable
     * @param eventType      narrative event key (e.g. "visit", "checkin")
     * @return a template string with {placeholder}s
     */
    public String getTemplate(@Nullable String buildingTypeId, @Nullable String category,
                               String eventType) {
        // Level 1: building-specific
        if (buildingTypeId != null) {
            loadBuilding(buildingTypeId);
            Map<String, List<String>> bldTmpl = buildingTemplates.get(buildingTypeId);
            if (bldTmpl != null && bldTmpl.containsKey(eventType)) {
                List<String> candidates = bldTmpl.get(eventType);
                if (!candidates.isEmpty()) return randomPick(candidates);
            }
        }

        // Level 2: global category
        if (category != null) {
            Map<String, List<String>> catTmpl = categoryTemplates.get(category);
            if (catTmpl != null && catTmpl.containsKey(eventType)) {
                List<String> candidates = catTmpl.get(eventType);
                if (!candidates.isEmpty()) return randomPick(candidates);
            }
        }

        // Level 3: hardcoded fallback
        return hardcodedFallback(eventType);
    }

    /**
     * Get a random template from the generic pool.
     */
    public String getGenericTemplate(String key) {
        List<String> candidates = genericTemplates.get(key);
        if (candidates != null && !candidates.isEmpty()) {
            return randomPick(candidates);
        }
        return hardcodedFallback(key);
    }

    /**
     * Pick a random adjective for the given emotion.
     */
    public String pickEmotionAdjective(Emotion emotion) {
        List<String> candidates = emotionAdjectives.get(emotion.name());
        if (candidates != null && !candidates.isEmpty()) {
            return candidates.get(RNG.nextInt(candidates.size()));
        }
        // Hardcoded fallback per emotion
        return switch (emotion) {
            case DELIGHTED -> "非常满意";
            case PLEASED -> "满意";
            case SATISFIED -> "还行";
            case NEUTRAL -> "没什么感觉";
            case DISAPPOINTED -> "有点失望";
            case UPSET -> "非常不满";
        };
    }

    // ── Template rendering ──

    /**
     * Substitute placeholders in a template string.
     */
    public static String render(String template, Map<String, String> vars) {
        String result = template;
        for (var entry : vars.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    // ── Helpers ──

    private static String randomPick(List<String> candidates) {
        return candidates.get(RNG.nextInt(candidates.size()));
    }

    private String hardcodedFallback(String key) {
        return switch (key) {
            case "visit" -> FALLBACK_VISIT;
            case "checkin" -> FALLBACK_HOTEL_CHECKIN;
            case "wakeup" -> FALLBACK_HOTEL_WAKEUP;
            case "arrival_morning", "arrival_afternoon" -> FALLBACK_ARRIVAL_MORNING;
            case "arrival_night" -> FALLBACK_ARRIVAL_NIGHT;
            case "departure_delighted", "departure_pleased",
                 "departure_neutral", "departure_unsatisfied" -> FALLBACK_DEPARTURE;
            case "satisfaction_milestone_50" -> FALLBACK_SATISFACTION_50;
            case "satisfaction_milestone_70" -> FALLBACK_SATISFACTION_70;
            case "satisfaction_milestone_100" -> FALLBACK_SATISFACTION_100;
            default -> "{name}";
        };
    }

    private static List<String> parseStringList(JsonElement el) {
        if (el.isJsonArray()) {
            List<String> list = new ArrayList<>();
            for (JsonElement e : el.getAsJsonArray()) {
                list.add(e.getAsString());
            }
            return list;
        }
        return List.of(el.getAsString());
    }

    @Nullable
    private static Reader openResource(String path) {
        var is = NarrativeTemplates.class.getClassLoader().getResourceAsStream(path);
        if (is == null) return null;
        return new InputStreamReader(is, StandardCharsets.UTF_8);
    }
}
