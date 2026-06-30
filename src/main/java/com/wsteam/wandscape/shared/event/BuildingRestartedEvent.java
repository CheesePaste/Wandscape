package com.wsteam.wandscape.shared.event;

import java.util.UUID;

import javax.annotation.Nullable;

import net.neoforged.bus.api.Event;
/** Fired when a building is restarted after shutdown. */
public class BuildingRestartedEvent extends Event {
    private final UUID buildingId;
    @Nullable
    private final UUID colonyId;

    public BuildingRestartedEvent(UUID buildingId) {
        this(buildingId, null);
    }

    public BuildingRestartedEvent(UUID buildingId, @Nullable UUID colonyId) {
        this.buildingId = buildingId;
        this.colonyId = colonyId;
    }

    public UUID getBuildingId() { return buildingId; }
    @Nullable
    public UUID getColonyId() { return colonyId; }
}
