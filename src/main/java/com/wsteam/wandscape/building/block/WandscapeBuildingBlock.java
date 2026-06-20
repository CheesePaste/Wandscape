package com.wsteam.wandscape.building.block;

import java.util.Map;

import javax.annotation.Nullable;

import com.google.gson.JsonPrimitive;
import com.mojang.serialization.MapCodec;
import com.wsteam.wandscape.building.be.AbstractWandscapeBE;
import com.wsteam.wandscape.building.internal.EnqueueHelper;
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

        if (!(level.getBlockEntity(pos) instanceof AbstractWandscapeBE be)) {
            return InteractionResult.FAIL;
        }

        // Ensure the building is registered with BuildingApi.
        // Needed for command-placed blocks (EntityPlaceEvent doesn't fire for /setblock).
        com.wsteam.wandscape.building.internal.BuildingConfigLoader configLoader =
                com.wsteam.wandscape.building.internal.BuildingConfigLoader.getInstance();
        var config = configLoader.get(buildingTypeId);
        if (config == null) {
            return InteractionResult.FAIL;
        }
        boolean newlyRegistered = com.wsteam.wandscape.building.internal.EnqueueHelper.registerIfAbsent(
                pos, config, buildingTypeId);

        WorkItem demo = buildEnqueueWorkItem(pos);
        if (demo == null) {
            return InteractionResult.FAIL;
        }
        be.enqueueWork(demo);
        String prefix = newlyRegistered ? "[Wandscape] Registered + Enqueued: " : "[Wandscape] Enqueued: ";
        player.displayClientMessage(
                net.minecraft.network.chat.Component.literal(
                        prefix + demo.blueprintId()
                        + " at (x=" + pos.getX() + ", y=" + pos.getY()
                        + ", z=" + pos.getZ() + ")"),
                false);
        return InteractionResult.SUCCESS;
    }

    /**
     * Build a {@link WorkItem} for this building at the given position.
     * Resolves blueprint ref bind + anchor from the building config.
     * Returns null if the building config is missing.
     */
    @Nullable
    public WorkItem buildEnqueueWorkItem(BlockPos pos) {
        com.wsteam.wandscape.building.internal.BuildingConfigLoader configLoader =
                com.wsteam.wandscape.building.internal.BuildingConfigLoader.getInstance();
        var config = configLoader.get(buildingTypeId);
        if (config == null) {
            return null;
        }
        return EnqueueHelper.buildWorkItem(config, pos, buildingTypeId, 10);
    }
}
