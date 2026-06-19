package com.wsteam.wandscape.core.types;

/**
 * Identifier for a ritual type (item_teleport, rain_call, warding, etc.).
 */
public record RitualId(String id) {

    public static final RitualId ITEM_TELEPORT = new RitualId("item_teleport");
    public static final RitualId RAIN_CALL = new RitualId("rain_call");
    public static final RitualId WARDING = new RitualId("warding");
    public static final RitualId PLAYER_SUMMON = new RitualId("player_summon");
    public static final RitualId CLEAR_WEATHER = new RitualId("clear_weather");
    public static final RitualId GROUP_VIGOR = new RitualId("group_vigor");
    public static final RitualId PORTAL_GATE = new RitualId("portal_gate");

    @Override
    public String toString() {
        return id;
    }
}
