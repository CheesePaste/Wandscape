package com.wsteam.wandscape.shared.event;

import java.util.List;
import java.util.UUID;

import com.wsteam.wandscape.shared.data.WonderEffect;

import net.neoforged.bus.api.Event;

/** Fired when a wonder's effects change (activated, paused, or modified). */
public class WonderEffectChangedEvent extends Event {
    private final UUID buildingId;
    private final UUID colonyId;
    private final List<WonderEffect> activeEffects;
    private final boolean active;

    public WonderEffectChangedEvent(UUID buildingId, UUID colonyId,
                                    List<WonderEffect> activeEffects, boolean active) {
        this.buildingId = buildingId;
        this.colonyId = colonyId;
        this.activeEffects = List.copyOf(activeEffects);
        this.active = active;
    }

    public UUID getBuildingId() { return buildingId; }
    public UUID getColonyId() { return colonyId; }
    public List<WonderEffect> getActiveEffects() { return activeEffects; }
    public boolean isActive() { return active; }
}
