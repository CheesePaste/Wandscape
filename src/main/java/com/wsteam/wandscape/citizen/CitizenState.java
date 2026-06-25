package com.wsteam.wandscape.citizen;

/**
 * Citizen behavioural state for the state machine.
 * Used by {@link CitizenManager} to determine what the citizen
 * should be doing each tick, and by the AI goal to pick behaviour.
 */
public enum CitizenState {
    /** Standing around, occasional slow wandering. Fallback state. */
    IDLE("空闲"),

    /** Walking to a target (workplace, home, bed) using road network. */
    COMMUTING("通勤中"),

    /** At workplace, slow wander near building. */
    WORKING("工作中"),

    /** Free time, wandering near home or plaza. */
    LEISURE("休闲中"),

    /** In bed, not moving, sleeping pose. */
    SLEEPING("睡眠中");

    private final String displayName;

    CitizenState(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
