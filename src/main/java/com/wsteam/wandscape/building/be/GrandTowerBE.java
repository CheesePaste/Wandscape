package com.wsteam.wandscape.building.be;

import com.wsteam.wandscape.Wandscape;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/** Grand Mage Tower — large 7×7 tower for stress-testing task distribution. */
public class GrandTowerBE extends AbstractWandscapeBE {
    public static final String TYPE_ID = "grand_tower";

    /** BlockEntitySupplier-compatible 2-arg constructor. */
    public GrandTowerBE(BlockPos pos, BlockState blockState) {
        this(Wandscape.GRAND_TOWER_BE.get(), pos, blockState);
    }

    /** Standard MC 3-arg constructor. */
    public GrandTowerBE(BlockEntityType<?> type, BlockPos pos, BlockState blockState) {
        super(type, pos, blockState);
    }

    @Override
    protected String getBuildingTypeId() {
        return TYPE_ID;
    }
}
