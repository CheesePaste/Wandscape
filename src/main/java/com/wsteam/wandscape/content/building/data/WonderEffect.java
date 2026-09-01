package com.wsteam.wandscape.content.building.data;

import com.google.gson.*;

import java.lang.reflect.Type;
/**
 * Sealed interface for wonder building global effects.
 * Applied when the wonder is intact; paused otherwise.
 */
public sealed interface WonderEffect {

    /** Modifies a numeric stat globally (e.g. NPC spell power, max mana). */
    record StatMod(String target, int value) implements WonderEffect {}

    /** Modifies shop prices globally. Positive percentage = price increase. */
    record PriceMod(String target, double percentage) implements WonderEffect {}

    /** Unlocks a rule-level capability (e.g. cross-colony transport). */
    record RuleUnlock(String ruleId) implements WonderEffect {}

    /** Custom Gson deserializer that dispatches on the "type" field. */
    class Deserializer implements JsonDeserializer<WonderEffect> {
        @Override
        public WonderEffect deserialize(JsonElement json, Type typeOfT,
                                        JsonDeserializationContext context) throws JsonParseException {
            JsonObject obj = json.getAsJsonObject();
            String type = obj.has("type") ? obj.get("type").getAsString() : "";

            return switch (type) {
                case "stat_mod" -> {
                    String target = obj.has("target") ? obj.get("target").getAsString() : "";
                    int value = obj.has("value") ? obj.get("value").getAsInt() : 0;
                    yield new StatMod(target, value);
                }
                case "price_mod" -> {
                    String target = obj.has("target") ? obj.get("target").getAsString() : "";
                    double percentage = obj.has("percentage") ? obj.get("percentage").getAsDouble() : 0.0;
                    yield new PriceMod(target, percentage);
                }
                case "rule_unlock" -> {
                    String ruleId = obj.has("rule_id") ? obj.get("rule_id").getAsString() : "";
                    yield new RuleUnlock(ruleId);
                }
                default -> throw new JsonParseException("Unknown WonderEffect type: " + type);
            };
        }
    }
}
