package com.wsteam.wandscape.content.building.projection.data;

import com.wsteam.wandscape.content.building.data.BuildingPackage;

/**
 * Lightweight DTO for a single building slot in the projection mode scroll list.
 * Sent from server to client in {@code ProjectionEnterResponsePacket}.
 *
 * @param id                 building type identifier (matches {@code BuildingConfig.id()})
 * @param packageId          building package identifier (e.g. "default", "oriental")
 * @param displayName        localized display name
 * @param category           building category (e.g. "basic", "storage", "node", "production")
 * @param firstFreeAvailable whether the colony's first-free build of this type is still
 *                           unused (config has {@code first_free: true} and not yet claimed).
 *                           Server-authoritative; used by the build panel to badge the slot.
 */
public record BuildingSlot(
        String id,
        String packageId,
        String displayName,
        String category,
        boolean firstFreeAvailable
) {
    public BuildingSlot(String id, String displayName, String category, boolean firstFreeAvailable) {
        this(id, BuildingPackage.DEFAULT_ID, displayName, category, firstFreeAvailable);
    }
}

