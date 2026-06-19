package com.wsteam.wandscape.engine.boundary;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.annotation.Nullable;

import com.wsteam.wandscape.core.boundary.BlockOps;
import com.wsteam.wandscape.core.types.BlockType;
import com.wsteam.wandscape.core.types.GridPos;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * MC implementation of {@link BlockOps}.
 * Delegates to the server overworld {@link Level}.
 */
public class WandscapeBlockOps implements BlockOps {

    // Cache block type string → MC Block lookups
    private final ConcurrentMap<String, Block> blockCache = new ConcurrentHashMap<>();

    @Override
    public void setBlock(GridPos pos, BlockType type) {
        Level level = getLevel();
        if (level == null) return;
        Block block = resolveBlock(type);
        if (block != null) {
            BlockPos bp = toBlockPos(pos);
            level.setBlock(bp, block.defaultBlockState(), 3);
        }
    }

    @Override
    public BlockType getBlock(GridPos pos) {
        Level level = getLevel();
        if (level == null) return BlockType.AIR;
        Block block = level.getBlockState(toBlockPos(pos)).getBlock();
        return new BlockType(keyOf(block));
    }

    @Override
    public boolean isAir(GridPos pos) {
        Level level = getLevel();
        if (level == null) return true;
        return level.getBlockState(toBlockPos(pos)).isAir();
    }

    @Override
    public void toggle(GridPos pos) {
        Level level = getLevel();
        if (level == null) return;
        // Delegate to MC's neighbor update mechanism for the next stages
        level.updateNeighborsAt(toBlockPos(pos), level.getBlockState(toBlockPos(pos)).getBlock());
    }

    @Override
    public void activate(GridPos pos) {
        // No-op in stage 1; used by EntityInteractOp later
    }

    @Override
    public void openGui(GridPos pos) {
        // No-op in stage 1; used for UI integration later
    }

    // ---- Helpers ----

    @Nullable
    private Block resolveBlock(BlockType type) {
        return blockCache.computeIfAbsent(type.id(), id -> {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl == null) return null;
            return BuiltInRegistries.BLOCK.getOptional(rl).orElse(null);
        });
    }

    private static String keyOf(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).toString();
    }

    private static BlockPos toBlockPos(GridPos pos) {
        return new BlockPos(pos.x(), pos.y(), pos.z());
    }

    @Nullable
    private static Level getLevel() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return null;
        return server.overworld();
    }
}
