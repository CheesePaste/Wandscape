package com.wsteam.wandscape.content.building.event;

import net.neoforged.bus.api.Event;

import java.util.UUID;
public class BuildingPlacedEvent extends Event {
    private final UUID buildingId;
    private final UUID colonyId;
    private final String buildingTypeId;

    public BuildingPlacedEvent(UUID buildingId, UUID colonyId, String buildingTypeId) {
        this.buildingId = buildingId;
        this.colonyId = colonyId;
        this.buildingTypeId = buildingTypeId;
    }

    public UUID getBuildingId() { return buildingId; }
    public UUID getColonyId() { return colonyId; }
    public String getBuildingTypeId() { return buildingTypeId; }
}
