package com.wsteam.wandscape.shared.event;

import java.util.UUID;

import com.wsteam.wandscape.shared.data.ElementType;

import net.neoforged.bus.api.Event;
public class ElementChangedEvent extends Event {
    private final UUID colonyId;
    private final ElementType type;
    private final long newAmount;
    private final long delta;

    public ElementChangedEvent(UUID colonyId, ElementType type, long newAmount, long delta) {
        this.colonyId = colonyId;
        this.type = type;
        this.newAmount = newAmount;
        this.delta = delta;
    }

    public UUID getColonyId() { return colonyId; }
    public ElementType getType() { return type; }
    public long getNewAmount() { return newAmount; }
    public long getDelta() { return delta; }
}
