package com.wsteam.wandscape.engine.transport;

import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.road.algorithm.RoadRouter;
import com.wsteam.wandscape.road.core.PathPoint;
import com.wsteam.wandscape.road.core.RoadNetwork;
import com.wsteam.wandscape.road.core.TransportRoute;
import com.wsteam.wandscape.road.engine.RoadSavedData;
import com.wsteam.wandscape.shared.data.ItemKey;
import com.wsteam.wandscape.shared.log.Log;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Manages in-flight item transport animations between warehouse and NPC.
 *
 * <p>Plans fast, non-blocking routes along player-built roads using {@link RoadRouter}.
 * Falls back to direct flight if no road is nearby or if the road is an excessive detour.
 */
public class ItemTransportManager {

    private static final String TAG = "ItemTransportManager";

    private final List<ActiveTransport> active = new ArrayList<>();

    // ── Public API ──

    /**
     * Send an item along a route from {@code from} to {@code to}.
     */
    public CompletableFuture<Void> send(ItemKey key, int count, BlockPos from, BlockPos to,
                                        Level level, long ownerNpcId) {
        return send(key, count, from, to, level, ownerNpcId, null, false);
    }

    /**
     * Send an item with an optional pre-computed route.
     */
    public CompletableFuture<Void> send(ItemKey key, int count, BlockPos from, BlockPos to,
                                        Level level, long ownerNpcId,
                                        @Nullable TransportRoute route) {
        return send(key, count, from, to, level, ownerNpcId, route, false);
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

        int ticksOnRoad = RoadRouter.DEFAULT_TICKS_ON_ROAD;
        int ticksOffRoad = RoadRouter.DEFAULT_TICKS_OFF_ROAD;
        try {
            ticksOnRoad = Config.TRANSPORT_TICKS_PER_BLOCK_ON_ROAD.get();
            ticksOffRoad = Config.TRANSPORT_TICKS_PER_BLOCK_OFF_ROAD.get();
        } catch (Exception ignored) {}

        TransportRoute actualRoute = route;
        if (actualRoute == null || actualRoute.isEmpty()) {
            RoadNetwork network = null;
            if (level instanceof ServerLevel serverLevel) {
                network = RoadSavedData.getOrCreate(serverLevel).getNetwork();
            }
            PathPoint startPt = new PathPoint(from.getX(), from.getY(), from.getZ());
            PathPoint endPt = new PathPoint(to.getX(), to.getY(), to.getZ());
            actualRoute = RoadRouter.plan(network, startPt, endPt, ticksOnRoad, ticksOffRoad);
        }

        int totalTicks = actualRoute.totalDuration(ticksOnRoad, ticksOffRoad);

        ActiveTransport t = new ActiveTransport(future, key, count,
                ownerNpcId, totalTicks, 0, ownsItem);
        active.add(t);

        // Send packet to clients in this level so players at destination or along the road see flight
        if (level instanceof ServerLevel serverLevel) {
            TransportStartPacket packet = new TransportStartPacket(key, count, from, actualRoute);
            for (net.minecraft.server.level.ServerPlayer player : serverLevel.players()) {
                PacketDistributor.sendToPlayer(player, packet);
            }
        }

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
