package com.wsteam.wandscape.shared.api;

import java.util.UUID;

import net.minecraft.core.BlockPos;

public interface ColonyApi {
    UUID createColony(BlockPos townHallPos);
    UUID getColonyId(BlockPos pos);
    void deleteColony(UUID colonyId);
    boolean isColonyBlock(BlockPos pos);
}
