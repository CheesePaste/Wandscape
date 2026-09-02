package com.wsteam.wandscape.content.warehouse.transport;

import com.wsteam.wandscape.content.warehouse.ColonyItemBank;
import com.wsteam.wandscape.content.road.algorithm.RoadRouter;
import com.wsteam.wandscape.content.road.core.PathPoint;
import com.wsteam.wandscape.content.road.core.RoadNetwork;
import com.wsteam.wandscape.content.road.core.SplineLeg;
import com.wsteam.wandscape.content.road.core.TransportRoute;
import com.wsteam.wandscape.content.road.engine.RoadSavedData;
import com.wsteam.wandscape.foundation.util.BalanceValues;
import com.wsteam.wandscape.foundation.util.ItemKey;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.log.LogCategory;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
    public static final int MAX_TRANSPORT_TICKS = 20 * 60; // 60s max limit
    private static final double VISUAL_RANGE = 96.0;
    private static final double VISUAL_RANGE_SQR = VISUAL_RANGE * VISUAL_RANGE;

    @Nullable
    private static volatile ItemTransportManager activeInstance;

    public static void setActive(@Nullable ItemTransportManager mgr) {
        activeInstance = mgr;
    }

    @Nullable
    public static ItemTransportManager getInstance() {
        return activeInstance;
    }

    public static void reset() {
        if (activeInstance != null) {
            for (ActiveTransport t : activeInstance.active) {
                t.future.cancel(false);
            }
            activeInstance.active.clear();
        }
        activeInstance = null;
    }

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
            ticksOnRoad = BalanceValues.transportTicksPerBlockOnRoad();
            ticksOffRoad = BalanceValues.transportTicksPerBlockOffRoad();
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

        int totalTicks = Math.min(actualRoute.totalDuration(ticksOnRoad, ticksOffRoad), MAX_TRANSPORT_TICKS);

        ActiveTransport t = new ActiveTransport(future, key, count,
                ownerNpcId, totalTicks, 0, ownsItem);
        active.add(t);

        // Send packet to clients in range so players at destination or along the road see flight
        if (level instanceof ServerLevel serverLevel) {
            TransportStartPacket packet = null;
            for (ServerPlayer player : serverLevel.players()) {
                if (isPlayerNearRoute(player, from, to, actualRoute)) {
                    if (packet == null) {
                        packet = new TransportStartPacket(key, count, from, actualRoute);
                    }
                    PacketDistributor.sendToPlayer(player, packet);
                }
            }
        }

        return future;
    }

    private static boolean isPlayerNearRoute(ServerPlayer player, BlockPos from, BlockPos to, TransportRoute route) {
        double dFrom = player.distanceToSqr(from.getX() + 0.5, from.getY() + 0.5, from.getZ() + 0.5);
        if (dFrom <= VISUAL_RANGE_SQR) return true;

        double dTo = player.distanceToSqr(to.getX() + 0.5, to.getY() + 0.5, to.getZ() + 0.5);
        if (dTo <= VISUAL_RANGE_SQR) return true;

        double minX = Math.min(from.getX(), to.getX());
        double maxX = Math.max(from.getX(), to.getX());
        double minY = Math.min(from.getY(), to.getY());
        double maxY = Math.max(from.getY(), to.getY());
        double minZ = Math.min(from.getZ(), to.getZ());
        double maxZ = Math.max(from.getZ(), to.getZ());

        if (route != null && !route.isEmpty()) {
            for (SplineLeg leg : route.legs()) {
                if (leg.spline() != null) {
                    for (var pt : leg.spline().getPoints()) {
                        var a = pt.getAnchor();
                        minX = Math.min(minX, a.x());
                        maxX = Math.max(maxX, a.x());
                        minY = Math.min(minY, a.y());
                        maxY = Math.max(maxY, a.y());
                        minZ = Math.min(minZ, a.z());
                        maxZ = Math.max(maxZ, a.z());
                    }
                }
            }
        }

        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();

        return px >= (minX - VISUAL_RANGE) && px <= (maxX + VISUAL_RANGE)
                && py >= (minY - VISUAL_RANGE) && py <= (maxY + VISUAL_RANGE)
                && pz >= (minZ - VISUAL_RANGE) && pz <= (maxZ + VISUAL_RANGE);
    }

    /**
     * Cancel all in-flight transports for the given NPC.
     * Only returns items that were <em>consumed</em> from the bank
     * (ownsItem=true). Reserved-but-not-consumed items are handled
     * separately by {@code ResourceRequestExecutor.cancelForNpc}.
     */
    public void cancelForNpc(long npcId,
                             ColonyItemBank bank,
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
            Log.debug(LogCategory.WAREHOUSE, "transport", "orphan recovery: {} {} (npc={})",
                    t.itemKey.itemId(), t.ownsItem ? "returned" : "discarded", npcId);
        }
    }

    /**
     * Drive all active transports forward by one tick.
     */
    public void tickAll() {
        if (active.isEmpty()) return;

        var arrived = new ArrayList<ActiveTransport>();

        for (int i = active.size() - 1; i >= 0; i--) {
            ActiveTransport t = active.get(i);
            t.elapsed++;
            if (t.elapsed >= t.duration) {
                active.remove(i);
                arrived.add(t);
            }
        }

        for (ActiveTransport t : arrived) {
            t.future.complete(null);
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
