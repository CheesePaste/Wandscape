package com.wsteam.wandscape.shared.event;

import java.util.UUID;

import net.neoforged.bus.api.Event;
public class MaintenanceTickEvent extends Event {
    private final UUID colonyId;

    public MaintenanceTickEvent(UUID colonyId) {
        this.colonyId = colonyId;
    }

    public UUID getColonyId() { return colonyId; }
}
