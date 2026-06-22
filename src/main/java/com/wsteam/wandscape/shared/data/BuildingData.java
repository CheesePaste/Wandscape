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
    int getMaintenanceCost();
    int getQueueCapacity();
    boolean isStructureIntact();
}
