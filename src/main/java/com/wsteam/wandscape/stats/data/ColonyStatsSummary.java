package com.wsteam.wandscape.stats.data;

import java.util.Map;

import com.wsteam.wandscape.shared.data.ElementType;

/**
 * Pre-computed aggregate summary of the 30-day rolling statistics window
 * for a single colony. Sent to the client for panel display.
 */
public record ColonyStatsSummary(
        long currentDay,
        int buildingsPaid,
        int buildingsShutdown,
        int buildingsRestarted,
        int touristsArrived,
        int touristsDeparted,
        int avgSatisfaction,
        int comfort,
        int magic,
        int wonder,
        Map<ElementType, Long> totalElementsConsumed,
        int snapshotCount
) {
    public static final ColonyStatsSummary EMPTY = new ColonyStatsSummary(
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, Map.of(), 0);
}
