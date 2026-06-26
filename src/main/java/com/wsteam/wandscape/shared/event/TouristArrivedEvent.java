package com.wsteam.wandscape.shared.event;

import java.util.UUID;

import net.neoforged.bus.api.Event;

/** Fired when a tourist arrives at a colony. */
public class TouristArrivedEvent extends Event {
    private final UUID touristId;
    private final UUID colonyId;

    public TouristArrivedEvent(UUID touristId, UUID colonyId) {
        this.touristId = touristId;
        this.colonyId = colonyId;
    }

    public UUID getTouristId() { return touristId; }
    public UUID getColonyId() { return colonyId; }
}
