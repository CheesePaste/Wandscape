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

    /** Whether the building is being demolished by an NPC task. */
    default boolean isDemolishing() { return false; }

    /** Snapshot of the building's maintenance cost config. */
    MaintenanceCostConfig getMaintenanceCost();

    /** Last game tick when maintenance was processed. */
    long getLastMaintenanceTick();

    /** Whether the last maintenance cycle was paid in full. */
    boolean isMaintenancePaid();

    /** Reason for shutdown, or empty string if not shut down. */
    default String getShutdownReason() { return ""; }

    /** Last game day when daily settlement processed this building. */
    default long getLastSettlementDay() { return 0; }

    /** Number of 90° CCW rotation steps applied to the building (0-3). */
    default int getRotationSteps() { return 0; }
}
