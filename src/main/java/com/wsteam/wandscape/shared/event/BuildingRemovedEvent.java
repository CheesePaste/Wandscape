package com.wsteam.wandscape.shared.event;

import java.util.UUID;

import javax.annotation.Nullable;

import net.neoforged.bus.api.Event;
/** Fired when a building is fully removed (demolished / unregistered). */
public class BuildingRemovedEvent extends Event {
    private final UUID buildingId;
    @Nullable
    private final UUID colonyId;

    public BuildingRemovedEvent(UUID buildingId, @Nullable UUID colonyId) {
        this.buildingId = buildingId;
        this.colonyId = colonyId;
    }

    public UUID getBuildingId() { return buildingId; }
    @Nullable
    public UUID getColonyId() { return colonyId; }
}
