package com.wsteam.wandscape.shared.event;

import java.util.UUID;

import net.neoforged.bus.api.Event;

/** Fired when a shop building completes its daily restock cycle. */
public class ShopRestockedEvent extends Event {
    private final UUID buildingId;
    private final UUID colonyId;

    public ShopRestockedEvent(UUID buildingId, UUID colonyId) {
        this.buildingId = buildingId;
        this.colonyId = colonyId;
    }

    public UUID getBuildingId() { return buildingId; }
    public UUID getColonyId() { return colonyId; }
}
