package com.wsteam.wandscape.shared.event;

import java.util.UUID;

import net.neoforged.bus.api.Event;

/** Fired when a tourist leaves a colony. */
public class TouristDepartedEvent extends Event {
    private final UUID touristId;
    private final UUID colonyId;
    private final int satisfaction;

    public TouristDepartedEvent(UUID touristId, UUID colonyId, int satisfaction) {
        this.touristId = touristId;
        this.colonyId = colonyId;
        this.satisfaction = satisfaction;
    }

    public UUID getTouristId() { return touristId; }
    public UUID getColonyId() { return colonyId; }
    public int getSatisfaction() { return satisfaction; }
}
