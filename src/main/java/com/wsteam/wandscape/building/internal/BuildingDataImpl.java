package com.wsteam.wandscape.building.internal;

import java.util.UUID;

import com.wsteam.wandscape.shared.data.BuildingData;
import com.wsteam.wandscape.shared.data.MaintenanceCostConfig;

import net.minecraft.core.BlockPos;
/**
 * Package-private mutable implementation of {@link BuildingData}.
 * Only BuildingApiImpl creates these.
 */
class BuildingDataImpl implements BuildingData {
    private final UUID buildingId;
    private final String buildingTypeId;
    private final String category;
    private final BlockPos position;
    private final UUID colonyId;
    private boolean shutdown;
    private boolean structureIntact;
    private final int comfort;
    private final int magic;
    private final int wonder;
    private final int queueCapacity;

    BuildingDataImpl(UUID buildingId, String buildingTypeId, String category,
                     BlockPos position, UUID colonyId,
                     int comfort, int magic, int wonder,
                     int queueCapacity) {
        this.buildingId = buildingId;
        this.buildingTypeId = buildingTypeId;
        this.category = category;
        this.position = position;
        this.colonyId = colonyId;
        this.comfort = comfort;
        this.magic = magic;
        this.wonder = wonder;
        this.queueCapacity = queueCapacity;
    }

    void setShutdown(boolean shutdown) { this.shutdown = shutdown; }
    void setStructureIntact(boolean intact) { this.structureIntact = intact; }

    @Override public UUID getBuildingId() { return buildingId; }
    @Override public String getBuildingTypeId() { return buildingTypeId; }
    @Override public String getCategory() { return category; }
    @Override public BlockPos getPosition() { return position; }

    // Colony id is not part of the BuildingData interface, but used internally
    public UUID getColonyId() { return colonyId; }

    @Override public boolean isShutdown() { return shutdown; }
    @Override public boolean isStructureIntact() { return structureIntact; }
    @Override public int getComfort() { return comfort; }
    @Override public int getMagic() { return magic; }
    @Override public int getWonder() { return wonder; }
    @Override public int getQueueCapacity() { return queueCapacity; }

    @Override public MaintenanceCostConfig getMaintenanceCost() { return MaintenanceCostConfig.NONE; }
    @Override public long getLastMaintenanceTick() { return 0; }
    @Override public boolean isMaintenancePaid() { return true; }

    @Override
    public String toString() {
        return "BuildingData[" + buildingTypeId + " " + position + "]";
    }
}
