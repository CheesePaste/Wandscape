package com.wsteam.wandscape.building.block;

import java.util.Map;

import javax.annotation.Nullable;

import com.mojang.serialization.MapCodec;
import com.wsteam.wandscape.building.be.AbstractWandscapeBE;
import com.wsteam.wandscape.shared.data.WorkItem;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

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
        return null;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                Player player, BlockHitResult hitResult) {
        if (level.isClientSide) return InteractionResult.SUCCESS;

        // Stage 1 debug: right-click enqueues a build task for this building itself
        // (reads blueprint auto-registered from BuildingConfig JSON).
        // In later stages, this opens the building GUI.
        if (!(level.getBlockEntity(pos) instanceof AbstractWandscapeBE be)) {
            return InteractionResult.FAIL;
        }

        String bpId = "build:" + buildingTypeId;
        WorkItem demo = new WorkItem(
                bpId,
                Map.of("x", String.valueOf(pos.getX()),
                       "y", String.valueOf(pos.getY()),
                       "z", String.valueOf(pos.getZ())),
                10 // V1: below 50 to skip PENDING_APPROVAL (no approval UI yet)
        );
        be.enqueueWork(demo);
        player.displayClientMessage(
                net.minecraft.network.chat.Component.literal(
                        "[Wandscape] Enqueued demo: " + demo.blueprintId()
                        + " at (x=" + pos.getX() + ", y=" + pos.getY()
                        + ", z=" + pos.getZ() + ")"),
                false);
        return InteractionResult.SUCCESS;
    }
}
