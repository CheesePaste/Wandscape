package com.wsteam.wandscape.building.scanner;

import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.wsteam.wandscape.building.scanner.client.ScannerClientHelper;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Building Scanner block — replacement for the ImGui-based building editor.
 * Works like the vanilla Structure Block: place, right-click to open GUI,
 * configure boundary/door/interact zones/meta in modes, export building JSON.
 */
public class CreativeScannerBlock extends Block implements EntityBlock {

    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    protected final Supplier<? extends BlockEntityType<?>> beType;

    public CreativeScannerBlock(Properties properties, Supplier<? extends BlockEntityType<?>> beType) {
        super(properties);
        this.beType = beType;
        registerDefaultState(defaultBlockState().setValue(FACING, net.minecraft.core.Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CreativeScannerBlockEntity(beType.get(), pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                Player player, BlockHitResult hitResult) {
        if (level.isClientSide) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof CreativeScannerBlockEntity scanner) {
                ScannerClientHelper.openCreativeScanner(scanner);
            }
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rot) {
        return state.setValue(FACING, rot.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.setValue(FACING, mirror.mirror(state.getValue(FACING)));
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            // Block destroyed — BE automatically removed
            super.onRemove(state, level, pos, newState, movedByPiston);
        }
    }
}
