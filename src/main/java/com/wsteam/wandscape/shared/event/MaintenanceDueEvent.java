package com.wsteam.wandscape.shared.event;

import java.util.UUID;

import com.wsteam.wandscape.shared.data.MaintenanceCostConfig;

import net.neoforged.bus.api.Event;

/** Fired when a building's maintenance cost is due for payment. */
public class MaintenanceDueEvent extends Event {
    private final UUID buildingId;
    private final UUID colonyId;
    private final MaintenanceCostConfig cost;

    public MaintenanceDueEvent(UUID buildingId, UUID colonyId, MaintenanceCostConfig cost) {
        this.buildingId = buildingId;
        this.colonyId = colonyId;
        this.cost = cost;
    }

    public UUID getBuildingId() { return buildingId; }
    public UUID getColonyId() { return colonyId; }
    public MaintenanceCostConfig getCost() { return cost; }
}
