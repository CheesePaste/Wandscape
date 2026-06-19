package com.wsteam.wandscape.building.be;

import com.wsteam.wandscape.Wandscape;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/** Forest node — produces wood element. Category: node. */
public class ForestNodeBE extends AbstractWandscapeBE {
    public static final String TYPE_ID = "forest_node";

    /** BlockEntitySupplier-compatible 2-arg constructor. */
    public ForestNodeBE(BlockPos pos, BlockState blockState) {
        this(Wandscape.FOREST_NODE_BE.get(), pos, blockState);
    }

    /** Standard MC 3-arg constructor. */
    public ForestNodeBE(BlockEntityType<?> type, BlockPos pos, BlockState blockState) {
        super(type, pos, blockState);
    }

    @Override
    protected String getBuildingTypeId() {
        return TYPE_ID;
    }
}
