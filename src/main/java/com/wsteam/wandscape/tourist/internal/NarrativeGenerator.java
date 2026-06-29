package com.wsteam.wandscape.tourist.internal;

import com.wsteam.wandscape.shared.data.Emotion;
import com.wsteam.wandscape.shared.data.NarrativeEvent;
import com.wsteam.wandscape.shared.data.NarrativeEventType;
import com.wsteam.wandscape.shared.data.VisitMemory;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates narrative text for tourist events using the two-level template resolution.
 *
 * <p>Each generation method builds a variable map ({@code {name}, {building}, {item}, ...})
 * and delegates to {@link NarrativeTemplates} for template selection and rendering.
 */
public final class NarrativeGenerator {

    private NarrativeGenerator() {}

    // ═══════════════════════════════════════════════════════════════
    // Public generation API
    // ═══════════════════════════════════════════════════════════════

    /**
     * Generate a narrative event for a building visit (shop, service, hotel-as-service).
     */
    public static NarrativeEvent generateVisit(VisitMemory memory) {
        NarrativeTemplates tmpl = NarrativeTemplates.getInstance();
        Map<String, String> vars = Map.of(
                "name", "",
                "building", memory.buildingDisplayName(),
                "item", memory.whatHappened(),
                "emotion_adj", tmpl.pickEmotionAdjective(memory.emotion())
        );

        String template = tmpl.getTemplate(
                memory.buildingTypeId(), memory.category(), "visit");
        String text = NarrativeTemplates.render(template, vars);

        NarrativeEventType type = "shop".equals(memory.category())
                ? NarrativeEventType.VISIT_SHOP
                : NarrativeEventType.VISIT_SERVICE;

        return NarrativeEvent.of(type, memory.gameTime(), memory.emotion(), text);
    }

    /**
     * Generate arrival text when a tourist spawns.
     *
     * @param dayPhase "morning", "afternoon", or "night"
     */
    public static NarrativeEvent generateArrival(String touristName, String dayPhase, long gameTime) {
        NarrativeTemplates tmpl = NarrativeTemplates.getInstance();
        Map<String, String> vars = Map.of("name", touristName);

        String template = tmpl.getGenericTemplate("arrival_" + dayPhase);
        String text = NarrativeTemplates.render(template, vars);

        return NarrativeEvent.of(NarrativeEventType.ARRIVAL, gameTime, Emotion.NEUTRAL, text);
    }

    /**
     * Generate departure text when a tourist leaves.
     */
    public static NarrativeEvent generateDeparture(String touristName,
                                                    int satisfaction,
                                                    int visitCount,
                                                    long gameTime) {
        NarrativeTemplates tmpl = NarrativeTemplates.getInstance();
        Emotion tone = Emotion.fromSatisfaction(satisfaction);
        Map<String, String> vars = Map.of(
                "name", touristName,
                "visit_count", String.valueOf(visitCount)
        );

        String template = tmpl.getGenericTemplate("departure_" + tone.name().toLowerCase());
        String text = NarrativeTemplates.render(template, vars);

        return NarrativeEvent.of(NarrativeEventType.DEPARTURE, gameTime, tone, text);
    }

    /**
     * Generate departure summary line (condensed version for action bar).
     */
    public static String generateDepartureSummary(String touristName, int satisfaction, int visitCount) {
        NarrativeTemplates tmpl = NarrativeTemplates.getInstance();
        Emotion tone = Emotion.fromSatisfaction(satisfaction);
        Map<String, String> vars = Map.of(
                "name", touristName,
                "visit_count", String.valueOf(visitCount)
        );

        String key = "departure_" + tone.name().toLowerCase();
        String template = tmpl.getGenericTemplate(key);
        return NarrativeTemplates.render(template, vars);
    }

    /**
     * Generate hotel checkin text.
     */
    public static NarrativeEvent generateHotelCheckin(String touristName,
                                                       String buildingTypeId,
                                                       String buildingDisplayName,
                                                       long gameTime) {
        NarrativeTemplates tmpl = NarrativeTemplates.getInstance();
        Map<String, String> vars = Map.of(
                "name", touristName,
                "building", buildingDisplayName
        );

        String template = tmpl.getTemplate(buildingTypeId, "service", "checkin");
        String text = NarrativeTemplates.render(template, vars);

        return NarrativeEvent.of(NarrativeEventType.HOTEL_CHECKIN, gameTime, Emotion.NEUTRAL, text);
    }

    /**
     * Generate hotel wakeup text.
     */
    public static NarrativeEvent generateHotelWakeup(String touristName,
                                                      String buildingTypeId,
                                                      String buildingDisplayName,
                                                      long gameTime) {
        NarrativeTemplates tmpl = NarrativeTemplates.getInstance();
        Map<String, String> vars = Map.of(
                "name", touristName,
                "building", buildingDisplayName
        );

        String template = tmpl.getTemplate(buildingTypeId, "service", "wakeup");
        String text = NarrativeTemplates.render(template, vars);

        return NarrativeEvent.of(NarrativeEventType.HOTEL_WAKEUP, gameTime, Emotion.NEUTRAL, text);
    }

    /**
     * Generate satisfaction milestone text.
     */
    @Nullable
    public static NarrativeEvent generateSatisfactionMilestone(String touristName,
                                                                int satisfaction,
                                                                long gameTime) {
        String key;
        if (satisfaction >= 100) key = "satisfaction_milestone_100";
        else if (satisfaction >= 70) key = "satisfaction_milestone_70";
        else if (satisfaction >= 50) key = "satisfaction_milestone_50";
        else return null; // no milestone yet

        NarrativeTemplates tmpl = NarrativeTemplates.getInstance();
        String template = tmpl.getGenericTemplate(key);
        String text = NarrativeTemplates.render(template, Map.of("name", touristName));

        return NarrativeEvent.of(NarrativeEventType.SATISFACTION_MILESTONE, gameTime,
                Emotion.PLEASED, text);
    }

    /**
     * Generate quick one-line text for ActionBar display.
     * This is the primary output for Phase 2 — no GUI needed.
     */
    public static String generateActionBarText(VisitMemory memory, String touristName) {
        String template = NarrativeTemplates.getInstance().getTemplate(
                memory.buildingTypeId(), memory.category(), "visit");
        Map<String, String> vars = new HashMap<>();
        vars.put("name", touristName);
        vars.put("building", memory.buildingDisplayName());
        vars.put("item", memory.whatHappened());
        vars.put("emotion_adj", NarrativeTemplates.getInstance().pickEmotionAdjective(memory.emotion()));
        return NarrativeTemplates.render(template, vars);
    }
}
