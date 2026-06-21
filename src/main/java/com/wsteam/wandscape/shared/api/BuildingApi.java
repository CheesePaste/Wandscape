package com.wsteam.wandscape.shared.api;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;

import com.wsteam.wandscape.shared.data.BuildingData;
import com.wsteam.wandscape.shared.data.WorkItem;

public interface BuildingApi {
    // ---- Query ----
    BuildingData getBuilding(UUID buildingId);
    BuildingData getBuildingAt(BlockPos pos);
    List<BuildingData> getColonyBuildings(UUID colonyId);

    // ---- Lifecycle (called by block place/break handlers) ----
    void registerBuilding(BuildingData data);
    void unregisterBuilding(BlockPos pos);

    // ---- Shutdown/Restart ----
    boolean shutdown(UUID buildingId);
    boolean restart(UUID buildingId);

    // ---- Colony stats ----
    int getColonyComfort(UUID colonyId);
    int getColonyMagic(UUID colonyId);
    int getColonyWonder(UUID colonyId);

    // ---- Task bridge (called by BuildingTaskSource) ----
    boolean isBuildingOccupied(UUID buildingId);

    /** Buildings that have queued work, are operational, and have no current task. */
    List<UUID> getBuildingsWithPendingWork(UUID colonyId);

    /** Dequeue the next WorkItem from the building's FIFO queue. Returns null if empty. */
    @Nullable
    WorkItem dequeueWork(UUID buildingId);

    /** Enqueue a WorkItem into the building's FIFO queue (e.g. auto-supply from node buildings). */
    void enqueueWork(UUID buildingId, WorkItem work);

    /** Get building IDs filtered by category. */
    List<UUID> getBuildingsByCategory(@Nullable UUID colonyId, String category);

    /** Mark the building as having an active task in the engine pool. */
    void setCurrentTask(UUID buildingId, UUID taskId);

    /** Clear the active task when it completes or is cancelled. */
    void clearCurrentTask(UUID buildingId);
}
