package com.wsteam.wandscape.worldreloader;

import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.worldreloader.network.TransformPreviewCancelPacket;
import com.wsteam.wandscape.worldreloader.network.TransformPreviewPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Service managing active WorldReloader tasks, block interactions (beacon trigger),
 * ghost previews with increasing opacity, and command dispatches.
 */
public class WorldReloaderManager {

    private static final String TAG = "WorldReloaderManager";
    private static final WorldReloaderManager INSTANCE = new WorldReloaderManager();
    public static final int PREVIEW_DURATION_TICKS = 80; // 4 seconds of preview fade-in

    private final List<WorldReloaderTask> activeTasks = new CopyOnWriteArrayList<>();
    private final List<PendingTransformation> pendingTransformations = new CopyOnWriteArrayList<>();
    private final Map<Item, Integer> itemRequirements = new HashMap<>();
    private Block targetBlock = Blocks.BEACON;
    private WorldReloaderConfig config;

    private static class PendingTransformation {
        final WorldReloaderBuilder builder;
        final WorldReloaderConfig.OperationMode opMode;
        @Nullable final Player player;
        final BlockPos centerPos;
        final ServerLevel world;
        int ticksRemaining;

        PendingTransformation(WorldReloaderBuilder builder, WorldReloaderConfig.OperationMode opMode,
                              @Nullable Player player, BlockPos centerPos, ServerLevel world, int ticksRemaining) {
            this.builder = builder;
            this.opMode = opMode;
            this.player = player;
            this.centerPos = centerPos;
            this.world = world;
            this.ticksRemaining = ticksRemaining;
        }
    }

    private WorldReloaderManager() {
        this.config = WorldReloaderConfig.load();
        updateRequirementsFromConfig();
    }

    public static WorldReloaderManager get() {
        return INSTANCE;
    }

    public static void register() {
        NeoForge.EVENT_BUS.register(INSTANCE);
        Log.info(TAG, "WorldReloaderManager registered to event bus");
    }

    public WorldReloaderConfig getConfig() {
        return config;
    }

    public void reloadConfig() {
        this.config = WorldReloaderConfig.load();
        updateRequirementsFromConfig();
        Log.info(TAG, "WorldReloader configuration reloaded");
    }

    public void startTask(WorldReloaderTask task) {
        if (task != null) {
            task.start();
            activeTasks.add(task);
        }
    }

    public void stopAll() {
        pendingTransformations.clear();
        for (WorldReloaderTask task : activeTasks) {
            task.stop();
        }
        activeTasks.clear();
        PacketDistributor.sendToAllPlayers(new TransformPreviewCancelPacket());
        Log.info(TAG, "All active and pending WorldReloader tasks stopped");
    }

    public void tickAll() {
        // 1. Update pending preview transformations
        for (PendingTransformation pending : pendingTransformations) {
            pending.ticksRemaining--;
            if (pending.ticksRemaining <= 0) {
                pendingTransformations.remove(pending);
                executeBuiltTask(pending.builder, pending.opMode, pending.player, pending.centerPos);
            }
        }

        // 2. Update active transformation tasks
        for (WorldReloaderTask task : activeTasks) {
            try {
                task.tick();
                if (!task.isActive()) {
                    activeTasks.remove(task);
                }
            } catch (Exception e) {
                Log.error(TAG, "Error ticking task: {}", e.getMessage());
                task.stop();
                activeTasks.remove(task);
            }
        }
    }

    public int getActiveTaskCount() {
        return activeTasks.size() + pendingTransformations.size();
    }

    private void updateRequirementsFromConfig() {
        itemRequirements.clear();
        for (WorldReloaderConfig.ItemRequirement requirement : config.targetBlockDict) {
            if (!requirement.enabled) continue;
            try {
                ResourceLocation itemId = ResourceLocation.parse(requirement.itemId);
                Item item = BuiltInRegistries.ITEM.get(itemId);
                if (item != null) {
                    itemRequirements.put(item, requirement.count);
                }
            } catch (Exception e) {
                Log.error(TAG, "Failed to parse item ID: {}", requirement.itemId);
            }
        }

        try {
            ResourceLocation blockId = ResourceLocation.parse(config.targetBlock);
            Block block = BuiltInRegistries.BLOCK.get(blockId);
            if (block != null && block != Blocks.AIR) {
                targetBlock = block;
            } else {
                targetBlock = Blocks.BEACON;
            }
        } catch (Exception e) {
            targetBlock = Blocks.BEACON;
        }
    }

    public boolean checkPermission(Player player) {
        String permission = config.minPermission;
        if ("disabled".equalsIgnoreCase(permission)) {
            return false;
        } else if ("op".equalsIgnoreCase(permission)) {
            return player.hasPermissions(2);
        } else {
            return true;
        }
    }

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        if (level.isClientSide() || !(level instanceof ServerLevel serverLevel)) {
            return;
        }

        BlockPos clickedPos = event.getPos();
        BlockState clickedState = level.getBlockState(clickedPos);
        if (!clickedState.is(targetBlock)) {
            return;
        }

        Player player = event.getEntity();
        InteractionHand hand = event.getHand();
        ItemStack heldStack = player.getItemInHand(hand);

        if (itemRequirements.isEmpty()) {
            updateRequirementsFromConfig();
        }

        Item heldItem = heldStack.getItem();
        if (!itemRequirements.containsKey(heldItem)) {
            return;
        }

        if (!checkPermission(player)) {
            player.sendSystemMessage(Component.literal("§c[WorldReloader] 你没有权限使用地形改造功能！"));
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
            return;
        }

        int requiredCount = itemRequirements.get(heldItem);
        if (!player.isCreative()) {
            if (heldStack.getCount() >= requiredCount) {
                heldStack.shrink(requiredCount);
            } else {
                player.sendSystemMessage(Component.literal(String.format("§c[WorldReloader] 需要 %d 个 %s", requiredCount, heldItem.getDescription().getString())));
                event.setCancellationResult(InteractionResult.FAIL);
                event.setCanceled(true);
                return;
            }
        }

        // Trigger transformation
        player.sendSystemMessage(Component.literal("§6[WorldReloader] 激活信标地形改造..."));
        serverLevel.getServer().execute(() -> {
            startTransformationFromBeacon(serverLevel, clickedPos, player);
        });

        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    public void startTransformationFromBeacon(ServerLevel world, BlockPos beaconPos, Player player) {
        ServerLevel targetDimensionWorld = getTargetDimensionWorld(world.getServer(), config.dimension);

        WorldReloaderBuilder builder = new WorldReloaderBuilder(world, player)
                .setChangePos(beaconPos)
                .setRadius(config.maxRadius)
                .setPadding(config.paddingCount)
                .setSteps(config.mode == WorldReloaderConfig.OperationMode.SURFACE ? config.totalSteps : config.totalSteps2)
                .setItemCleanupInterval(config.itemCleanupInterval)
                .changeBiome(config.changeBiome)
                .preserveBeacon(config.preserveBeacon)
                .setTargetDimension(targetDimensionWorld);

        if (config.mode == WorldReloaderConfig.OperationMode.SURFACE) {
            builder.setYMin(config.depth).setYMax(config.height);
        } else {
            builder.setYMin(config.yMin).setYMax(config.yMaxThanSurface);
        }

        Predicate<Holder<Biome>> mappedBiome = detectTargetBiome(world, beaconPos, player);
        String mappedStructure = mappedBiome == null ? detectTargetStructure(world, beaconPos, player) : null;

        if (mappedBiome != null) {
            builder.setBiomePos(beaconPos, mappedBiome, config.searchRadius);
        } else if (mappedStructure != null) {
            builder.setStructurePos(beaconPos, mappedStructure, config.searchRadius);
        } else if (config.posMode == WorldReloaderConfig.PositionMode.FIXED) {
            BlockPos specificPos = new BlockPos(config.posX, config.posY, config.posZ);
            builder.setTargetPos(specificPos);
            if (config.debug && player != null) {
                player.sendSystemMessage(Component.literal("§6[WorldReloader] 使用特定位置: " + specificPos));
            }
        } else if (config.posMode == WorldReloaderConfig.PositionMode.BIOME) {
            ResourceLocation biomeLoc = ResourceLocation.parse(WorldReloaderConfig.normalizeId(config.targetBiomeId));
            ResourceKey<Biome> key = ResourceKey.create(Registries.BIOME, biomeLoc);
            builder.setBiomePos(beaconPos, entry -> entry.is(key), config.searchRadius);
        } else {
            builder.setRandomPos(beaconPos, config.randomRadius);
        }

        schedulePreviewAndTask(world, builder, config.mode, player, beaconPos);
    }

    public void startTransformationAt(ServerLevel world, BlockPos centerPos, @Nullable Player player,
                                      String mode, @Nullable String target) {
        ServerLevel targetDimensionWorld = getTargetDimensionWorld(world.getServer(), config.dimension);

        WorldReloaderBuilder builder = new WorldReloaderBuilder(world, player)
                .setChangePos(centerPos)
                .setRadius(config.maxRadius)
                .setPadding(config.paddingCount)
                .setSteps(config.mode == WorldReloaderConfig.OperationMode.SURFACE ? config.totalSteps : config.totalSteps2)
                .setItemCleanupInterval(config.itemCleanupInterval)
                .changeBiome(config.changeBiome)
                .preserveBeacon(config.preserveBeacon)
                .setTargetDimension(targetDimensionWorld);

        if (config.mode == WorldReloaderConfig.OperationMode.SURFACE) {
            builder.setYMin(config.depth).setYMax(config.height);
        } else {
            builder.setYMin(config.yMin).setYMax(config.yMaxThanSurface);
        }

        if ("biome".equalsIgnoreCase(mode) && target != null) {
            Predicate<Holder<Biome>> targetBiome;
            if (target.startsWith("#")) {
                ResourceLocation tagId = ResourceLocation.parse(target.substring(1));
                TagKey<Biome> biomeTag = TagKey.create(Registries.BIOME, tagId);
                targetBiome = entry -> entry.is(biomeTag);
            } else {
                ResourceKey<Biome> k = ResourceKey.create(Registries.BIOME, ResourceLocation.parse(WorldReloaderConfig.normalizeId(target)));
                targetBiome = entry -> entry.is(k);
            }
            builder.setBiomePos(centerPos, targetBiome, config.searchRadius);
        } else if ("structure".equalsIgnoreCase(mode) && target != null) {
            builder.setStructurePos(centerPos, target, config.searchRadius);
        } else {
            builder.setRandomPos(centerPos, config.randomRadius);
        }

        schedulePreviewAndTask(world, builder, config.mode, player, centerPos);
    }

    private void schedulePreviewAndTask(ServerLevel world, WorldReloaderBuilder builder,
                                        WorldReloaderConfig.OperationMode opMode,
                                        @Nullable Player player, BlockPos centerPos) {
        if (!builder.isValidated()) {
            if (player != null) {
                player.sendSystemMessage(Component.literal("§c[WorldReloader] 无法定位目标区域，改造取消！"));
            }
            return;
        }

        BlockPos refCenter = builder.getTargetPos();
        ServerLevel refWorld = builder.getTargetDimensionWorld();

        List<TransformPreviewPacket.PreviewBlock> previewBlocks = extractPreviewBlocks(
                world, centerPos, refCenter, refWorld,
                builder.getRadius(), opMode, builder.getYMin(), builder.getYMax(),
                builder.getPadding(), builder.isPreserveBeacon());

        TransformPreviewPacket packet = new TransformPreviewPacket(centerPos, builder.getRadius(), PREVIEW_DURATION_TICKS, previewBlocks);
        PacketDistributor.sendToPlayersTrackingChunk(world, new ChunkPos(centerPos), packet);
        if (player instanceof ServerPlayer sp) {
            PacketDistributor.sendToPlayer(sp, packet);
        }

        pendingTransformations.add(new PendingTransformation(builder, opMode, player, centerPos, world, PREVIEW_DURATION_TICKS));

        if (player != null) {
            player.sendSystemMessage(Component.literal("§6[WorldReloader] 地形改造虚影预览已展开，显现完毕后开始改造..."));
        }
        Log.info(TAG, "Scheduled transformation preview at {} (radius={}, previewBlocks={})",
                centerPos, builder.getRadius(), previewBlocks.size());
    }

    private List<TransformPreviewPacket.PreviewBlock> extractPreviewBlocks(
            ServerLevel world, BlockPos center, BlockPos referenceCenter, ServerLevel refWorld,
            int radius, WorldReloaderConfig.OperationMode mode, int yMin, int yMax,
            int paddingCount, boolean preserveBeacon) {

        List<TransformPreviewPacket.PreviewBlock> list = new ArrayList<>();
        int maxBlocks = 16000;

        Set<Long> forcedChunks = new HashSet<>();
        int chunkRadius = (radius + 15) >> 4;
        try {
            for (int cx = -chunkRadius; cx <= chunkRadius; cx++) {
                for (int cz = -chunkRadius; cz <= chunkRadius; cz++) {
                    int chX = (referenceCenter.getX() >> 4) + cx;
                    int chZ = (referenceCenter.getZ() >> 4) + cz;
                    long chunkKey = ChunkPos.asLong(chX, chZ);
                    if (!refWorld.isLoaded(new BlockPos(chX << 4, 64, chZ << 4))) {
                        refWorld.setChunkForced(chX, chZ, true);
                        forcedChunks.add(chunkKey);
                    }
                    refWorld.getChunk(chX, chZ);
                }
            }

            int rSq = radius * radius;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int distSq = dx * dx + dz * dz;
                    if (distSq > rSq) continue;
                    if (list.size() >= maxBlocks) break;

                    double dist = Math.sqrt(distSq);
                    int refX = referenceCenter.getX() + dx;
                    int refZ = referenceCenter.getZ() + dz;
                    int targetX = center.getX() + dx;
                    int targetZ = center.getZ() + dz;

                    // 1. Find the true solid surface height at (refX, refZ)
                    int refTopY = refWorld.getHeight(Heightmap.Types.MOTION_BLOCKING, refX, refZ);
                    int refSurfaceY = refTopY;
                    while (refSurfaceY > refWorld.getMinBuildHeight() + 10) {
                        BlockPos checkPos = new BlockPos(refX, refSurfaceY, refZ);
                        BlockState checkState = refWorld.getBlockState(checkPos);
                        if (WorldReloaderTask.isSolidBlock(refWorld, checkState)) {
                            break;
                        }
                        refSurfaceY--;
                    }

                    int originalSurfaceY = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, targetX, targetZ) - 1;
                    int refTargetSurfaceY = refSurfaceY + center.getY() - referenceCenter.getY();

                    int baseTargetSurfaceY;
                    if (mode == WorldReloaderConfig.OperationMode.STANDARD && dist >= radius - paddingCount && paddingCount > 0) {
                        float progress = 1.0f - (float) (radius - dist) / paddingCount;
                        baseTargetSurfaceY = (int) (refTargetSurfaceY + (originalSurfaceY - refTargetSurfaceY) * progress);
                    } else {
                        baseTargetSurfaceY = refTargetSurfaceY;
                    }

                    int yDelta = baseTargetSurfaceY - refSurfaceY;

                    // 2. Sample surface and subsurface (2 layers down)
                    for (int y = refSurfaceY; y >= Math.max(refSurfaceY - 2, refWorld.getMinBuildHeight()); y--) {
                        int targetY = y + yDelta;
                        BlockPos targetPos = new BlockPos(targetX, targetY, targetZ);
                        if (preserveBeacon && dist <= 8 && WorldReloaderTask.shouldPreserveCenterAreaStatic(targetPos, center)) {
                            continue;
                        }
                        BlockState state = refWorld.getBlockState(new BlockPos(refX, y, refZ));
                        if (!state.isAir()) {
                            list.add(new TransformPreviewPacket.PreviewBlock(
                                    (short) dx, (short) (targetY - center.getY()), (short) dz, Block.getId(state)));
                        }
                    }

                    // 3. Sample above-surface features (trees, vegetation, structures up to yMax)
                    int maxAbove = Math.min(refSurfaceY + yMax, refWorld.getMaxBuildHeight() - 1);
                    for (int y = refSurfaceY + 1; y <= maxAbove; y++) {
                        BlockPos abovePos = new BlockPos(refX, y, refZ);
                        BlockState aboveState = refWorld.getBlockState(abovePos);
                        if (!aboveState.isAir()) {
                            int targetY = y + yDelta;
                            BlockPos targetPos = new BlockPos(targetX, targetY, targetZ);
                            if (preserveBeacon && dist <= 8 && WorldReloaderTask.shouldPreserveCenterAreaStatic(targetPos, center)) {
                                continue;
                            }
                            list.add(new TransformPreviewPacket.PreviewBlock(
                                    (short) dx, (short) (targetY - center.getY()), (short) dz, Block.getId(aboveState)));
                        }
                    }
                }
            }
        } finally {
            for (long chunkKey : forcedChunks) {
                refWorld.setChunkForced(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey), false);
            }
        }

        return list;
    }

    private void executeBuiltTask(WorldReloaderBuilder builder, WorldReloaderConfig.OperationMode opMode,
                                  @Nullable Player player, BlockPos centerPos) {
        if (opMode == WorldReloaderConfig.OperationMode.SURFACE) {
            SurfaceTransformationTask task = builder.buildSurface();
            if (task != null) {
                startTask(task);
                if (player != null) {
                    player.sendSystemMessage(Component.literal("§a[WorldReloader] 地表地形改造已启动！"));
                }
                Log.info(TAG, "Surface terrain transformation started at {}", centerPos);
            } else if (player != null) {
                player.sendSystemMessage(Component.literal("§c[WorldReloader] 无法启动地表地形改造任务！"));
            }
        } else {
            TerrainTransformationTask task = builder.buildStandard();
            if (task != null) {
                startTask(task);
                if (player != null) {
                    player.sendSystemMessage(Component.literal("§a[WorldReloader] 完整地形改造已启动！"));
                }
                Log.info(TAG, "Standard terrain transformation started at {}", centerPos);
            } else if (player != null) {
                player.sendSystemMessage(Component.literal("§c[WorldReloader] 无法启动完整地形改造任务！"));
            }
        }
    }

    private ServerLevel getTargetDimensionWorld(MinecraftServer server, String dimensionStr) {
        try {
            ResourceLocation dimLoc = ResourceLocation.parse(WorldReloaderConfig.normalizeId(dimensionStr));
            ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, dimLoc);
            ServerLevel lvl = server.getLevel(key);
            if (lvl != null) {
                return lvl;
            }
        } catch (Exception e) {
            Log.warn(TAG, "Failed to resolve dimension {}, using overworld: {}", dimensionStr, e.getMessage());
        }
        return server.overworld();
    }

    private Predicate<Holder<Biome>> detectTargetBiome(ServerLevel world, BlockPos beaconPos, @Nullable Player player) {
        BlockPos sidePos = beaconPos.east();
        BlockState sideState = world.getBlockState(sidePos);
        String sideBlockId = BuiltInRegistries.BLOCK.getKey(sideState.getBlock()).toString();

        Map<String, String> biomeMap = config.getBiomeMap();
        String biomeId = biomeMap.get(sideBlockId);
        if (biomeId != null) {
            if (player != null && config.debug) {
                player.sendSystemMessage(Component.literal(String.format("§6[WorldReloader] 检测到东侧方块 %s，将寻找 %s 生物群系", sideBlockId, biomeId)));
            }
            if (biomeId.startsWith("#")) {
                ResourceLocation tagId = ResourceLocation.parse(biomeId.substring(1));
                TagKey<Biome> biomeTag = TagKey.create(Registries.BIOME, tagId);
                return entry -> entry.is(biomeTag);
            } else {
                ResourceKey<Biome> k = ResourceKey.create(Registries.BIOME, ResourceLocation.parse(WorldReloaderConfig.normalizeId(biomeId)));
                return entry -> entry.is(k);
            }
        }
        return null;
    }

    private String detectTargetStructure(ServerLevel world, BlockPos beaconPos, @Nullable Player player) {
        BlockPos sidePos = beaconPos.east();
        BlockState sideState = world.getBlockState(sidePos);
        String sideBlockId = BuiltInRegistries.BLOCK.getKey(sideState.getBlock()).toString();

        Map<String, String> structureMap = config.getStructureMap();
        String structureId = structureMap.get(sideBlockId);
        if (structureId != null) {
            if (player != null && config.debug) {
                player.sendSystemMessage(Component.literal(String.format("§6[WorldReloader] 检测到东侧方块 %s，将寻找 %s 结构", sideBlockId, structureId)));
            }
            return structureId;
        }
        return null;
    }
}
