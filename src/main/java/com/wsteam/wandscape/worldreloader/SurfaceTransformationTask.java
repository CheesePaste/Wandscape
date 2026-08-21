package com.wsteam.wandscape.worldreloader;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;

/**
 * Surface mode terrain transformation task.
 * Focuses on replacing the immediate surface layers (configurable depth and height)
 * from the reference terrain, ensuring efficient updates for landscapes without altering deep caves.
 */
public class SurfaceTransformationTask extends WorldReloaderTask {

    private final int depth;
    private final int height;

    public SurfaceTransformationTask(ServerLevel world, BlockPos center, BlockPos referenceCenter,
                                     @Nullable Player player, ServerLevel targetDimensionWorld,
                                     int maxRadius, int totalSteps, int itemCleanupInterval,
                                     boolean isChangeBiome, boolean preserveBeacon,
                                     int depth, int height) {
        super(world, center, referenceCenter, player, targetDimensionWorld,
                maxRadius, totalSteps, itemCleanupInterval, isChangeBiome, preserveBeacon);
        this.depth = depth;
        this.height = height;
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
        if (referenceInfo == null) {
            return;
        }

        int referenceSurfaceYAtTarget = referenceInfo.surfaceY + center.getY() - this.referenceCenter.getY();

        if (shouldSkipProcessing(referenceSurfaceYAtTarget, originalSurfaceY)) {
            return;
        }

        if (currentRadius <= 8) {
            destroyAtPositionWithPreserve(targetX, targetZ, originalSurfaceY);
            copyTerrainStructureTopDownWithPreserve(targetX, targetZ, referenceInfo);
        } else {
            destroyAtPosition(targetX, targetZ, originalSurfaceY);
            copyTerrainStructureTopDown(targetX, targetZ, referenceInfo);
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

        if (referenceSurfaceY < 19) {
            return null;
        }

        referenceSurfaceY = validateAndAdjustHeight(targetDimensionWorld, referenceX, referenceZ, referenceSurfaceY, minY);
        return analyzeTerrain(targetDimensionWorld, referenceX, referenceZ, referenceSurfaceY, minY, depth, height);
    }

    @Override
    protected void copyFromReference(int targetX, int targetZ, ReferenceTerrainInfo referenceInfo) {
        copyTerrainStructureTopDown(targetX, targetZ, referenceInfo);
    }

    @Override
    protected boolean shouldSkipProcessing(int referenceSurfaceYAtTarget, int originalSurfaceY) {
        return referenceSurfaceYAtTarget < originalSurfaceY - height;
    }

    private void destroyAtPositionWithPreserve(int targetX, int targetZ, int surfaceY) {
        if (surfaceY < 18) {
            return;
        }

        int startY = Math.max(minY, surfaceY - depth);
        int endY = surfaceY + height;

        for (int y = startY; y <= endY; y++) {
            BlockPos targetPos = new BlockPos(targetX, y, targetZ);
            if (shouldPreserveCenterArea(targetPos)) {
                continue;
            }
            BlockState currentState = world.getBlockState(targetPos);
            if (!currentState.isAir()) {
                world.setBlock(targetPos, Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }

    private void destroyAtPosition(int targetX, int targetZ, int surfaceY) {
        if (surfaceY < 18) {
            return;
        }

        int startY = Math.max(minY, surfaceY - depth);
        int endY = surfaceY + height;

        for (int y = startY; y <= endY; y++) {
            if (y > center.getY() + height) {
                continue;
            }
            BlockPos targetPos = new BlockPos(targetX, y, targetZ);
            BlockState currentState = world.getBlockState(targetPos);
            if (!currentState.isAir()) {
                world.setBlock(targetPos, Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }

    private void copyTerrainStructureTopDownWithPreserve(int targetX, int targetZ, ReferenceTerrainInfo reference) {
        if (reference.aboveSurfaceBlocks != null && reference.aboveSurfaceHeights != null) {
            for (int i = reference.aboveSurfaceBlocks.length - 1; i >= 0; i--) {
                int targetY = reference.aboveSurfaceHeights[i] + center.getY() - this.referenceCenter.getY();
                BlockPos targetPos = new BlockPos(targetX, targetY, targetZ);
                BlockState referenceState = reference.aboveSurfaceBlocks[i];

                if (shouldPreserveCenterArea(targetPos)) {
                    continue;
                }

                BlockState currentState = world.getBlockState(targetPos);
                if (currentState.isAir() || currentState.canBeReplaced()) {
                    world.setBlock(targetPos, referenceState, 3);
                }
            }
        }

        if (reference.blocks != null && reference.heights.length != 0) {
            for (int i = reference.blocks.length - 1; i >= 0; i--) {
                int targetY = reference.heights[i] + center.getY() - this.referenceCenter.getY();
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
    }

    private void copyTerrainStructureTopDown(int targetX, int targetZ, ReferenceTerrainInfo reference) {
        if (reference.aboveSurfaceBlocks != null && reference.aboveSurfaceHeights != null) {
            for (int i = reference.aboveSurfaceBlocks.length - 1; i >= 0; i--) {
                int targetY = reference.aboveSurfaceHeights[i] + center.getY() - this.referenceCenter.getY();
                BlockPos targetPos = new BlockPos(targetX, targetY, targetZ);
                BlockState referenceState = reference.aboveSurfaceBlocks[i];

                BlockState currentState = world.getBlockState(targetPos);
                if (currentState.isAir() || currentState.canBeReplaced()) {
                    world.setBlock(targetPos, referenceState, 3);
                }
            }
        }

        if (reference.blocks != null && reference.heights.length != 0) {
            for (int i = reference.blocks.length - 1; i >= 0; i--) {
                int targetY = reference.heights[i] + center.getY() - this.referenceCenter.getY();
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
    }
}
