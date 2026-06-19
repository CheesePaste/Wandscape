package com.wsteam.wandscape.building.be;

import com.wsteam.wandscape.Wandscape;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/** Town Hall — colony center. Category: basic. */
public class TownHallBE extends AbstractWandscapeBE {
    public static final String TYPE_ID = "town_hall";

    /** BlockEntitySupplier-compatible 2-arg constructor. Uses the DeferredHolder. */
    public TownHallBE(BlockPos pos, BlockState blockState) {
        this(Wandscape.TOWN_HALL_BE.get(), pos, blockState);
    }

    /** Standard MC 3-arg constructor. */
    public TownHallBE(BlockEntityType<?> type, BlockPos pos, BlockState blockState) {
        super(type, pos, blockState);
    }

    @Override
    protected String getBuildingTypeId() {
        return TYPE_ID;
    }
}
