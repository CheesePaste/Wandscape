package com.wsteam.wandscape.shared.data;

import java.util.List;
import java.util.UUID;

public record ColonyMetricsSnapshot(
        UUID colonyId,
        int comfort, int magic, int wonder,
        String colonyName, int colonyLevel, int colonyExperience,
        int touristCount, int overnightStayerCount,
        int npcIdleCount, int npcTotalCount,
        int earthAmount, int woodAmount, int waterAmount, int fireAmount,
        int windAmount, int metalAmount, int darkAmount,
        int shutdownCount, List<String> shutdownBuildingNames, List<UUID> shutdownBuildingIds,
        int brokenCount, List<UUID> brokenBuildingIds, List<String> brokenBuildingNames,
        int underConstructionCount, List<UUID> underConstructionBuildingIds,
        List<String> underConstructionBuildingNames, List<Boolean> underConstructionStarted) {

    public static final ColonyMetricsSnapshot EMPTY = new ColonyMetricsSnapshot(
            null, 0, 0, 0, "", 1, 0,
            0, 0,
            0, 0,
            0, 0, 0, 0, 0, 0, 0,
            0, List.of(), List.of(),
            0, List.of(), List.of(),
            0, List.of(), List.of(), List.of());

    public int totalAnomalyCount() {
        return shutdownCount + brokenCount;
    }
}
