package com.wsteam.wandscape.api;

import java.util.List;
import java.util.UUID;
public interface HouseApi {
    UUID getAssignedNpc(UUID houseId);
    boolean isOccupied(UUID houseId);
    boolean assignNpc(UUID houseId, UUID npcId);
    boolean unassignNpc(UUID houseId);
    List<UUID> getVacantHouses(UUID colonyId);
}
