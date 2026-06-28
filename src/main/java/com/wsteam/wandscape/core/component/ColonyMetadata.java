package com.wsteam.wandscape.core.component;

import com.wsteam.wandscape.core.types.GridPos;

import java.util.UUID;
/**
 * Colony-level metadata attached to the colony entity.
 * Defines the colony's identity, center, and territory radius.
 */
public record ColonyMetadata(UUID colonyId, GridPos center, int radius, int prosperity) {

    /** Check whether a position falls within this colony's territory. */
    public boolean contains(GridPos pos) {
        return Math.abs(pos.x() - center.x()) <= radius
                && Math.abs(pos.z() - center.z()) <= radius;
    }

    /** Create a colony entry with a new random UUID. */
    public static ColonyMetadata create(GridPos center, int radius) {
        return new ColonyMetadata(UUID.randomUUID(), center, radius, 0);
    }
}
