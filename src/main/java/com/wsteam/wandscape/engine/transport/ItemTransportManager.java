package com.wsteam.wandscape.engine.transport;

import com.wsteam.wandscape.road.engine.WandscapeTags;
import com.wsteam.wandscape.shared.data.ItemKey;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Manages in-flight item transport animations between warehouse and NPC.
 *
 * <p>No road graph: the straight flight line is sampled and the ground-block
 * tag decides the speed — mostly over {@code wandscape:custom_roads} blocks
 * flies fast and flat, otherwise slow with an arc.
 */
public class ItemTransportManager {

    private static final String TAG = "ItemTransportManager";

    /** Ticks per block of distance for off-road transport (2 blocks/sec). */
    public static final int TICKS_PER_BLOCK_OFF_ROAD = 10;

    /** Ticks per block of distance for on-road transport (4 blocks/sec). */
    public static final int TICKS_PER_BLOCK_ON_ROAD = 5;

    /** Minimum fraction of sampled ground blocks that must be road-tagged to count as on-road. */
    private static final double ON_ROAD_FRACTION = 0.5;

    /** Cap on ground-block samples along the flight line (long flights sample every few blocks). */
    private static final int MAX_ROAD_SAMPLES = 128;

    private final List<ActiveTransport> active = new ArrayList<>();

    // ── Public API ──

    /**
     * Send an item flying from {@code from} to {@code to}.
     *
     * @param key        the item to transport
     * @param from       start position
     * @param to         end position
     * @param level      the server level to spawn the visual entity in
     * @param ownerNpcId ECS entity ID (for orphan recovery)
     * @return future that completes when the item reaches its destination
     */
    public CompletableFuture<Void> send(ItemKey key, int count, BlockPos from, BlockPos to,
                                        Level level, long ownerNpcId) {
        return send(key, count, from, to, level, ownerNpcId, false);
    }

    /**
     * Full send with ownership tracking.
     *
     * @param ownsItem  if true, the item was <em>consumed</em> from the colony bank
     *                  and MUST be returned on {@link #cancelForNpc}. If false,
     *                  the item was only reserved or is purely visual — no return.
     */
    public CompletableFuture<Void> send(ItemKey key, int count, BlockPos from, BlockPos to,
                                        Level level, long ownerNpcId,
                                        boolean ownsItem) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        boolean onRoad = isMostlyOverRoad(level, from, to);
        int dist = Math.max(1, (int) Math.sqrt(from.distSqr(to)));
        int ticksPerBlock = onRoad ? TICKS_PER_BLOCK_ON_ROAD : TICKS_PER_BLOCK_OFF_ROAD;
        int totalTicks = dist * ticksPerBlock;

        ActiveTransport t = new ActiveTransport(future, key, count,
                ownerNpcId, totalTicks, 0, ownsItem);
        active.add(t);

        // Send packet to clients
        if (level instanceof ServerLevel serverLevel) {
            TransportStartPacket packet = new TransportStartPacket(key, count, from, to, totalTicks, onRoad);
            PacketDistributor.sendToPlayersTrackingChunk(serverLevel, new net.minecraft.world.level.ChunkPos(from), packet);
        }

        return future;
    }

    // ── Road-surface check (block tag, no graph) ──

    /**
     * Sample the ground blocks under the straight flight line; the flight is
     * "on road" when at least half the samples match {@code custom_roads}.
     */
    private static boolean isMostlyOverRoad(Level level, BlockPos from, BlockPos to) {
        if (level == null) return false;
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        int dist = Math.max(1, Math.abs(dx) + Math.abs(dz));
        int steps = Math.min(dist, MAX_ROAD_SAMPLES);
        int onRoad = 0;
        for (int i = 0; i <= steps; i++) {
            int x = from.getX() + dx * i / steps;
            int z = from.getZ() + dz * i / steps;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
            if (y < level.getMinBuildHeight()) continue;
            if (level.getBlockState(new BlockPos(x, y, z)).is(WandscapeTags.Blocks.CUSTOM_ROADS)) {
                onRoad++;
            }
        }
        return (double) onRoad / (steps + 1) >= ON_ROAD_FRACTION;
    }

    /**
     * Cancel all in-flight transports for the given NPC.
     * Only returns items that were <em>consumed</em> from the bank
     * (ownsItem=true). Reserved-but-not-consumed items are handled
     * separately by {@code ResourceRequestExecutor.cancelForNpc}.
     */
    public void cancelForNpc(long npcId,
                             com.wsteam.wandscape.warehouse.ColonyItemBank bank,
                             UUID colonyId) {
        var toCancel = new ArrayList<ActiveTransport>();
        for (ActiveTransport t : active) {
            if (t.ownerNpcId == npcId) toCancel.add(t);
        }
        for (ActiveTransport t : toCancel) {
            if (t.ownsItem) {
                bank.add(colonyId, t.itemKey, t.count);
            }
            t.future.cancel(false);
            active.remove(t);
            Log.info(TAG, "[Transport] orphan recovery: {} {} (npc={})",
                    t.itemKey.itemId(), t.ownsItem ? "returned" : "discarded", npcId);
        }
    }

    /**
     * Drive all active transports forward by one tick.
     */
    public void tickAll() {
        if (active.isEmpty()) return;

        var arrived = new ArrayList<ActiveTransport>();

        for (ActiveTransport t : active) {
            t.elapsed++;
            if (t.elapsed >= t.duration) {
                arrived.add(t);
            }
        }

        for (ActiveTransport t : arrived) {
            t.future.complete(null);
            active.remove(t);
        }
    }

    public int pendingCount() {
        return active.size();
    }

    // ── Internal types ──

    private static class ActiveTransport {
        final CompletableFuture<Void> future;
        final ItemKey itemKey;
        final int count;
        final long ownerNpcId;
        final int duration;
        int elapsed;
        final boolean ownsItem;

        ActiveTransport(CompletableFuture<Void> future,
                       ItemKey itemKey, int count, long ownerNpcId,
                       int duration, int elapsed,
                       boolean ownsItem) {
            this.future = future;
            this.itemKey = itemKey;
            this.count = count;
            this.ownerNpcId = ownerNpcId;
            this.duration = duration;
            this.elapsed = elapsed;
            this.ownsItem = ownsItem;
        }
    }
}
