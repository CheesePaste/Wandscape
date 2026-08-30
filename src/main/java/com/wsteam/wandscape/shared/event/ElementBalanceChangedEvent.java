package com.wsteam.wandscape.shared.event;

import net.neoforged.bus.api.Event;

import java.util.UUID;
/**
 * Fired when a colony's warehouse element balance changes (any element added or
 * consumed). Subscribers can resync UI that mirrors element balances.
 */
public class ElementBalanceChangedEvent extends Event {
    private final UUID colonyId;

    public ElementBalanceChangedEvent(UUID colonyId) {
        this.colonyId = colonyId;
    }

    public UUID getColonyId() { return colonyId; }
}