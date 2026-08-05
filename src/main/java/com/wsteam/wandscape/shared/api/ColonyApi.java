package com.wsteam.wandscape.shared.api;

import java.util.Collection;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
public interface ColonyApi {
    /** Register a new colony at the given origin. Returns its UUID. */
    default UUID createColony(BlockPos origin) {
        return createColony(origin, null);
    }

    /** Register a new colony at the given origin, recording the founding player. */
    UUID createColony(BlockPos origin, @Nullable UUID founder);

    /** The founding player UUID of a colony, or null if unknown (legacy/console-created). */
    @Nullable
    UUID getFounder(UUID colonyId);

    /** The colony founded by the given player (one player = one colony), or null. */
    @Nullable
    UUID getColonyByFounder(UUID founder);

    /** Find the nearest colony UUID within 256 blocks of pos, or null. */
    UUID getColonyId(BlockPos pos);

    /** Remove a colony and clear its building associations. */
    void deleteColony(UUID colonyId);

    /** True if pos is a registered colony origin. */
    boolean isColonyOrigin(BlockPos pos);

    // Called by BuildCompleteListener when a building becomes intact.
    // Returns the colonyId assigned, or null if none found.
    UUID onBuildingIntact(com.wsteam.wandscape.shared.data.BuildingData building);

    // Called by BuildingBreakHandler when a building is destroyed.
    void onBuildingDestroyed(com.wsteam.wandscape.shared.data.BuildingData building);

    // Try to assign a colony to a newly registered building.
    void assignColonyIfPossible(com.wsteam.wandscape.shared.data.BuildingData building);

    /** Returns all registered colony UUIDs. Empty if no colonies exist. */
    Collection<UUID> getAllColonyIds();

    // Rebuild spatial index from saved data (called on server start).
    void rebuildFromSavedData();
}
