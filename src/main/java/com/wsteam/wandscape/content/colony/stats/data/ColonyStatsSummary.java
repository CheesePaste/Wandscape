package com.wsteam.wandscape.stats.data;

/**
 * Pre-computed aggregate summary of the 30-day rolling statistics window
 * for a single colony. Sent to the client for panel display.
 */
public record ColonyStatsSummary(
        long currentDay,
        int touristsArrived,
        int touristsDeparted,
        int avgComfortRatio,
        int avgMagicRatio,
        int avgWonderRatio,
        int comfort,
        int magic,
        int wonder,
        int snapshotCount
) {
    public static final ColonyStatsSummary EMPTY = new ColonyStatsSummary(
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
}
