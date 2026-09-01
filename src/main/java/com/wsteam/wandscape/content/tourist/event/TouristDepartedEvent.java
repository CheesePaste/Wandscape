package com.wsteam.wandscape.content.tourist.event;

import com.wsteam.wandscape.content.tourist.data.BarRatio;
import net.neoforged.bus.api.Event;

import java.util.UUID;
/** Fired when a tourist leaves a colony. */
public class TouristDepartedEvent extends Event {
    private final UUID touristId;
    private final UUID colonyId;
    private final BarRatio fill;

    public TouristDepartedEvent(UUID touristId, UUID colonyId, BarRatio fill) {
        this.touristId = touristId;
        this.colonyId = colonyId;
        this.fill = fill != null ? fill : BarRatio.ZERO;
    }

    public UUID getTouristId() { return touristId; }
    public UUID getColonyId() { return colonyId; }
    public BarRatio getFill() { return fill; }
}
