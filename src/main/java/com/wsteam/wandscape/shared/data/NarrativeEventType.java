package com.wsteam.wandscape.shared.data;

/**
 * Types of narrative events that can occur during a tourist's journey.
 */
public enum NarrativeEventType {

    ARRIVAL(false),
    VISIT_SHOP(false),
    VISIT_SERVICE(false),
    VISIT_RELAX(false),
    VISIT_ATM(false),
    HOTEL_CHECKIN(false),
    HOTEL_WAKEUP(false),
    SATISFACTION_MILESTONE(true),
    ENERGY_CRITICAL(false),
    MAGE_RECRUIT(true),
    DEPARTURE(false),
    DEPARTURE_SUMMARY(true);

    private final boolean chronicleWorthy;

    NarrativeEventType(boolean chronicleWorthy) {
        this.chronicleWorthy = chronicleWorthy;
    }

    /** Whether events of this type should be stored in the colony chronicle. */
    public boolean isChronicleWorthy() {
        return chronicleWorthy;
    }
}
