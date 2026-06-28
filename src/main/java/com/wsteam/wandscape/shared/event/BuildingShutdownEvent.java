package com.wsteam.wandscape.shared.event;

import java.util.UUID;

import net.neoforged.bus.api.Event;
public class BuildingShutdownEvent extends Event {
    private final UUID buildingId;

    public BuildingShutdownEvent(UUID buildingId) {
        this.buildingId = buildingId;
    }

    public UUID getBuildingId() { return buildingId; }
}
