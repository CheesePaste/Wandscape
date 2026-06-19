package com.wsteam.wandscape.shared.event;

import java.util.UUID;

import net.neoforged.bus.api.Event;

public class BuildingRestartedEvent extends Event {
    private final UUID buildingId;

    public BuildingRestartedEvent(UUID buildingId) {
        this.buildingId = buildingId;
    }

    public UUID getBuildingId() { return buildingId; }
}
