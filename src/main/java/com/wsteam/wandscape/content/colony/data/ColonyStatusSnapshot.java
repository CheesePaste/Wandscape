package com.wsteam.wandscape.content.colony.data;

import java.util.List;
import java.util.UUID;

public record ColonyStatusSnapshot(
        UUID colonyId,
        int comfort, int magic, int wonder,
        String colonyName, int colonyLevel, int colonyExperience,
        int touristCount, int overnightStayerCount,
        int npcIdleCount, int npcTotalCount,
        int earthAmount, int woodAmount, int waterAmount, int fireAmount,
        int windAmount, int metalAmount, int darkAmount,
        int underConstructionCount, List<UUID> underConstructionBuildingIds,
        List<String> underConstructionBuildingNames, List<Boolean> underConstructionStarted) {

    public static final ColonyStatusSnapshot EMPTY = new ColonyStatusSnapshot(
            null, 0, 0, 0, "", 1, 0,
            0, 0,
            0, 0,
            0, 0, 0, 0, 0, 0, 0,
            0, List.of(), List.of(), List.of());
}
