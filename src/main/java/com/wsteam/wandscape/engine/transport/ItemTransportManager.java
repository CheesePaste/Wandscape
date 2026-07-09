package com.wsteam.wandscape.engine.transport;

import com.wsteam.wandscape.road.algorithm.RoadRouter;
import com.wsteam.wandscape.road.core.SplineLeg;
import com.wsteam.wandscape.road.core.SplineModel;
import com.wsteam.wandscape.road.core.SplinePoint;
import com.wsteam.wandscape.road.core.SplineVec3;
import com.wsteam.wandscape.road.core.TransportRoute;
import com.wsteam.wandscape.shared.data.ItemKey;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Manages in-flight item transport animations between warehouse and NPC.
 *
 * <p>Supports road network routing:
 * <ul>
 *   <li>Off-road: direct line, gentle arc, 20 ticks/block (1 block/sec)</li>
 *   <li>On-road: follows road PathPoints, flat (follows terrain Y), 10 ticks/block (2 blocks/sec)</li>
 * </ul>
 *
 * <p>When no road network is available, falls back to direct transport.
 */
public class ItemTransportManager {

    private static final String TAG = "ItemTransportManager";

    private final List<ActiveTransport> active = new ArrayList<>();

    // ── Public API ──

    /**
     * Send an item along a pre-planned route, or direct if route is empty.
     *
     * @param key        the item to transport
     * @param from       logical start position (for logging)
     * @param to         logical end position (for logging)
     * @param level      the server level to spawn the visual entity in
     * @param ownerNpcId ECS entity ID (for orphan recovery)
     * @param route      pre-planned route from {@link RoadRouter#plan}, or null/empty
     * @return future that completes when the item reaches its destination
     */
    public CompletableFuture<Void> send(ItemKey key, int count, BlockPos from, BlockPos to,
                                        Level level, long ownerNpcId,
                                        @Nullable TransportRoute route) {
        return send(key, count, from, to, level, ownerNpcId, route, false);
    }

    /** Convenience overload — direct path without route. */
    public CompletableFuture<Void> send(ItemKey key, int count, BlockPos from, BlockPos to,
                                        Level level, long ownerNpcId) {
        return send(key, count, from, to, level, ownerNpcId, null, false);
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
                                        @Nullable TransportRoute route,
                                        boolean ownsItem) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        TransportRoute actualRoute = route;
        if (actualRoute == null || actualRoute.isEmpty()) {
            // Direct fallback
            SplineModel gap = new SplineModel();
            SplineVec3 pA = new SplineVec3(from.getX() + 0.5, from.getY() + 0.5, from.getZ() + 0.5);
            SplineVec3 pB = new SplineVec3(to.getX() + 0.5, to.getY() + 0.5, to.getZ() + 0.5);
            gap.getPoints().add(new SplinePoint(pA, pA, pA, true));
            gap.getPoints().add(new SplinePoint(pB, pB, pB, true));
            actualRoute = new TransportRoute(List.of(new SplineLeg(gap, 0, 1, true)));
        }

        int totalTicks = 0;
        for (SplineLeg leg : actualRoute.legs()) {
            int ticksPerBlock = leg.offRoad() ? RoadRouter.TICKS_PER_BLOCK_OFF_ROAD : RoadRouter.TICKS_PER_BLOCK_ON_ROAD;
            totalTicks += Math.max(1, (int) leg.getApproxLength() * ticksPerBlock);
        }

        ActiveTransport t = new ActiveTransport(future, key, count,
                ownerNpcId, totalTicks, 0, ownsItem);
        active.add(t);

        // Send packet to clients
        if (level instanceof ServerLevel serverLevel) {
            TransportStartPacket packet = new TransportStartPacket(key, count, from, actualRoute);
            PacketDistributor.sendToPlayersTrackingChunk(serverLevel, new net.minecraft.world.level.ChunkPos(from), packet);
        }

        Log.debug(TAG, "[Transport] send {} {}→{} legs={} ticks={} npc={} ownsItem={}",
                key.itemId(), from.toShortString(), to.toShortString(),
                actualRoute.legs().size(), totalTicks, ownerNpcId, ownsItem);
        return future;
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
