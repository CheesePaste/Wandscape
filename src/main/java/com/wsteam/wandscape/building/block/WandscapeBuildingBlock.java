package com.wsteam.wandscape.building.block;

import javax.annotation.Nullable;

import com.mojang.serialization.MapCodec;
import com.wsteam.wandscape.building.be.AbstractWandscapeBE;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Common block class for all Wandscape buildings.
 * Each building type gets its own registered Block instance
 * with a factory that creates the correct BE subclass.
 */
public class WandscapeBuildingBlock extends BaseEntityBlock {
    private final String buildingTypeId;
    private final BlockEntityType.BlockEntitySupplier<? extends AbstractWandscapeBE> beFactory;

    public WandscapeBuildingBlock(BlockBehaviour.Properties properties, String buildingTypeId,
                                   BlockEntityType.BlockEntitySupplier<? extends AbstractWandscapeBE> beFactory) {
        super(properties);
        this.buildingTypeId = buildingTypeId;
        this.beFactory = beFactory;
    }

    public String getBuildingTypeId() {
        return buildingTypeId;
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        // Each instance captures its own typeId and beFactory in the closure.
        // Codecs are used by structure blocks/commands — normal placement bypasses this.
        return simpleCodec(props -> new WandscapeBuildingBlock(props, this.buildingTypeId, this.beFactory));
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return beFactory.create(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                    BlockEntityType<T> type) {
        // Stage 1: no tick logic. Stage 3+ can add per-BE ticker here.
        return null;
    }
}
