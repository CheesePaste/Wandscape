package com.wsteam.wandscape.shared.event;

import java.util.Map;
import java.util.UUID;

import com.wsteam.wandscape.shared.data.ElementType;

import net.neoforged.bus.api.Event;

/**
 * Fired when a colony's element reserves are below the maintenance threshold.
 * The forecast system uses this to signal that proactive gathering is needed.
 */
public class MaintenanceForecastWarningEvent extends Event {
    private final UUID colonyId;
    private final Map<ElementType, Long> shortfall;
    private final Map<ElementType, Long> dailyCost;

    public MaintenanceForecastWarningEvent(UUID colonyId,
                                           Map<ElementType, Long> shortfall,
                                           Map<ElementType, Long> dailyCost) {
        this.colonyId = colonyId;
        this.shortfall = shortfall;
        this.dailyCost = dailyCost;
    }

    public UUID getColonyId() { return colonyId; }
    public Map<ElementType, Long> getShortfall() { return shortfall; }
    public Map<ElementType, Long> getDailyCost() { return dailyCost; }
}
