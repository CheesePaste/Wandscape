package com.wsteam.wandscape.building.internal;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

import javax.annotation.Nullable;

import com.wsteam.wandscape.shared.data.BuildingData;
import com.wsteam.wandscape.shared.data.MaintenanceCostConfig;
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

    // ── Maintenance tracking ──
    private MaintenanceCostConfig maintenanceCost = MaintenanceCostConfig.NONE;
    private long lastMaintenanceTick;
    private boolean maintenancePaid;

    @Nullable
    private UUID colonyId;
    private boolean shutdown;
    private boolean structureIntact;
    private final Deque<WorkItem> taskQueue = new ArrayDeque<>();
    @Nullable
    private UUID currentTaskId;

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
    @Override public int getComfort() { return comfort; }
    @Override public int getMagic() { return magic; }
    @Override public int getWonder() { return wonder; }
    @Override public int getQueueCapacity() { return queueCapacity; }

    // ── Extended getters ──

    public BlockPos getAnchor() { return anchor; }
    public BoundingBox getBounds() { return bounds; }
    @Nullable public UUID getColonyId() { return colonyId; }
    @Override public boolean isStructureIntact() { return structureIntact; }
    @Nullable public UUID getCurrentTaskId() { return currentTaskId; }
    public Deque<WorkItem> getTaskQueue() { return taskQueue; }
    public boolean hasWork() { return !taskQueue.isEmpty() && !shutdown; }

    // ── Maintenance getters ──

    public MaintenanceCostConfig getMaintenanceCost() { return maintenanceCost; }
    public long getLastMaintenanceTick() { return lastMaintenanceTick; }
    public boolean isMaintenancePaid() { return maintenancePaid; }

    // ── Setters ──

    public void setColonyId(@Nullable UUID colonyId) { this.colonyId = colonyId; }
    public void setShutdown(boolean shutdown) { this.shutdown = shutdown; }
    public void setStructureIntact(boolean intact) { this.structureIntact = intact; }
    public void setCurrentTaskId(@Nullable UUID taskId) { this.currentTaskId = taskId; }

    // ── Maintenance setters ──

    public void setMaintenanceCost(MaintenanceCostConfig cost) { this.maintenanceCost = cost; }
    public void setLastMaintenanceTick(long tick) { this.lastMaintenanceTick = tick; }
    public void setMaintenancePaid(boolean paid) { this.maintenancePaid = paid; }
}
