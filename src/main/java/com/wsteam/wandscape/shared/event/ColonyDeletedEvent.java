package com.wsteam.wandscape.shared.event;

import java.util.UUID;

import net.neoforged.bus.api.Event;

public class ColonyDeletedEvent extends Event {
    private final UUID colonyId;

    public ColonyDeletedEvent(UUID colonyId) {
        this.colonyId = colonyId;
    }

    public UUID getColonyId() { return colonyId; }
}
