package com.wsteam.wandscape.engine.boundary;

import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.annotation.Nullable;

import com.wsteam.wandscape.core.boundary.BlockOps;
import com.wsteam.wandscape.core.types.BlockType;
import com.wsteam.wandscape.core.types.GridPos;
import com.wsteam.wandscape.engine.service.ChunkLoadManager;
import com.wsteam.wandscape.engine.service.SoundService;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import com.wsteam.wandscape.shared.log.Log;

/**
 * MC implementation of {@link BlockOps}.
 * Delegates to the server overworld {@link Level}.
 */
public class WandscapeBlockOps implements BlockOps {

    private static final String TAG = "WandscapeBlockOps";

    /** Tried in order when finding an adjacent air block for the redstone pulse. */
    private static final Direction[] PULSE_DIRS = {
            Direction.UP, Direction.NORTH, Direction.SOUTH,
            Direction.EAST, Direction.WEST, Direction.DOWN
    };

    /** 方块放置/拆除音效节流间隔（tick）：建造连续放块、拆除连续清块时防止每块都响。 */
    private static final int BLOCK_SOUND_THROTTLE_TICKS = 10;

    // Cache block type string → MC Block lookups
    private final ConcurrentMap<String, Block> blockCache = new ConcurrentHashMap<>();
    // Cache resolved BlockStates for types with bracket properties
    private final ConcurrentMap<String, BlockState> stateCache = new ConcurrentHashMap<>();

    @Override
    public void setBlock(GridPos pos, BlockType type) {
        Level level = getLevel();
        if (level == null) return;
        BlockState state = resolveBlockState(type);
        if (state == null) return;

        BlockPos bp = toBlockPos(pos);
        ChunkPos cp = new ChunkPos(bp);
        // Temporary lease: force-load the target chunk so the write lands even when
        // the area is otherwise unloaded (manual blueprints / road tasks that skip the
        // building lease). Refcounted — no-op on the construction path where the
        // building lease already holds the chunk.
        ChunkLoadManager.get().acquireChunk(cp);
        try {
            BlockState oldState = level.getBlockState(bp);
            evacuateEntities(level, bp);
            level.setBlock(bp, state, 2);
            if (state.isAir() && !oldState.isAir()) {
                // 移除（拆除/清空）：播被拆方块自身的原版破坏音，节流防止每块都响
                SoundEvent breakSound = oldState.getSoundType(level, bp, null).getBreakSound();
                if (level instanceof ServerLevel sl && breakSound != null) {
                    SoundService.playAtThrottled(sl, bp.getX() + 0.5, bp.getY() + 0.5, bp.getZ() + 0.5,
                            breakSound, SoundSource.BLOCKS, 0.8f, 1.0f, BLOCK_SOUND_THROTTLE_TICKS);
                }
            } else if (!state.isAir()) {
                // 方块自身原版放置音（与原版玩家右手放置一致），BLOCKS 通道，与拆除同频节流
                SoundEvent placeSound = state.getSoundType(level, bp, null).getPlaceSound();
                if (level instanceof ServerLevel sl && placeSound != null) {
                    SoundService.playAtThrottled(sl, bp.getX() + 0.5, bp.getY() + 0.5, bp.getZ() + 0.5,
                            placeSound, SoundSource.BLOCKS, 0.8f, 1.0f, BLOCK_SOUND_THROTTLE_TICKS);
                }
            }
        } finally {
            ChunkLoadManager.get().releaseChunk(cp);
        }
    }

    @Override
    public void setBlockEntityData(GridPos pos, @Nullable String nbtBase64) {
        if (nbtBase64 == null || nbtBase64.isEmpty()) return;
        Level level = getLevel();
        if (level == null) return;
        BlockPos bp = toBlockPos(pos);
        ChunkPos cp = new ChunkPos(bp);
        ChunkLoadManager.get().acquireChunk(cp);
        try {
            byte[] data = Base64.getDecoder().decode(nbtBase64);
            CompoundTag tag = NbtIo.readCompressed(new ByteArrayInputStream(data), NbtAccounter.create(0x200000L));
            BlockState state = level.getBlockState(bp);
            BlockEntity be = BlockEntity.loadStatic(bp, state, tag, level.registryAccess());
            if (be != null) {
                level.setBlockEntity(be);
                be.setChanged();
                // Sync BlockEntity data to clients
                if (level instanceof ServerLevel serverLevel) {
                    var packet = be.getUpdatePacket();
                    if (packet != null) {
                        serverLevel.getChunkSource().chunkMap.getPlayers(
                                serverLevel.getChunk(bp).getPos(), false
                        ).forEach(player -> player.connection.send(packet));
                    }
                }
            }
        } catch (Exception e) {
            Log.warn(TAG, "Failed to restore BlockEntity NBT at {}: {}", bp, e.toString());
        } finally {
            ChunkLoadManager.get().releaseChunk(cp);
        }
    }

    /** Push any living entities out of the target block before placing. */
    private void evacuateEntities(Level level, BlockPos pos) {
        List<Entity> occupants = level.getEntities((Entity) null, new AABB(pos),
                e -> e.isAlive() && !e.isSpectator());
        if (occupants.isEmpty()) return;

        for (Entity e : occupants) {
            // Try adjacent blocks first, then upward
            boolean found = false;
            for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH,
                    Direction.EAST, Direction.WEST, Direction.UP}) {
                BlockPos adj = pos.relative(dir);
                if (level.getBlockState(adj).isAir() && level.getBlockState(adj.above()).isAir()) {
                    e.teleportTo(adj.getX() + 0.5, adj.getY(), adj.getZ() + 0.5);
                    found = true;
                    break;
                }
            }
            if (!found) {
                // Last resort: push straight up 2 blocks
                e.teleportTo(pos.getX() + 0.5, pos.getY() + 2, pos.getZ() + 0.5);
            }
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

    // ================================================================
    // Block interaction (BlockInteractOp — toggle / activate / open_gui)
    // ================================================================

    /**
     * Toggle a binary block state — lever, door, trapdoor, fence gate,
     * note block, bell, etc.
     *
     * <p>Delegates to {@code BlockBehaviour.useWithoutItem} with a null
     * Player. All vanilla toggle blocks handle null correctly (sound is
     * broadcast globally). Wrapped in try-catch for mod-compatibility.
     */
    @Override
    public void toggle(GridPos pos) {
        Level level = getLevel();
        if (level == null) return;

        BlockPos bp = toBlockPos(pos);
        BlockState state = level.getBlockState(bp);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(bp), Direction.UP, bp, false);

        try {
            state.useWithoutItem(level, null, hit);
        } catch (Exception e) {
            Log.warn(TAG, "toggle failed at {} (block={}): {}", bp, state.getBlock(), e.toString());
        }
    }

    /**
     * One-shot activation — buttons (press), TNT (ignite), dispensers
     * (dispense), pistons (extend), bells (ring), note blocks (play), etc.
     *
     * <p>Two-tier strategy:
     * <ol>
     *   <li>{@code useWithoutItem} — handles buttons, bells, note blocks,
     *       and any block that responds to empty-hand right-click.</li>
     *   <li>Redstone pulse fallback — places then immediately removes a
     *       redstone block on an adjacent air position. The synchronous
     *       {@code neighborChanged} triggers TNT, dispensers, pistons,
     *       command blocks, and any other redstone-reactive block.</li>
     * </ol>
     */
    @Override
    public void activate(GridPos pos) {
        Level level = getLevel();
        if (level == null) return;

        BlockPos bp = toBlockPos(pos);
        BlockState state = level.getBlockState(bp);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(bp), Direction.UP, bp, false);

        // Tier 1: useWithoutItem
        try {
            InteractionResult result = state.useWithoutItem(level, null, hit);
            if (result.consumesAction()) {
                return; // Block handled it
            }
        } catch (Exception e) {
            Log.warn(TAG, "activate useWithoutItem failed at {} (block={}): {}",
                    bp, state.getBlock(), e.toString());
        }

        // Tier 2: redstone pulse fallback
        redstonePulse(level, bp);
    }

    /**
     * Place a redstone block on an adjacent air position, then remove it
     * immediately. Neighbor updates fire synchronously — the target block
     * receives {@code neighborChanged} within the same tick.
     */
    private void redstonePulse(Level level, BlockPos target) {
        BlockPos adj = findAdjacentAir(level, target);
        if (adj == null) {
            Log.debug(TAG, "activate redstone fallback: no adjacent air at {}", target);
            return;
        }
        level.setBlock(adj, Blocks.REDSTONE_BLOCK.defaultBlockState(),
                Block.UPDATE_ALL);
        level.setBlock(adj, Blocks.AIR.defaultBlockState(),
                Block.UPDATE_ALL);
    }

    /** Find the first adjacent air block, or null if all 6 faces are solid. */
    @Nullable
    private BlockPos findAdjacentAir(Level level, BlockPos pos) {
        for (Direction dir : PULSE_DIRS) {
            BlockPos adj = pos.relative(dir);
            if (level.getBlockState(adj).isAir()) {
                return adj;
            }
        }
        return null;
    }

    /**
     * Access a block's container inventory.
     *
     * <p>Triggers the open/close animation via {@code level.blockEvent}
     * (e.g. chest lid) and provides access to the {@link Container}
     * interface for item manipulation by higher-level systems.
     */
    @Override
    public void openGui(GridPos pos) {
        Level level = getLevel();
        if (level == null) return;

        BlockPos bp = toBlockPos(pos);
        BlockState state = level.getBlockState(bp);
        Block block = state.getBlock();
        BlockEntity blockEntity = level.getBlockEntity(bp);

        // Open animation (server → client)
        try {
            level.blockEvent(bp, block, 1, 0);
        } catch (Exception e) {
            Log.warn(TAG, "openGui blockEvent(open) failed at {}: {}", bp, e.toString());
        }

        // Container access for future item manipulation
        if (blockEntity instanceof Container container) {
            Log.debug(TAG, "openGui: container at {} has {} slots",
                    bp, container.getContainerSize());
            // Stage 3+: NPC reads/writes items via Container interface
        }

        // Close animation
        try {
            level.blockEvent(bp, block, 0, 0);
        } catch (Exception e) {
            Log.warn(TAG, "openGui blockEvent(close) failed at {}: {}", bp, e.toString());
        }
    }

    // ---- Helpers ----

    /**
     * Resolve a {@link BlockType} string to a {@link BlockState}.
     *
     * <p>Supports MC bracket notation for state properties:
     * {@code "minecraft:lever[facing=north,face=floor]"}.
     * Plain block IDs (e.g. {@code "minecraft:stone"}) resolve to
     * {@link Block#defaultBlockState()}.
     *
     * @return the resolved BlockState, or null if the block ID is not found
     */
    @Nullable
    private BlockState resolveBlockState(BlockType type) {
        return stateCache.computeIfAbsent(type.id(), id -> {
            String blockId = id;
            java.util.Map<String, String> props = Collections.emptyMap();

            // Parse bracket notation: "mod:block[prop1=val1,prop2=val2]"
            int bracket = id.indexOf('[');
            if (bracket > 0 && id.endsWith("]")) {
                blockId = id.substring(0, bracket);
                String propsStr = id.substring(bracket + 1, id.length() - 1);
                props = new java.util.LinkedHashMap<>();
                for (String kv : propsStr.split(",")) {
                    String[] parts = kv.split("=", 2);
                    if (parts.length == 2) {
                        props.put(parts[0].trim(), parts[1].trim());
                    }
                }
            }

            ResourceLocation rl = ResourceLocation.tryParse(blockId);
            if (rl == null) return null;
            Block block = BuiltInRegistries.BLOCK.getOptional(rl).orElse(null);
            if (block == null) return null;

            BlockState state = block.defaultBlockState();
            for (var entry : props.entrySet()) {
                state = applyProperty(state, entry.getKey(), entry.getValue());
            }
            return state;
        });
    }

    /**
     * Apply a single property value to a BlockState by name.
     * Looks up the property from the block's state definition.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static BlockState applyProperty(BlockState state, String key, String value) {
        net.minecraft.world.level.block.state.properties.Property<?> prop =
                state.getBlock().getStateDefinition().getProperty(key);
        if (prop == null) {
            Log.warn(TAG, "Unknown blockstate property '{}' for block {}, ignoring", key, state.getBlock());
            return state;
        }
        return setPropertyValue(state, (net.minecraft.world.level.block.state.properties.Property) prop, value);
    }

    /** Type-safe property value setting via the property's valueOf. */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static <T extends Comparable<T>> BlockState setPropertyValue(
            BlockState state,
            net.minecraft.world.level.block.state.properties.Property<T> prop,
            String value) {
        return state.setValue(prop, (T) prop.getValue(value).orElse(null));
    }

    /** Keep resolveBlock for calls that just need the Block (getBlock, isAir). */
    @Nullable
    private Block resolveBlock(BlockType type) {
        String id = type.id();
        int bracket = id.indexOf('[');
        if (bracket > 0) id = id.substring(0, bracket); // strip state properties
        final String cleanId = id;
        return blockCache.computeIfAbsent(cleanId, id2 -> {
            ResourceLocation rl = ResourceLocation.tryParse(id2);
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
