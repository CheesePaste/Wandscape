package com.wsteam.wandscape.tourist.internal;

import com.wsteam.wandscape.shared.data.Emotion;
import com.wsteam.wandscape.shared.data.NarrativeEvent;
import com.wsteam.wandscape.shared.data.NarrativeEventType;
import com.wsteam.wandscape.shared.data.VisitMemory;

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

        NarrativeEventType type = switch (memory.category()) {
            case "shop" -> NarrativeEventType.VISIT_SHOP;
            case "relax" -> NarrativeEventType.VISIT_RELAX;
            case "atm" -> NarrativeEventType.VISIT_ATM;
            default -> NarrativeEventType.VISIT_SERVICE;
        };

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
                                                    int minRatioPct,
                                                    int visitCount,
                                                    long gameTime) {
        NarrativeTemplates tmpl = NarrativeTemplates.getInstance();
        Emotion tone = Emotion.fromBarRatio(minRatioPct);
        Map<String, String> vars = Map.of(
                "name", touristName,
                "visit_count", String.valueOf(visitCount)
        );

        String template = tmpl.getGenericTemplate("departure_" + tone.name().toLowerCase());
        String text = NarrativeTemplates.render(template, vars);

        return NarrativeEvent.of(NarrativeEventType.DEPARTURE, gameTime, tone, text);
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
}
