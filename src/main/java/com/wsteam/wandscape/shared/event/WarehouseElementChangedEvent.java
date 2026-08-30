package com.wsteam.wandscape.shared.event;

import com.wsteam.wandscape.shared.data.ElementType;
import net.neoforged.bus.api.Event;

import java.util.UUID;

/**
 * Fired when an element balance in a colony's warehouse changes with explicit delta information.
 */
public class WarehouseElementChangedEvent extends Event {
    private final UUID colonyId;
    private final ElementType elementType;
    private final long newAmount;
    private final long delta;

    public WarehouseElementChangedEvent(UUID colonyId, ElementType elementType, long newAmount, long delta) {
        this.colonyId = colonyId;
        this.elementType = elementType;
        this.newAmount = newAmount;
        this.delta = delta;
    }

    public UUID getColonyId() {
        return colonyId;
    }

    public ElementType getElementType() {
        return elementType;
    }

    public long getNewAmount() {
        return newAmount;
    }

    public long getDelta() {
        return delta;
    }
}
