package com.wsteam.wandscape.worldreloader;

import com.mojang.datafixers.util.Pair;
import com.wsteam.wandscape.shared.log.Log;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Fluent builder for creating and configuring WorldReloader tasks.
 * Resolves target coordinates via biome search, structure search, random offset, or explicit positions.
 */
public class WorldReloaderBuilder {

    private static final String TAG = "WorldReloaderBuilder";

    private final ServerLevel world;
    @Nullable
    private final Player player;
    private ServerLevel targetDimensionWorld;

    private BlockPos targetPos = null;
    private BlockPos changePos = null;
    private int radius = 64;
    private int padding = 12;
    private boolean isChangeBiome = true;
    private int yMin = 20;
    private int yMax = 30;
    private int steps = 3;
    private int itemCleanupInterval = 20;
    private boolean preserveBeacon = true;

    public WorldReloaderBuilder(ServerLevel world, @Nullable Player player) {
        this.world = world;
        this.player = player;
        this.targetDimensionWorld = world;
    }

    public WorldReloaderBuilder preserveBeacon(boolean b) {
        this.preserveBeacon = b;
        return this;
    }

    public WorldReloaderBuilder setTargetDimension(ServerLevel targetDimensionWorld) {
        if (targetDimensionWorld != null) {
            this.targetDimensionWorld = targetDimensionWorld;
        }
        return this;
    }

    public WorldReloaderBuilder changeBiome(boolean changeBiome) {
        this.isChangeBiome = changeBiome;
        return this;
    }

    public WorldReloaderBuilder setItemCleanupInterval(int itemCleanupInterval) {
        this.itemCleanupInterval = itemCleanupInterval;
        return this;
    }

    public WorldReloaderBuilder setRadius(int r) {
        this.radius = r;
        return this;
    }

    public WorldReloaderBuilder setPadding(int padding) {
        this.padding = padding;
        return this;
    }

    public WorldReloaderBuilder setRandomPos(BlockPos center, int randomRadius) {
        Log.info(TAG, "Searching for random reference position: center={}, randomRadius={}", center, randomRadius);

        for (int i = 0; i < 20; i++) {
            double angle = world.random.nextDouble() * 2 * Math.PI;
            int distance = randomRadius + world.random.nextInt(500);

            int refX = center.getX() + (int) (Math.cos(angle) * distance);
            int refZ = center.getZ() + (int) (Math.sin(angle) * distance);
            BlockPos testPos = new BlockPos(refX, 0, refZ);

            BlockPos surfacePos = getValidSurfacePosition(testPos);
            if (surfacePos != null) {
                Log.info(TAG, "Found valid random position: {}", surfacePos);
                this.targetPos = surfacePos;
                return this;
            }
        }

        Log.warn(TAG, "Random position search failed after 20 attempts");
        return this;
    }

    public WorldReloaderBuilder setBiomePos(BlockPos center, Predicate<Holder<Biome>> targetBiome, int searchRadius) {
        try {
            Pair<BlockPos, Holder<Biome>> pair = targetDimensionWorld.findClosestBiome3d(targetBiome, center, searchRadius, 32, 64);
            if (pair == null) {
                if (player != null) {
                    player.sendSystemMessage(Component.literal("§c[WorldReloader] 未能找到目标生物群系，请尝试扩大搜索半径"));
                }
                return this;
            }

            BlockPos biomePos = pair.getFirst();
            BlockPos surfacePos = getValidSurfacePosition(biomePos);
            if (surfacePos != null) {
                double distance = Math.sqrt(center.distSqr(surfacePos));
                if (player != null) {
                    player.sendSystemMessage(Component.literal(String.format("§a[WorldReloader] 成功找到目标生物群系，距离: %.1f 格", distance)));
                }
                this.targetPos = surfacePos;
                return this;
            }

            surfacePos = findAlternativeBiomePosition(biomePos, targetBiome);
            if (surfacePos != null) {
                double distance = Math.sqrt(center.distSqr(surfacePos));
                if (player != null) {
                    player.sendSystemMessage(Component.literal(String.format("§a[WorldReloader] 成功在替代位置找到目标生物群系，距离: %.1f 格", distance)));
                }
                this.targetPos = surfacePos;
                return this;
            }
        } catch (Exception e) {
            Log.error(TAG, "Error finding biome: {}", e.getMessage());
            if (player != null) {
                player.sendSystemMessage(Component.literal("§c[WorldReloader] 查找生物群系出错: " + e.getMessage()));
            }
        }
        return this;
    }

    public WorldReloaderBuilder setStructurePos(BlockPos center, String structureIdOrTag, int searchRadius) {
        try {
            Registry<Structure> registry = targetDimensionWorld.registryAccess().registryOrThrow(Registries.STRUCTURE);
            HolderSet<Structure> holderSet = null;

            if (structureIdOrTag.startsWith("#")) {
                ResourceLocation tagLoc = ResourceLocation.parse(structureIdOrTag.substring(1));
                TagKey<Structure> tagKey = TagKey.create(Registries.STRUCTURE, tagLoc);
                Optional<HolderSet.Named<Structure>> tagHolders = registry.getTag(tagKey);
                if (tagHolders.isPresent()) {
                    holderSet = tagHolders.get();
                }
            } else {
                ResourceLocation loc = ResourceLocation.parse(WorldReloaderConfig.normalizeId(structureIdOrTag));
                ResourceKey<Structure> key = ResourceKey.create(Registries.STRUCTURE, loc);
                Optional<Holder.Reference<Structure>> holder = registry.getHolder(key);
                if (holder.isPresent()) {
                    holderSet = HolderSet.direct(holder.get());
                }
            }

            if (holderSet == null) {
                if (player != null) {
                    player.sendSystemMessage(Component.literal("§c[WorldReloader] 未找到结构定义: " + structureIdOrTag));
                }
                return this;
            }

            Pair<BlockPos, Holder<Structure>> pair = targetDimensionWorld.getChunkSource()
                    .getGenerator()
                    .findNearestMapStructure(targetDimensionWorld, holderSet, center, searchRadius / 16, false);

            if (pair != null) {
                BlockPos structurePos = pair.getFirst();
                BlockPos surfacePos = getValidSurfacePosition(structurePos);
                if (surfacePos != null) {
                    double distance = Math.sqrt(center.distSqr(surfacePos));
                    if (player != null) {
                        player.sendSystemMessage(Component.literal(String.format("§a[WorldReloader] 成功找到目标结构，距离: %.1f 格", distance)));
                    }
                    this.targetPos = surfacePos;
                    return this;
                }
            } else {
                if (player != null) {
                    player.sendSystemMessage(Component.literal("§c[WorldReloader] 未能在范围内找到目标结构: " + structureIdOrTag));
                }
            }
        } catch (Exception e) {
            Log.error(TAG, "Error finding structure: {}", e.getMessage());
            if (player != null) {
                player.sendSystemMessage(Component.literal("§c[WorldReloader] 查找结构出错: " + e.getMessage()));
            }
        }
        return this;
    }

    public WorldReloaderBuilder setTargetPos(int posX, int posY, int posZ) {
        this.targetPos = new BlockPos(posX, posY, posZ);
        return this;
    }

    public WorldReloaderBuilder setTargetPos(BlockPos pos) {
        this.targetPos = pos;
        return this;
    }

    public WorldReloaderBuilder setChangePos(int posX, int posY, int posZ) {
        this.changePos = new BlockPos(posX, posY, posZ);
        return this;
    }

    public WorldReloaderBuilder setChangePos(BlockPos pos) {
        this.changePos = pos;
        return this;
    }

    public WorldReloaderBuilder setSteps(int steps) {
        this.steps = steps;
        return this;
    }

    public WorldReloaderBuilder setYMin(int yMin) {
        this.yMin = yMin;
        return this;
    }

    public WorldReloaderBuilder setYMax(int yMax) {
        this.yMax = yMax;
        return this;
    }

    public TerrainTransformationTask buildStandard() {
        if (targetDimensionWorld == null) {
            targetDimensionWorld = world;
        }
        if (isValidated()) {
            return new TerrainTransformationTask(
                    world, changePos, targetPos, player, targetDimensionWorld,
                    radius, steps, itemCleanupInterval, isChangeBiome, preserveBeacon,
                    padding, yMin, yMax);
        }
        Log.error(TAG, "Failed to build Standard TerrainTransformationTask — invalid parameters (radius={}, targetPos={}, changePos={})",
                radius, targetPos, changePos);
        return null;
    }

    public SurfaceTransformationTask buildSurface() {
        if (targetDimensionWorld == null) {
            targetDimensionWorld = world;
        }
        if (isValidated()) {
            return new SurfaceTransformationTask(
                    world, changePos, targetPos, player, targetDimensionWorld,
                    radius, steps, itemCleanupInterval, isChangeBiome, preserveBeacon,
                    yMin, yMax);
        }
        Log.error(TAG, "Failed to build SurfaceTransformationTask — invalid parameters (radius={}, targetPos={}, changePos={})",
                radius, targetPos, changePos);
        return null;
    }

    public boolean isValidated() {
        return radius > 0 && targetPos != null && changePos != null;
    }

    public BlockPos getTargetPos() {
        return targetPos;
    }

    public BlockPos getChangePos() {
        return changePos;
    }

    private BlockPos getValidSurfacePosition(BlockPos pos) {
        ServerLevel targetLvl = targetDimensionWorld != null ? targetDimensionWorld : world;
        ChunkPos chunkPos = new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4);

        boolean forcedHere = false;
        if (!targetLvl.isLoaded(new BlockPos(chunkPos.getMinBlockX(), 64, chunkPos.getMinBlockZ()))) {
            targetLvl.setChunkForced(chunkPos.x, chunkPos.z, true);
            forcedHere = true;
        }

        try {
            targetLvl.getChunk(chunkPos.x, chunkPos.z);
            int surfaceY = targetLvl.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ());
            surfaceY = validateAndAdjustSurfaceHeight(pos.getX(), pos.getZ(), surfaceY, targetLvl);

            BlockPos surfacePos = new BlockPos(pos.getX(), surfaceY, pos.getZ());
            BlockState surfaceBlock = targetLvl.getBlockState(surfacePos);

            if (WorldReloaderTask.isSolidBlock(targetLvl, surfaceBlock) && surfacePos.getY() >= 64) {
                return surfacePos;
            }
        } finally {
            if (forcedHere) {
                targetLvl.setChunkForced(chunkPos.x, chunkPos.z, false);
            }
        }

        return null;
    }

    private int validateAndAdjustSurfaceHeight(int x, int z, int initialHeight, ServerLevel lvl) {
        int currentY = initialHeight;

        while (currentY > lvl.getMinBuildHeight() + 10) {
            BlockPos pos = new BlockPos(x, currentY, z);
            BlockState state = lvl.getBlockState(pos);

            if (WorldReloaderTask.isSolidBlock(lvl, state)) {
                return currentY;
            }

            currentY--;
        }

        return initialHeight;
    }

    private BlockPos findAlternativeBiomePosition(BlockPos center, Predicate<Holder<Biome>> targetBiome) {
        for (int i = 0; i < 10; i++) {
            int offsetX = world.random.nextInt(400) - 200;
            int offsetZ = world.random.nextInt(400) - 200;
            BlockPos testPos = center.offset(offsetX, 0, offsetZ);

            if (targetBiome.test(targetDimensionWorld.getBiome(testPos))) {
                BlockPos surfacePos = getValidSurfacePosition(testPos);
                if (surfacePos != null) return surfacePos;
            }
        }
        return null;
    }
}
