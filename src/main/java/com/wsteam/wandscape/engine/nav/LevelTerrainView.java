package com.wsteam.wandscape.engine.nav;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * {@link StandableTerrain.TerrainView} backed by a real {@link ServerLevel}.
 *
 * <p>This is the only engine-side glue for {@link StandableTerrain}; it keeps the
 * surface / standability decision logic MC-free and unit-testable while the actual
 * block queries are delegated here. All terrain predicates are read-only.
 */
public final class LevelTerrainView implements StandableTerrain.TerrainView {

    private final ServerLevel level;

    public LevelTerrainView(ServerLevel level) {
        this.level = level;
    }

    @Override
    public boolean isLoaded(int x, int y, int z) {
        return level.isLoaded(new BlockPos(x, y, z));
    }

    @Override
    public boolean isBlocking(int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    @Override
    public boolean isLiquid(int x, int y, int z) {
        return !level.getBlockState(new BlockPos(x, y, z)).getFluidState().isEmpty();
    }

    @Override
    public boolean isSolid(int x, int y, int z) {
        return level.getBlockState(new BlockPos(x, y, z)).isSolid();
    }

    @Override
    public int surfaceY(int x, int z) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }
}
