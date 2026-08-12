package com.wsteam.wandscape.building.internal;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.wsteam.wandscape.shared.data.BuildingData;
import com.wsteam.wandscape.shared.data.WorkItem;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
/**
 * Mutable building state — replaces all {@code AbstractWandscapeBE} fields.
 * Implements {@link BuildingData} for read-only access.
 */
public class BuildingState implements BuildingData {
    private final UUID buildingId;
    private final String buildingTypeId;
    private final String category;
    private final BlockPos anchor;
    private final BoundingBox bounds;
    private final int comfort;
    private final int magic;
    private final int wonder;
    private final int queueCapacity;

    // ── Shutdown tracking ──
    private String shutdownReason = "";

    @Nullable
    private UUID colonyId;
    private boolean shutdown;
    private boolean structureIntact;
    /** Sticky flag: set once construction completes, never reset (drives the ghost). */
    private boolean hasEverCompleted;
    /** Sticky flag: set once construction work is claimed by an NPC, never reset. */
    private boolean constructionStarted;
    private boolean demolishing;
    private final Deque<WorkItem> taskQueue = new ArrayDeque<>();
    @Nullable
    private Set<BlockPos> patternPositions;
    @Nullable
    private UUID currentTaskId;
    private int rotationSteps;

    public BuildingState(UUID buildingId, String buildingTypeId, String category,
                         BlockPos anchor, BoundingBox bounds,
                         int comfort, int magic, int wonder,
                         int queueCapacity) {
        this.buildingId = buildingId;
        this.buildingTypeId = buildingTypeId;
        this.category = category;
        this.anchor = anchor;
        this.bounds = bounds;
        this.comfort = comfort;
        this.magic = magic;
        this.wonder = wonder;
        this.queueCapacity = queueCapacity;
    }

    // ── BuildingData getters ──

    @Override public UUID getBuildingId() { return buildingId; }
    @Override public String getBuildingTypeId() { return buildingTypeId; }
    @Override public String getCategory() { return category; }
    @Override public BlockPos getPosition() { return anchor; }
    @Override public boolean isShutdown() { return shutdown; }
    @Override public boolean isDemolishing() { return demolishing; }
    @Override public int getComfort() { return comfort; }
    @Override public int getMagic() { return magic; }
    @Override public int getWonder() { return wonder; }
    @Override public int getQueueCapacity() { return queueCapacity; }

    // ── Extended getters ──

    public BlockPos getAnchor() { return anchor; }
    public BoundingBox getBounds() { return bounds; }
    @Nullable public UUID getColonyId() { return colonyId; }
    @Override public boolean isStructureIntact() { return structureIntact; }
    @Override public boolean hasEverCompleted() { return hasEverCompleted; }
    @Override public boolean isConstructionStarted() { return constructionStarted; }
    @Nullable public UUID getCurrentTaskId() { return currentTaskId; }
    public Deque<WorkItem> getTaskQueue() { return taskQueue; }
    public boolean hasWork() {
        if (taskQueue.isEmpty()) return false;
        if (!shutdown) return true;
        // Shutdown buildings can still process repair tasks
        WorkItem first = taskQueue.peekFirst();
        return first != null && "build:place_structure".equals(first.blueprintId());
    }

    // ── Shutdown getter ──

    @Override public String getShutdownReason() { return shutdownReason; }

    // ── Pattern positions getter/setter ──

    /** World-space pattern block positions for precise overlap detection. */
    @Nullable
    public Set<BlockPos> getPatternPositions() { return patternPositions; }

    public void setPatternPositions(@Nullable Set<BlockPos> positions) { this.patternPositions = positions; }

    // ── Setters ──

    public void setColonyId(@Nullable UUID colonyId) { this.colonyId = colonyId; }
    public void setShutdown(boolean shutdown) { this.shutdown = shutdown; }
    public void setStructureIntact(boolean intact) { this.structureIntact = intact; }
    public void setHasEverCompleted(boolean completed) { this.hasEverCompleted = completed; }
    public void setConstructionStarted(boolean started) { this.constructionStarted = started; }
    public void setDemolishing(boolean demolishing) { this.demolishing = demolishing; }
    public void setCurrentTaskId(@Nullable UUID taskId) { this.currentTaskId = taskId; }
    public int getRotationSteps() { return rotationSteps; }
    public void setRotationSteps(int steps) { this.rotationSteps = steps & 3; }

    // ── Shutdown setter ──

    public void setShutdownReason(String reason) { this.shutdownReason = reason; }
}
