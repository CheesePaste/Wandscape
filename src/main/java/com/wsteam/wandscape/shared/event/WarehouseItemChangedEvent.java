package com.wsteam.wandscape.shared.event;

import java.util.UUID;

import com.wsteam.wandscape.shared.data.ItemKey;

import net.neoforged.bus.api.Event;

/**
 * Fired when an item stack in a colony's warehouse changes (deposit, withdraw, craft, consumption).
 * Addons (such as AE2 warehouse cells) can listen to this event for O(1) incremental sync.
 */
public class WarehouseItemChangedEvent extends Event {
    private final UUID colonyId;
    private final ItemKey itemKey;
    private final long newCount;
    private final long delta;

    public WarehouseItemChangedEvent(UUID colonyId, ItemKey itemKey, long newCount, long delta) {
        this.colonyId = colonyId;
        this.itemKey = itemKey;
        this.newCount = newCount;
        this.delta = delta;
    }

    public UUID getColonyId() {
        return colonyId;
    }

    public ItemKey getItemKey() {
        return itemKey;
    }

    public long getNewCount() {
        return newCount;
    }

    public long getDelta() {
        return delta;
    }
}
