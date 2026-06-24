package com.wsteam.wandscape.shared.api;

import java.util.UUID;

import net.minecraft.core.BlockPos;

public interface ColonyApi {
    UUID createColony(BlockPos townHallPos);
    UUID getColonyId(BlockPos pos);
    void deleteColony(UUID colonyId);
    boolean isColonyBlock(BlockPos pos);

    // Called by BuildCompleteListener when a building becomes intact.
    // Returns the colonyId assigned, or null if none found.
    UUID onBuildingIntact(com.wsteam.wandscape.shared.data.BuildingData building);

    // Called by BuildingBreakHandler when a building is destroyed.
    void onBuildingDestroyed(com.wsteam.wandscape.shared.data.BuildingData building);

    // Try to assign a colony to a newly registered building.
    void assignColonyIfPossible(com.wsteam.wandscape.shared.data.BuildingData building);

    // Rebuild spatial index from saved data (called on server start).
    void rebuildFromSavedData();
}
