package com.wsteam.wandscape.api;
import com.wsteam.wandscape.content.building.source.BuildingTaskSource;
import com.wsteam.wandscape.content.task.component.Position;
import com.wsteam.wandscape.content.task.ecs.World;
import com.wsteam.wandscape.foundation.registry.WandscapeConstants;

import com.wsteam.wandscape.content.building.data.BuildingData;
import com.wsteam.wandscape.content.building.data.WorkItem;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;
public interface BuildingApi {
    // ---- Query ----
    BuildingData getBuilding(UUID buildingId);
    BuildingData getBuildingAt(BlockPos pos);
    List<BuildingData> getColonyBuildings(UUID colonyId);

    /**
     * Get the building's world-space bounding box (AABB).
     * Used by the guard system to compute defended zones. Returns null if the
     * building no longer exists.
     */
    @Nullable
    BoundingBox getBuildingBounds(UUID buildingId);

    // ---- Demolish ----
    void demolishBuilding(UUID buildingId);
    boolean isDemolishing(UUID buildingId);

    /**
     * Whether demolishing (or cancelling an under-construction) building would leave
     * the world with zero buildings of an essential category (town hall / warehouse /
     * workstation). Returns a player-facing reason Component when removal must be
     * blocked, null when allowed. Consult on the server thread before
     * {@link #demolishBuilding} / {@link #cancelBuilding} to show the player why
     * removal was refused.
     */
    @Nullable
    Component demolishBlockReason(UUID buildingId);

    /**
     * Undo an under-construction (not yet completed) building. Returns true when
     * the building was cancelled. If construction has started the built parts are
     * demolished and the full material cost is refunded to the colony warehouse;
     * if construction has not started the pending building is removed outright
     * (nothing was consumed, so nothing is refunded). Completed buildings cannot
     * be cancelled.
     */
    boolean cancelBuilding(UUID buildingId);

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

    /**
     * Enqueue a WorkItem into the building's priority-ordered queue.
     * Higher-priority tasks run first; a new task joins the tail of its own
     * priority band and merges into an adjacent same-recipe production task at
     * that band's tail. Bands: player &gt; restock &gt; auto (see
     * {@code WandscapeConstants.TASK_PRIORITY_*}).
     */
    void enqueueWork(UUID buildingId, WorkItem work);

    /** Get building IDs filtered by category. */
    List<UUID> getBuildingsByCategory(@Nullable UUID colonyId, String category);

    // ---- Placement (unified entry point) ----

    /**
     * Result of a building placement attempt via {@link #placeBuilding}. {@code error} is a
     * translatable Component resolved on the client (safe to build on the server).
     */
    record PlacementResult(boolean success, @Nullable UUID buildingId, boolean firstFree, @Nullable Component error) {
        public static PlacementResult ok(UUID buildingId, boolean firstFree) {
            return new PlacementResult(true, buildingId, firstFree, null);
        }
        public static PlacementResult fail(Component error) {
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
     * Whether this colony has already claimed the first-free build of a building type
     * (i.e. {@code first_free: true} in its config, and a building of that type has
     * already been placed for free here). Returns false when the first build is still free.
     */
    boolean isFirstFreeClaimed(UUID colonyId, String buildingTypeId);

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
    BlockPos getTouristInteractionTarget(UUID buildingId);

    /**
     * Get the entry point for tourists to approach a building.
     * A walkable ground position OUTSIDE the building, suitable as the
     * macro-navigation destination before switching to indoor micro-navigation.
     * Uses {@code door_offsets} from building config if defined, otherwise
     * heuristic spiral scan around the outside of the bounding box.
     */
    @Nullable
    BlockPos getEntryPoint(UUID buildingId);

    /**
     * Get the precise interaction point inside the building.
     * 第一个 interact spot 的世界坐标（anchor + 旋转偏移）；0-spot 建筑返回 null（无兜底）。
     */
    @Nullable
    BlockPos getTouristInteractPoint(UUID buildingId);

    // ── 可调平衡值（委托 BalanceValues；运行时生效，不追溯已生成实体）──

    double getDecorationBonusCap();
    void setDecorationBonusCap(double v);
    int getConstructionPlaceTicksPerUnit();
    void setConstructionPlaceTicksPerUnit(int v);
}
