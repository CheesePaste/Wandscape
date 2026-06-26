package com.wsteam.wandscape.citizen;

/**
 * Movement state label for tourist entities.
 * Updated by {@link com.wsteam.wandscape.tourist.internal.TouristMoveGoal}
 * to reflect actual movement destination.
 */
public enum CitizenState {
    /** Heading to a building for interaction. */
    VISITING("前往建筑"),

    /** Heading to a POI for sightseeing. */
    EXPLORING("游览中"),

    /** Random walk near home. */
    WANDERING("闲逛中"),

    /** Standing still. */
    IDLE("空闲"),

    /** In bed, sleeping pose. */
    SLEEPING("睡眠中");

    private final String displayName;

    CitizenState(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
