package com.wsteam.wandscape.worldreloader;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.datafixers.util.Either;
import com.wsteam.wandscape.shared.log.Log;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.commands.FillBiomeCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Base abstract task for asynchronous terrain and biome transformation.
 * Expands outward in concentric rings, managing chunk loading, block manipulation,
 * biome updates, and item entity cleanup.
 */
public abstract class WorldReloaderTask {

    private static final String TAG = "WorldReloader";

    protected final ServerLevel world;
    protected final BlockPos center;
    protected final BlockPos referenceCenter;
    @Nullable
    protected final Player player;
    protected final ServerLevel targetDimensionWorld;

    protected final Set<ChunkPos> forcedTargetChunks = new HashSet<>();
    protected final Set<ChunkPos> forcedReferenceChunks = new HashSet<>();

    protected int currentRadius = 0;
    protected final int maxRadius;
    protected boolean isActive = false;
    protected boolean isInit = false;

    protected final int totalSteps;
    protected int currentStep = 0;

    protected final int minY;
    protected final int itemCleanupInterval;
    protected int lastCleanupRadius = -1;
    protected final boolean isChangeBiome;
    protected final boolean preserveBeacon;

    protected List<BlockPos> currentRadiusPositions = new ArrayList<>();

    protected WorldReloaderTask(ServerLevel world, BlockPos center, BlockPos referenceCenter,
                               @Nullable Player player, ServerLevel targetDimensionWorld,
                               int maxRadius, int totalSteps, int itemCleanupInterval,
                               boolean isChangeBiome, boolean preserveBeacon) {
        this.world = world;
        this.center = center;
        this.referenceCenter = referenceCenter;
        this.player = player;
        this.targetDimensionWorld = targetDimensionWorld;
        this.minY = world != null ? world.getMinBuildHeight() : -64;
        this.maxRadius = maxRadius;
        this.totalSteps = Math.max(1, totalSteps);
        this.itemCleanupInterval = itemCleanupInterval;
        this.isChangeBiome = isChangeBiome;
        this.preserveBeacon = preserveBeacon;
    }

    public void start() {
        this.isActive = true;
        Log.info(TAG, "Starting terrain transformation task: center={}, refCenter={}, radius={}",
                center, referenceCenter, maxRadius);
    }

    public void stop() {
        this.isActive = false;
        cleanupChunkForcing();
        currentRadiusPositions.clear();
        currentStep = 0;
        lastCleanupRadius = -1;
        if (player != null) {
            player.sendSystemMessage(Component.literal("§a[WorldReloader] 地形改造任务已结束"));
        }
        Log.info(TAG, "Terrain transformation task stopped: center={}", center);
    }

    public boolean isActive() {
        return this.isActive;
    }

    public void tick() {
        if (!this.isActive) {
            if (isInit) {
                cleanupChunkForcing();
            }
            return;
        }

        handleChunkForcing();
        processNextStep();
    }

    protected abstract void processPosition(BlockPos circlePos);
    protected abstract ReferenceTerrainInfo getReferenceTerrainInfo(int referenceX, int referenceZ);
    protected abstract void copyFromReference(int targetX, int targetZ, ReferenceTerrainInfo referenceInfo);
    protected abstract boolean shouldSkipProcessing(int referenceSurfaceYAtTarget, int originalSurfaceY);

    protected void handleChunkForcing() {
        if (!isInit) {
            int chunkRadius = (maxRadius + 15) >> 4;
            for (int x = -chunkRadius; x <= chunkRadius; x++) {
                for (int z = -chunkRadius; z <= chunkRadius; z++) {
                    ChunkPos targetChunkPos = new ChunkPos((center.getX() >> 4) + x, (center.getZ() >> 4) + z);
                    ChunkPos referenceChunkPos = new ChunkPos((referenceCenter.getX() >> 4) + x, (referenceCenter.getZ() >> 4) + z);

                    if (!forcedTargetChunks.contains(targetChunkPos)) {
                        forceChunk(world, targetChunkPos);
                        forcedTargetChunks.add(targetChunkPos);
                    }

                    if (targetDimensionWorld != null && !forcedReferenceChunks.contains(referenceChunkPos)) {
                        forceChunk(targetDimensionWorld, referenceChunkPos);
                        forcedReferenceChunks.add(referenceChunkPos);
                    }

                    if (isChangeBiome && targetDimensionWorld != null) {
                        Holder<Biome> bb = getBiomeAtChunkCenter(targetDimensionWorld, referenceChunkPos);
                        setBiome(center.offset(16 * x, 0, 16 * z), bb, world);
                    }
                }
            }
            isInit = true;
        }
    }

    protected boolean ensureChunkLoaded(ServerLevel serverWorld, int chunkX, int chunkZ) {
        if (serverWorld == null) {
            return false;
        }
        serverWorld.getChunk(chunkX, chunkZ);
        return serverWorld.isLoaded(new BlockPos(chunkX << 4, 64, chunkZ << 4));
    }

    private void forceChunk(ServerLevel serverWorld, ChunkPos chunkPos) {
        serverWorld.setChunkForced(chunkPos.x, chunkPos.z, true);
        serverWorld.getChunk(chunkPos.x, chunkPos.z);
    }

    protected boolean ensureColumnChunksLoaded(int targetX, int targetZ, int referenceX, int referenceZ) {
        return ensureChunkLoaded(world, targetX >> 4, targetZ >> 4)
                && ensureChunkLoaded(targetDimensionWorld, referenceX >> 4, referenceZ >> 4);
    }

    protected void cleanupChunkForcing() {
        if (targetDimensionWorld != null) {
            for (ChunkPos chunkPos : forcedReferenceChunks) {
                targetDimensionWorld.setChunkForced(chunkPos.x, chunkPos.z, false);
            }
        }
        for (ChunkPos chunkPos : forcedTargetChunks) {
            world.setChunkForced(chunkPos.x, chunkPos.z, false);
        }
        forcedReferenceChunks.clear();
        forcedTargetChunks.clear();
        isInit = false;
    }

    public static Holder<Biome> getBiomeAtChunkCenter(Level world, ChunkPos chunkPos) {
        int centerX = chunkPos.getMinBlockX() + 8;
        int centerZ = chunkPos.getMinBlockZ() + 8;
        int topY = world.getHeight(Heightmap.Types.MOTION_BLOCKING, centerX, centerZ);
        BlockPos topPos = new BlockPos(centerX, topY, centerZ);
        return world.getBiome(topPos);
    }

    protected void processNextStep() {
        if (currentRadius > maxRadius) {
            if (player != null) {
                player.sendSystemMessage(Component.literal("§6[WorldReloader] 地形改造完成！"));
            }
            stop();
            return;
        }

        if (currentStep == 0) {
            currentRadiusPositions = generateCirclePositions(currentRadius);
        }

        if (shouldCleanupItems()) {
            cleanupItemEntities();
            lastCleanupRadius = currentRadius;
        }

        if (!processCurrentStepPositions()) {
            return;
        }

        currentStep++;

        if (currentStep >= totalSteps) {
            currentRadius++;
            currentStep = 0;
        }
    }

    protected boolean shouldCleanupItems() {
        return currentRadius % itemCleanupInterval == 0 && currentRadius != lastCleanupRadius;
    }

    protected boolean processCurrentStepPositions() {
        int totalPositions = currentRadiusPositions.size();
        if (totalPositions == 0) {
            currentRadius++;
            currentStep = 0;
            return false;
        }

        int positionsPerStep = (totalPositions + totalSteps - 1) / totalSteps;
        int startIndex = currentStep * positionsPerStep;
        int endIndex = Math.min(startIndex + positionsPerStep, totalPositions);

        if (startIndex >= totalPositions) {
            currentRadius++;
            currentStep = 0;
            return false;
        }

        List<BlockPos> stepPositions = currentRadiusPositions.subList(startIndex, endIndex);
        for (BlockPos pos : stepPositions) {
            processPosition(pos);
        }
        return true;
    }

    public List<BlockPos> generateCirclePositions(int radius) {
        List<BlockPos> positions = new ArrayList<>();

        if (radius == 0) {
            positions.add(new BlockPos(center.getX(), 0, center.getZ()));
            return positions;
        }

        int radiusSquared = radius * radius;
        int prevRadiusSquared = (radius - 1) * (radius - 1);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int distanceSquared = dx * dx + dz * dz;
                if (distanceSquared <= radiusSquared && distanceSquared > prevRadiusSquared) {
                    int x = center.getX() + dx;
                    int z = center.getZ() + dz;
                    positions.add(new BlockPos(x, 0, z));
                }
            }
        }
        return positions;
    }

    protected void cleanupItemEntities() {
        int itemsCleared = 0;
        int cleanupRadius = Math.min(currentRadius + 10, maxRadius + 20);

        List<ItemEntity> itemsToRemove = world.getEntitiesOfClass(
                ItemEntity.class,
                getCleanupBoundingBox(cleanupRadius),
                entity -> true);

        for (ItemEntity item : itemsToRemove) {
            item.discard();
            itemsCleared++;
        }

        if (itemsCleared > 0 && player != null) {
            player.sendSystemMessage(Component.literal("§b[WorldReloader] 清理了 " + itemsCleared + " 个掉落物（半径 " + currentRadius + "）"));
        }
    }

    protected AABB getCleanupBoundingBox(int radius) {
        int minX = center.getX() - radius;
        int minBoxY = this.minY;
        int minZ = center.getZ() - radius;
        int maxX = center.getX() + radius;
        int maxBoxY = 320;
        int maxZ = center.getZ() + radius;
        return new AABB(minX, minBoxY, minZ, maxX, maxBoxY, maxZ);
    }

    protected boolean shouldPreserveCenterArea(BlockPos pos) {
        if (!preserveBeacon) {
            return false;
        }
        return shouldPreserveCenterAreaStatic(pos, center);
    }

    public static boolean shouldPreserveCenterAreaStatic(BlockPos pos, BlockPos center) {
        if (pos == null || center == null) return false;
        for (int i = 0; i <= 4; i++) {
            if (pos.getY() == center.getY() - i &&
                    Math.abs(pos.getX() - center.getX()) <= i &&
                    Math.abs(pos.getZ() - center.getZ()) <= i) {
                return true;
            }
        }
        return false;
    }

    public static int validateAndAdjustHeight(Level world, int x, int z, int initialHeight, int minY) {
        int currentY = initialHeight;
        int solidGroundCount = 0;

        while (currentY > minY + 10) {
            BlockPos pos = new BlockPos(x, currentY, z);
            BlockState state = world.getBlockState(pos);

            if (isSolidBlock(world, state)) {
                solidGroundCount++;
                if (solidGroundCount >= 3) {
                    return currentY + 2;
                }
            } else {
                solidGroundCount = 0;
            }
            currentY--;
        }
        return initialHeight;
    }

    public static ReferenceTerrainInfo analyzeTerrain(Level world, int x, int z, int surfaceY, int minY, int copyDepth, int copyHeight) {
        ReferenceTerrainInfo info = new ReferenceTerrainInfo();
        info.surfaceY = surfaceY;

        int startY = Math.max(minY, surfaceY - copyDepth);
        int endY = surfaceY + copyHeight;

        List<BlockState> blocks = new ArrayList<>();
        List<Integer> heights = new ArrayList<>();

        for (int y = startY; y <= endY; y++) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = world.getBlockState(pos);
            if (!state.isAir() || y <= surfaceY) {
                blocks.add(state);
                heights.add(y);
            }
        }

        info.blocks = blocks.toArray(new BlockState[0]);
        info.heights = heights.stream().mapToInt(Integer::intValue).toArray();

        List<BlockState> aboveBlocks = new ArrayList<>();
        List<Integer> aboveHeights = new ArrayList<>();

        for (int y = surfaceY + 1; y <= surfaceY + 15; y++) {
            BlockPos abovePos = new BlockPos(x, y, z);
            BlockState aboveState = world.getBlockState(abovePos);
            if (!aboveState.isAir()) {
                aboveBlocks.add(aboveState);
                aboveHeights.add(y);
            }
        }

        if (!aboveBlocks.isEmpty()) {
            info.aboveSurfaceBlocks = aboveBlocks.toArray(new BlockState[0]);
            info.aboveSurfaceHeights = aboveHeights.stream().mapToInt(Integer::intValue).toArray();
        }

        return info;
    }

    public static void setBiome(BlockPos pos, Holder<Biome> biome, ServerLevel serverWorld) {
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        BlockPos chunkStartPos = new BlockPos(chunkX << 4, serverWorld.getMinBuildHeight(), chunkZ << 4);
        BlockPos chunkEndPos = new BlockPos(
                chunkStartPos.getX() + 15,
                serverWorld.getMaxBuildHeight(),
                chunkStartPos.getZ() + 15
        );

        int worldBottomY = serverWorld.getMinBuildHeight();
        int worldTopY = serverWorld.getMaxBuildHeight();

        int maxBlocksPerCall = 32768;
        int maxHeightPerCall = maxBlocksPerCall / 256;

        for (int startY = worldBottomY; startY < worldTopY; startY += maxHeightPerCall) {
            int endY = Math.min(startY + maxHeightPerCall - 1, worldTopY - 1);

            BlockPos layerStartPos = new BlockPos(chunkStartPos.getX(), startY, chunkStartPos.getZ());
            BlockPos layerEndPos = new BlockPos(chunkEndPos.getX(), endY, chunkEndPos.getZ());

            Either<Integer, CommandSyntaxException> either = FillBiomeCommand.fill(
                    serverWorld,
                    layerStartPos,
                    layerEndPos,
                    biome
            );

            if (either.right().isPresent()) {
                CommandSyntaxException error = either.right().get();
                Log.warn(TAG, "Failed to fill biome at layer {}-{}: {}", startY, endY, error.getMessage());
            }
        }
    }

    public static class ReferenceTerrainInfo {
        public int surfaceY;
        public BlockState[] blocks;
        public int[] heights;
        public BlockState[] aboveSurfaceBlocks;
        public int[] aboveSurfaceHeights;
    }

    public static boolean isSolidBlock(Level world, BlockState state) {
        return state.isSolid() && !isWaterOrPlant(state);
    }

    public static boolean isWaterOrPlant(BlockState state) {
        Block block = state.getBlock();

        if (!state.getFluidState().isEmpty() || block == Blocks.BUBBLE_COLUMN || block == Blocks.CONDUIT) {
            return true;
        }
        String blockName = block.getDescriptionId().toLowerCase();
        if (blockName.contains("coral") &&
                (blockName.contains("fan") || blockName.contains("block") || blockName.contains("wall"))) {
            return true;
        }

        return state.is(BlockTags.REPLACEABLE) ||
                state.is(BlockTags.LEAVES) ||
                state.is(BlockTags.FLOWERS) ||
                state.is(BlockTags.CROPS) ||
                state.isAir() ||
                state.is(BlockTags.LOGS) ||
                state.is(Blocks.MUSHROOM_STEM) ||
                state.is(Blocks.BROWN_MUSHROOM_BLOCK) ||
                state.is(Blocks.RED_MUSHROOM_BLOCK);
    }
}
