package com.wsteam.wandscape.shared.event;

import java.util.UUID;

import javax.annotation.Nullable;

import net.neoforged.bus.api.Event;
/** Fired when a building enters shutdown state. */
public class BuildingShutdownEvent extends Event {
    private final UUID buildingId;
    @Nullable
    private final UUID colonyId;
    private final String reason;

    public BuildingShutdownEvent(UUID buildingId) {
        this(buildingId, null, "manual");
    }

    public BuildingShutdownEvent(UUID buildingId, @Nullable UUID colonyId, String reason) {
        this.buildingId = buildingId;
        this.colonyId = colonyId;
        this.reason = reason;
    }

    public UUID getBuildingId() { return buildingId; }
    @Nullable
    public UUID getColonyId() { return colonyId; }
    public String getReason() { return reason; }
}
