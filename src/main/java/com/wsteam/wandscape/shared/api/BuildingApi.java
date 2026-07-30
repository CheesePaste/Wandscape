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
    boolean shutdown(UUID buildingId, String reason);
    boolean restart(UUID buildingId);

    // ---- Demolish ----
    void demolishBuilding(UUID buildingId);
    boolean isDemolishing(UUID buildingId);

    // ---- Colony stats ----

    /** All three evaluation values computed in a single traversal. */
    record ColonySnapshot(int comfort, int magic, int wonder) {
        public static final ColonySnapshot EMPTY = new ColonySnapshot(0, 0, 0);
    }

    @Nullable
    ColonySnapshot getColonySnapshot(UUID colonyId);

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

    /** Get a snapshot of the current task queue in FIFO order. */
    List<WorkItem> getQueue(UUID buildingId);

    /** Remove a task from the queue by index. Returns true if removed.
     *  Index 0 (current task) cannot be removed. */
    boolean removeFromQueue(UUID buildingId, int index);

    /** Swap the task at index with the one above it. Returns true if swapped.
     *  Index 0 cannot be moved. */
    boolean moveUp(UUID buildingId, int index);

    /** Swap the task at index with the one below it. Returns true if swapped.
     *  Index 0 cannot be moved. */
    boolean moveDown(UUID buildingId, int index);

    /** Clear the active task when it completes or is cancelled. */
    void clearCurrentTask(UUID buildingId);

    // ---- Placement (unified entry point) ----

    /**
     * Result of a building placement attempt via {@link #placeBuilding}.
     */
    record PlacementResult(boolean success, @Nullable UUID buildingId, boolean firstFree, @Nullable String error) {
        public static PlacementResult ok(UUID buildingId, boolean firstFree) {
            return new PlacementResult(true, buildingId, firstFree, null);
        }
        public static PlacementResult fail(String error) {
            return new PlacementResult(false, null, false, error);
        }
    }

    /**
     * Unified building placement: validates config, checks overlap, registers,
     * handles first-free, builds WorkItem, and enqueues — all in one call.
     * Callers only need to handle the result and display messages.
     */
    PlacementResult placeBuilding(BlockPos anchor, String buildingTypeId, int rotationSteps);

    /**
     * Scan the building's boundary AABB for bed blocks.
     * Returns world-coordinate positions of every bed block found.
     * Each bed (two halves) produces two entries.
     */
    List<BlockPos> findBeds(UUID buildingId);

    /**
     * Sample random walkable ground positions within the building's
     * boundary AABB. Each returned position has a solid block under it
     * and air above. Used by tourist AI for LEISURE POI wandering.
     *
     * @param count number of positions to sample
     */
    List<BlockPos> sampleWalkableGround(UUID buildingId, int count);

    /**
     * Get the interaction target position for tourist AI navigation.
     * Returns a walkable ground position inside the building's bounding box.
     * Tourists navigate here to interact with the building (shop, service, etc.).
     * Returns the building anchor position as fallback if no walkable ground found.
     */
    @Nullable
    BlockPos getInteractionTarget(UUID buildingId);

    /**
     * Get the entry point for tourists to approach a building.
     * A walkable ground position OUTSIDE the building, suitable as the
     * macro-navigation destination before switching to indoor micro-navigation.
     * Uses {@code door_offset} from building config if defined, otherwise
     * heuristic spiral scan around the outside of the bounding box.
     */
    @Nullable
    BlockPos getEntryPoint(UUID buildingId);

    /**
     * Get the precise interaction point inside the building.
     * Iterates {@code interact_aabb} from building config if defined, spiral-scanning
     * each zone for walkable ground. Falls back to spiral scan inside the building's
     * bounding box.
     */
    @Nullable
    BlockPos getInteractPoint(UUID buildingId);
}
