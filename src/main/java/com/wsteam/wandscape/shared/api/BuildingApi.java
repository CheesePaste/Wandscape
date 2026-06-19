package com.wsteam.wandscape.shared.api;

import java.util.List;
import java.util.UUID;

import net.minecraft.core.BlockPos;

import com.wsteam.wandscape.shared.data.BuildingData;

public interface BuildingApi {
    BuildingData getBuilding(UUID buildingId);
    BuildingData getBuildingAt(BlockPos pos);
    List<BuildingData> getColonyBuildings(UUID colonyId);
    boolean shutdown(UUID buildingId);
    boolean restart(UUID buildingId);
    int getColonyComfort(UUID colonyId);
    int getColonyMagic(UUID colonyId);
    int getColonyWonder(UUID colonyId);
    boolean isBuildingOccupied(UUID buildingId);
}
