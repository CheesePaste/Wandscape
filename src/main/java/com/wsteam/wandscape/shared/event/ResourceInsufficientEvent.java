package com.wsteam.wandscape.shared.event;

import com.wsteam.wandscape.core.types.ResourceId;

import net.neoforged.bus.api.Event;

/**
 * Posted to {@code NeoForge.EVENT_BUS} when a {@code ResourceRequestOp}
 * finds insufficient resources in the colony warehouse.
 *
 * <p>Subscribers (e.g. UI notification layer) can use this to alert
 * the player that the colony needs more of a specific resource.
 */
public class ResourceInsufficientEvent extends Event {

    private final ResourceId resource;
    private final int needed;
    private final int available;

    public ResourceInsufficientEvent(ResourceId resource, int needed, int available) {
        this.resource = resource;
        this.needed = needed;
        this.available = available;
    }

    public ResourceId getResource() { return resource; }
    public int getNeeded() { return needed; }
    public int getAvailable() { return available; }

    /** Human-readable shortage message, e.g. "⚠ Colony is short on stone_bricks: needs 10, has 3". */
    public String getShortageMessage() {
        return "⚠ Colony is short on " + resource.id()
                + ": needs " + needed + ", has " + available;
    }
}
