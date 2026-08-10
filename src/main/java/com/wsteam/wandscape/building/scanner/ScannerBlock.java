package com.wsteam.wandscape.building.scanner;

import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.wsteam.wandscape.building.scanner.client.ScannerScreen;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Survival Building Scanner block — simplified counterpart of the Creative Building Scanner.
 * Opens {@link ScannerScreen} which is locked to the {@code custom} building category
 * and only exposes size/door/id/name + export.
 */
public class ScannerBlock extends CreativeScannerBlock {

    public ScannerBlock(Properties properties, Supplier<? extends BlockEntityType<?>> beType) {
        super(properties, beType);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ScannerBlockEntity(beType.get(), pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                Player player, BlockHitResult hitResult) {
        if (level.isClientSide) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof ScannerBlockEntity scanner) {
                Minecraft.getInstance().setScreen(new ScannerScreen(scanner));
            }
        }
        return InteractionResult.SUCCESS;
    }
}
