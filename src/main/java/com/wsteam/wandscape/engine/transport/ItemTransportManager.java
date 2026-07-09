package com.wsteam.wandscape.engine.transport;

import com.wsteam.wandscape.road.algorithm.RoadRouter;
import com.wsteam.wandscape.road.core.RouteSegment;
import com.wsteam.wandscape.shared.data.ItemKey;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
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
                                        @Nullable List<RouteSegment> route) {
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
                                        @Nullable List<RouteSegment> route,
                                        boolean ownsItem) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        ItemEntity entity = spawnVisual(key, count, from, level);
        if (entity == null) {
            future.complete(null);
            return future;
        }

        List<Leg> legs;
        if (route != null && !route.isEmpty()) {
            legs = buildLegs(route);
        } else {
            // Direct fallback
            legs = List.of(new Leg(Vec3.atCenterOf(from), Vec3.atCenterOf(to),
                    false /* arc ON for direct path */));
        }

        ActiveTransport t = new ActiveTransport(future, entity, key, count,
                ownerNpcId, legs, 0, 0, ownsItem);
        active.add(t);

        Log.debug(TAG, "[Transport] send {} {}→{} legs={} npc={} ownsItem={}",
                key.itemId(), from.toShortString(), to.toShortString(),
                legs.size(), ownerNpcId, ownsItem);
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
            t.entity.discard();
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
            neutralizeEntityPhysics(t.entity);

            t.elapsed++;
            Leg leg = t.legs.get(t.legIndex);
            int segElapsed = t.segmentElapsed + 1;

            if (segElapsed >= leg.duration) {
                // This leg complete → advance to next leg
                t.segmentElapsed = 0;
                t.legIndex++;

                if (t.legIndex >= t.legs.size()) {
                    // All legs done
                    arrived.add(t);
                    continue;
                }

                // Start next leg
                Leg next = t.legs.get(t.legIndex);
                tickLeg(t.entity, next, 0);
            } else {
                t.segmentElapsed = segElapsed;
                tickLeg(t.entity, leg, segElapsed);
            }
        }

        for (ActiveTransport t : arrived) {
            t.entity.setPos(t.legs.get(t.legs.size() - 1).to.x,
                    t.legs.get(t.legs.size() - 1).to.y,
                    t.legs.get(t.legs.size() - 1).to.z);
            t.entity.discard();
            t.future.complete(null);
            active.remove(t);
        }
    }

    public int pendingCount() {
        return active.size();
    }

    // ── Leg helpers ──

    /** Move the entity to its position at tick {@code elapsed} of the given leg. */
    private static void tickLeg(ItemEntity entity, Leg leg, int elapsed) {
        double t = (double) elapsed / leg.duration;

        double x = leg.from.x + (leg.to.x - leg.from.x) * t;
        double z = leg.from.z + (leg.to.z - leg.from.z) * t;

        double y;
        if (leg.flatY) {
            // On-road: linear Y interpolation (follow terrain)
            y = leg.from.y + (leg.to.y - leg.from.y) * t;
        } else {
            // Off-road: arc
            y = leg.from.y + (leg.to.y - leg.from.y) * t + Math.sin(t * Math.PI) * 1.5;
        }

        Vec3 pos = new Vec3(x, y, z);

        entity.xo = entity.getX();
        entity.yo = entity.getY();
        entity.zo = entity.getZ();
        entity.xOld = entity.getX();
        entity.yOld = entity.getY();
        entity.zOld = entity.getZ();

        // Calculate and set velocity for client-side interpolation/prediction
        double nextT = (double) (elapsed + 1) / leg.duration;
        double nx = leg.from.x + (leg.to.x - leg.from.x) * nextT;
        double nz = leg.from.z + (leg.to.z - leg.from.z) * nextT;
        double ny;
        if (leg.flatY) {
            ny = leg.from.y + (leg.to.y - leg.from.y) * nextT;
        } else {
            ny = leg.from.y + (leg.to.y - leg.from.y) * nextT + Math.sin(nextT * Math.PI) * 1.5;
        }
        entity.setDeltaMovement(nx - x, ny - y, nz - z);

        entity.hasImpulse = true;
        entity.setPos(pos.x, pos.y, pos.z);
    }

    /** Convert RouteSegments (core-friendly) to engine Legs, applying Y-smoothing. */
    private static List<Leg> buildLegs(List<RouteSegment> route) {
        List<Leg> legs = new ArrayList<>();
        if (route.isEmpty()) return legs;

        List<Vec3> nodes = new ArrayList<>();
        for (int i = 0; i < route.size(); i++) {
            RouteSegment seg = route.get(i);
            double yOff = seg.onRoad() ? 1.0 : 0.5;
            if (i == 0) {
                nodes.add(new Vec3(seg.fromX() + 0.5, seg.fromY() + yOff, seg.fromZ() + 0.5));
            }
            nodes.add(new Vec3(seg.toX() + 0.5, seg.toY() + yOff, seg.toZ() + 0.5));
        }

        List<Vec3> smoothedNodes = new ArrayList<>(nodes.size());
        smoothedNodes.add(nodes.get(0)); // Keep exact start

        int window = 2; // Smooth over 5 blocks
        for (int i = 1; i < nodes.size() - 1; i++) {
            boolean prevOnRoad = route.get(i - 1).onRoad();
            boolean nextOnRoad = route.get(i).onRoad();
            
            // Only smooth road-related nodes
            if (prevOnRoad || nextOnRoad) {
                double sumY = 0;
                int count = 0;
                for (int j = Math.max(1, i - window); j <= Math.min(nodes.size() - 2, i + window); j++) {
                    sumY += nodes.get(j).y;
                    count++;
                }
                smoothedNodes.add(new Vec3(nodes.get(i).x, sumY / count, nodes.get(i).z));
            } else {
                smoothedNodes.add(nodes.get(i));
            }
        }

        if (nodes.size() > 1) {
            smoothedNodes.add(nodes.get(nodes.size() - 1)); // Keep exact end
        }

        // Apply path decimation/simplification to merge short segments where possible
        // This prevents the entity from changing legs every single block
        List<Vec3> decimatedNodes = new ArrayList<>();
        List<Boolean> decimatedOnRoad = new ArrayList<>();
        
        decimatedNodes.add(smoothedNodes.get(0));
        
        for (int i = 0; i < route.size(); i++) {
            boolean onRoad = route.get(i).onRoad();
            Vec3 nextNode = smoothedNodes.get(i + 1);
            
            // If we have at least 2 nodes in decimated list, check if we can skip the intermediate one
            if (decimatedNodes.size() >= 2) {
                Vec3 prevNode = decimatedNodes.get(decimatedNodes.size() - 2);
                Vec3 currNode = decimatedNodes.get(decimatedNodes.size() - 1);
                boolean prevOnRoad = decimatedOnRoad.get(decimatedOnRoad.size() - 1);
                
                // If same type and colinear, replace current with next
                if (prevOnRoad == onRoad) {
                    Vec3 v1 = currNode.subtract(prevNode).normalize();
                    Vec3 v2 = nextNode.subtract(currNode).normalize();
                    
                    if (v1.distanceTo(v2) < 0.05) { // highly colinear
                        decimatedNodes.set(decimatedNodes.size() - 1, nextNode);
                        continue; // Skip adding a new leg, just extended the previous one
                    }
                }
            }
            
            decimatedNodes.add(nextNode);
            decimatedOnRoad.add(onRoad);
        }

        for (int i = 0; i < decimatedOnRoad.size(); i++) {
            legs.add(new Leg(decimatedNodes.get(i), decimatedNodes.get(i + 1), decimatedOnRoad.get(i)));
        }
        return legs;
    }

    /** Reset all physics fields that ItemEntity.tick() may have overwritten. */
    private static void neutralizeEntityPhysics(ItemEntity entity) {
        entity.noPhysics = true;
        entity.setNoGravity(true);
        // Do NOT setDeltaMovement(Vec3.ZERO) here, tickLeg manages the velocity!
        entity.setUnlimitedLifetime();
        entity.setPickUpDelay(Short.MAX_VALUE);
    }

    // ── Visual spawn ──

    /** Spawn a no-pickup, no-physics ItemEntity for visual only. */
    @Nullable
    private static ItemEntity spawnVisual(ItemKey key, int count, BlockPos pos, Level level) {
        var item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(key.itemId()));
        if (item == null) {
            Log.warn(TAG, "[Transport] unknown item: {}", key.itemId());
            return null;
        }
        ItemStack stack = new ItemStack(item, count);
        if (key.nbt() != null && !key.nbt().isEmpty()) {
            stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                    net.minecraft.world.item.component.CustomData.of(key.nbt().copy()));
        }
        Vec3 center = Vec3.atCenterOf(pos);
        TransportItemEntity entity = new TransportItemEntity(level, center.x, center.y + 0.5, center.z, stack);
        entity.setPickUpDelay(Short.MAX_VALUE);
        entity.setUnlimitedLifetime();
        entity.setNoGravity(true);
        entity.noPhysics = true;
        entity.hasImpulse = true;
        level.addFreshEntity(entity);
        return entity;
    }

    // ── Internal types ──

    /** A single straight-line leg of a transport. */
    private static class Leg {
        final Vec3 from, to;
        final int duration;
        final boolean flatY; // true = on-road, no arc

        Leg(Vec3 from, Vec3 to, boolean flatY) {
            this.from = from;
            this.to = to;
            this.flatY = flatY;
            // Fallback: ensure at least 1 tick per leg
            int xzDist = Math.max(1,
                    (int) (Math.abs(to.x - from.x) + Math.abs(to.z - from.z)));
            int ticksPerBlock = flatY
                    ? RoadRouter.TICKS_PER_BLOCK_ON_ROAD
                    : RoadRouter.TICKS_PER_BLOCK_OFF_ROAD;
            this.duration = Math.max(1, xzDist * ticksPerBlock);
        }
    }

    private static class ActiveTransport {
        final CompletableFuture<Void> future;
        final ItemEntity entity;
        final ItemKey itemKey;
        final int count;
        final long ownerNpcId;
        final List<Leg> legs;
        int legIndex;
        int segmentElapsed;
        int elapsed;
        final boolean ownsItem;

        ActiveTransport(CompletableFuture<Void> future, ItemEntity entity,
                       ItemKey itemKey, int count, long ownerNpcId,
                       List<Leg> legs, int legIndex, int segmentElapsed,
                       boolean ownsItem) {
            this.future = future;
            this.entity = entity;
            this.itemKey = itemKey;
            this.count = count;
            this.ownerNpcId = ownerNpcId;
            this.legs = legs;
            this.legIndex = legIndex;
            this.segmentElapsed = segmentElapsed;
            this.ownsItem = ownsItem;
        }
    }
}
