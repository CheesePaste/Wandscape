package com.wsteam.wandscape.shared.data;

import java.util.UUID;

import net.minecraft.core.BlockPos;
public interface BuildingData {
    UUID getBuildingId();
    UUID getColonyId();
    String getBuildingTypeId();
    String getCategory();
    BlockPos getPosition();
    boolean isShutdown();
    int getComfort();
    int getMagic();
    int getWonder();
    int getQueueCapacity();
    boolean isStructureIntact();

    /** Snapshot of the building's maintenance cost config. */
    MaintenanceCostConfig getMaintenanceCost();

    /** Last game tick when maintenance was processed. */
    long getLastMaintenanceTick();

    /** Whether the last maintenance cycle was paid in full. */
    boolean isMaintenancePaid();
}
