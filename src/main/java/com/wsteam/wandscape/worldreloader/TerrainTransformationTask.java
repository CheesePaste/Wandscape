package com.wsteam.wandscape.worldreloader;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Standard mode terrain transformation task.
 * Performs a complete 3D terrain rebuild and copy, with height translation,
 * beacon pyramid preservation, and smooth edge blending transitions.
 */
public class TerrainTransformationTask extends WorldReloaderTask {

    private final int paddingCount;
    private final int yMin;
    private final int yMax;

    public TerrainTransformationTask(ServerLevel world, BlockPos center, BlockPos referenceCenter,
                                     @Nullable Player player, ServerLevel targetDimensionWorld,
                                     int maxRadius, int totalSteps, int itemCleanupInterval,
                                     boolean isChangeBiome, boolean preserveBeacon,
                                     int paddingCount, int yMin, int yMax) {
        super(world, center, referenceCenter, player, targetDimensionWorld,
                maxRadius, totalSteps, itemCleanupInterval, isChangeBiome, preserveBeacon);
        this.paddingCount = paddingCount;
        this.yMin = yMin;
        this.yMax = yMax;
    }

    @Override
    protected void processPosition(BlockPos circlePos) {
        int targetX = circlePos.getX();
        int targetZ = circlePos.getZ();
        int offsetX = targetX - center.getX();
        int offsetZ = targetZ - center.getZ();
        int referenceX = referenceCenter.getX() + offsetX;
        int referenceZ = referenceCenter.getZ() + offsetZ;

        if (!ensureColumnChunksLoaded(targetX, targetZ, referenceX, referenceZ)) {
            return;
        }

        int originalSurfaceY = world.getChunk(targetX >> 4, targetZ >> 4)
                .getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, targetX & 15, targetZ & 15);

        ReferenceTerrainInfo referenceInfo = getReferenceTerrainInfo(referenceX, referenceZ);
        if (referenceInfo != null) {
            destroyAtPosition(targetX, targetZ);
            copyFromReference(targetX, targetZ, referenceInfo, originalSurfaceY);
        }
    }

    @Override
    protected ReferenceTerrainInfo getReferenceTerrainInfo(int referenceX, int referenceZ) {
        if (!targetDimensionWorld.isLoaded(new BlockPos(referenceX, 64, referenceZ))) {
            if (!ensureChunkLoaded(targetDimensionWorld, referenceX >> 4, referenceZ >> 4)) {
                return null;
            }
        }

        int referenceSurfaceY = targetDimensionWorld.getChunk(referenceX >> 4, referenceZ >> 4)
                .getHeight(Heightmap.Types.MOTION_BLOCKING, referenceX & 15, referenceZ & 15);

        if (referenceSurfaceY < yMin - 1) {
            return null;
        }

        referenceSurfaceY = validateAndAdjustReferenceHeight(referenceX, referenceZ, referenceSurfaceY);
        return analyzeReferenceTerrain(referenceX, referenceZ, referenceSurfaceY);
    }

    @Override
    protected void copyFromReference(int targetX, int targetZ, ReferenceTerrainInfo referenceInfo) {
        copyFromReference(targetX, targetZ, referenceInfo, 0);
    }

    protected void copyFromReference(int targetX, int targetZ, ReferenceTerrainInfo referenceInfo, int originalSurfaceY) {
        copyTerrainStructure(targetX, targetZ, referenceInfo, originalSurfaceY);
    }

    @Override
    protected boolean shouldSkipProcessing(int referenceSurfaceYAtTarget, int originalSurfaceY) {
        return false;
    }

    private void destroyAtPosition(int targetX, int targetZ) {
        int surfaceY = world.getChunk(targetX >> 4, targetZ >> 4)
                .getHeight(Heightmap.Types.WORLD_SURFACE, targetX & 15, targetZ & 15);

        if (surfaceY < yMin - 1) {
            return;
        }

        for (int y = yMin; y <= surfaceY + yMax; y++) {
            BlockPos targetPos = new BlockPos(targetX, y, targetZ);
            if (currentRadius <= 8 && shouldPreserveCenterArea(targetPos)) {
                continue;
            }
            if (y > referenceCenter.getY() + yMax) {
                continue;
            }
            BlockState currentState = world.getBlockState(targetPos);
            if (!currentState.isAir()) {
                world.setBlock(targetPos, Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }

    private int validateAndAdjustReferenceHeight(int x, int z, int initialHeight) {
        int currentY = initialHeight;
        while (currentY > minY + 10) {
            BlockPos pos = new BlockPos(x, currentY, z);
            BlockState state = targetDimensionWorld.getBlockState(pos);
            if (isSolidBlock(targetDimensionWorld, state)) {
                return currentY;
            }
            currentY--;
        }
        return initialHeight;
    }

    private ReferenceTerrainInfo analyzeReferenceTerrain(int x, int z, int surfaceY) {
        ReferenceTerrainInfo info = new ReferenceTerrainInfo();
        info.surfaceY = surfaceY;

        List<BlockState> blocks = new ArrayList<>();
        List<Integer> heights = new ArrayList<>();

        for (int y = yMin; y <= surfaceY + yMax; y++) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = targetDimensionWorld.getBlockState(pos);
            if (!state.isAir() || y <= surfaceY) {
                blocks.add(state);
                heights.add(y);
            }
        }

        info.blocks = blocks.toArray(new BlockState[0]);
        info.heights = heights.stream().mapToInt(Integer::intValue).toArray();

        BlockPos abovePos = new BlockPos(x, surfaceY + 1, z);
        BlockState aboveState = targetDimensionWorld.getBlockState(abovePos);
        if (!aboveState.isAir()) {
            info.aboveSurfaceBlocks = new BlockState[]{aboveState};
            info.aboveSurfaceHeights = new int[]{surfaceY + 1};
        }

        return info;
    }

    private void copyTerrainStructure(int targetX, int targetZ, ReferenceTerrainInfo reference, int originalSurfaceY) {
        if (reference.blocks != null && reference.heights.length != 0) {
            if (currentRadius <= 8) {
                copyWithCenterPreservation(targetX, targetZ, reference);
            } else if (currentRadius < maxRadius - paddingCount) {
                copyWithoutPreservation(targetX, targetZ, reference);
            } else {
                applyPaddingTransition(targetX, targetZ, reference, originalSurfaceY);
            }
        }
    }

    private void copyWithCenterPreservation(int targetX, int targetZ, ReferenceTerrainInfo reference) {
        for (int i = 0; i < reference.blocks.length; i++) {
            int targetY = reference.heights[i] + center.getY() - this.referenceCenter.getY();
            if (targetY > referenceCenter.getY() + yMax) {
                continue;
            }
            BlockPos targetPos = new BlockPos(targetX, targetY, targetZ);
            BlockState referenceState = reference.blocks[i];

            if (shouldPreserveCenterArea(targetPos)) {
                continue;
            }

            if (!referenceState.isAir()) {
                BlockState currentState = world.getBlockState(targetPos);
                if (!currentState.equals(referenceState)) {
                    world.setBlock(targetPos, referenceState, 3);
                }
            }
        }
    }

    private void copyWithoutPreservation(int targetX, int targetZ, ReferenceTerrainInfo reference) {
        for (int i = 0; i < reference.blocks.length; i++) {
            int targetY = reference.heights[i] + center.getY() - this.referenceCenter.getY();
            if (targetY > referenceCenter.getY() + yMax) {
                continue;
            }
            BlockPos targetPos = new BlockPos(targetX, targetY, targetZ);
            BlockState referenceState = reference.blocks[i];

            if (!referenceState.isAir()) {
                BlockState currentState = world.getBlockState(targetPos);
                if (!currentState.equals(referenceState)) {
                    world.setBlock(targetPos, referenceState, 3);
                }
            }
        }
    }

    private void applyPaddingTransition(int targetX, int targetZ, ReferenceTerrainInfo reference, int originalSurfaceY) {
        float progress = 1.0f - (float) (maxRadius - currentRadius) / Math.max(1, paddingCount);
        int referenceTargetY = reference.surfaceY + center.getY() - this.referenceCenter.getY();
        int transitionSurfaceY = (int) (referenceTargetY + (originalSurfaceY - referenceTargetY) * progress);

        for (int i = 0; i < reference.blocks.length; i++) {
            int referenceY = reference.heights[i];
            int targetY = referenceY + center.getY() - this.referenceCenter.getY();
            int transitionY = calculateTransitionHeight(referenceY, reference.surfaceY, targetY, transitionSurfaceY, progress);

            BlockPos targetPos = new BlockPos(targetX, transitionY, targetZ);
            if (isSolidBlock(world, reference.blocks[i])) {
                BlockState referenceState = reference.blocks[i];
                if (!referenceState.isAir()) {
                    BlockState currentState = world.getBlockState(targetPos);
                    if (!currentState.equals(referenceState)) {
                        world.setBlock(targetPos, referenceState, 3);
                    }
                }
            } else if (!reference.blocks[i].getFluidState().isEmpty()) {
                world.setBlock(new BlockPos(targetX, targetY, targetZ), reference.blocks[i], 3);
            }
        }
        cleanFloatingBlocks(targetX, targetZ, transitionSurfaceY);
    }

    private int calculateTransitionHeight(int referenceY, int referenceSurfaceY, int targetY, int transitionSurfaceY, float progress) {
        if (referenceY > referenceSurfaceY) {
            int heightAboveSurface = referenceY - referenceSurfaceY;
            return transitionSurfaceY + heightAboveSurface;
        }
        int depthBelowSurface = referenceSurfaceY - referenceY;
        return transitionSurfaceY - depthBelowSurface;
    }

    private void cleanFloatingBlocks(int targetX, int targetZ, int transitionSurfaceY) {
        for (int y = transitionSurfaceY + 10; y > transitionSurfaceY; y--) {
            BlockPos pos = new BlockPos(targetX, y, targetZ);
            BlockState state = world.getBlockState(pos);
            if (!state.isAir() && isSolidBlock(world, state) && y > transitionSurfaceY + 2) {
                boolean hasSupport = false;
                for (int checkY = y - 1; checkY >= transitionSurfaceY; checkY--) {
                    BlockPos belowPos = new BlockPos(targetX, checkY, targetZ);
                    BlockState belowState = world.getBlockState(belowPos);
                    if (isSolidBlock(world, belowState)) {
                        hasSupport = true;
                        break;
                    }
                }
                if (!hasSupport) {
                    world.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
    }
}
