package com.wsteam.wandscape.engine.transport;

import com.wsteam.wandscape.shared.data.ItemKey;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Manages in-flight item transport animations between warehouse and NPC.
 *
 * <p>Each transport spawns a temporary {@link ItemEntity} that lerps
 * from a start position to a target position over a fixed duration.
 * The returned {@link CompletableFuture} completes when the item arrives.
 *
 * <p>Call {@link #tickAll()} every MC tick to drive position updates.
 *
 * <p>Orphan recovery via {@link #cancelForNpc(long, ColonyItemBank, UUID)}
 * returns in-flight items to the warehouse when an NPC dies or despawns.
 */
public class ItemTransportManager {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Duration in MC ticks for a single item to traverse from start to end. */
    public static final int DURATION_TICKS = 30; // ~1.5 seconds

    private final List<ActiveTransport> active = new ArrayList<>();

    // ── Public API ──

    /**
     * Send an item from {@code from} to {@code to}, returning a future
     * that completes when the visual transport finishes.
     *
     * @param key      the item to transport
     * @param from     start position (warehouse / storage building)
     * @param to       destination (NPC position or build target)
     * @param level    the server level to spawn the visual entity in
     * @param ownerNpcId ECS entity ID of the requesting NPC (for orphan recovery)
     * @return future that completes when the item reaches {@code to}
     */
    public CompletableFuture<Void> send(ItemKey key, BlockPos from, BlockPos to,
                                        Level level, long ownerNpcId) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        ItemEntity entity = spawnVisual(key, from, level);
        if (entity == null) {
            future.complete(null);
            return future;
        }
        ActiveTransport t = new ActiveTransport(future, entity, key,
                Vec3.atCenterOf(from), Vec3.atCenterOf(to),
                ownerNpcId, 0, DURATION_TICKS);
        active.add(t);
        LOGGER.debug("[Transport] send {} {} → {} (npc={}, {} ticks)",
                key.itemId(), from, to, ownerNpcId, DURATION_TICKS);
        return future;
    }

    /**
     * Cancel all in-flight transports for the given NPC.
     * Items are returned to the warehouse, entities are discarded,
     * and futures are cancelled.
     */
    public void cancelForNpc(long npcId,
                             com.wsteam.wandscape.warehouse.ColonyItemBank bank,
                             UUID colonyId) {
        var toCancel = new ArrayList<ActiveTransport>();
        for (ActiveTransport t : active) {
            if (t.ownerNpcId == npcId) toCancel.add(t);
        }
        for (ActiveTransport t : toCancel) {
            bank.add(colonyId, t.itemKey, 1);
            t.entity.discard();
            t.future.cancel(false);
            active.remove(t);
            LOGGER.info("[Transport] orphan recovery: {} returned to warehouse (npc={})",
                    t.itemKey.itemId(), npcId);
        }
    }

    /**
     * Drive all active transports forward by one tick.
     * Call every MC tick from {@code onServerTick}.
     */
    public void tickAll() {
        if (active.isEmpty()) return;

        var arrived = new ArrayList<ActiveTransport>();

        for (ActiveTransport t : active) {
            // ── Neutralize ItemEntity.tick() interference ──
            // Entity tick runs BEFORE tickAll each frame. It overwrites:
            //   noPhysics  (→ collision)
            //   xo/yo/zo   (→ lerp base)
            //   deltaMovement (→ accumulates gravity/friction/drag)
            //   age        (→ lifespan expiry / merging)
            // We must re-apply ALL physics disablers every frame.
            t.entity.noPhysics = true;
            t.entity.setNoGravity(true);
            t.entity.setDeltaMovement(Vec3.ZERO);
            t.entity.setUnlimitedLifetime();  // age = -32768, prevents expiry + merge
            t.entity.setPickUpDelay(Short.MAX_VALUE);

            t.elapsed++;
            if (t.elapsed >= t.duration) {
                arrived.add(t);
            } else {
                double progress = (double) t.elapsed / t.duration;
                Vec3 pos = lerp(t.from, t.to, progress);

                // Capture old position BEFORE moving — client interpolates xo→x
                t.entity.xo = t.entity.getX();
                t.entity.yo = t.entity.getY();
                t.entity.zo = t.entity.getZ();
                t.entity.xOld = t.entity.getX();
                t.entity.yOld = t.entity.getY();
                t.entity.zOld = t.entity.getZ();

                // Force entity tracker to send a position update this tick.
                // Without this, a zero-velocity entity gets sparse teleport packets.
                t.entity.hasImpulse = true;

                t.entity.setPos(pos.x, pos.y, pos.z);
            }
        }

        for (ActiveTransport t : arrived) {
            // Snap to final position, discard, and signal completion
            t.entity.setPos(t.to.x, t.to.y, t.to.z);
            t.entity.discard();
            t.future.complete(null);
            active.remove(t);
        }
    }

    public int pendingCount() {
        return active.size();
    }

    // ── Internal ──

    /** Spawn a no-pickup, no-physics, no-gravity ItemEntity for visual only. */
    @Nullable
    private static ItemEntity spawnVisual(ItemKey key, BlockPos pos, Level level) {
        var item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(key.itemId()));
        if (item == null) {
            LOGGER.warn("[Transport] unknown item: {}", key.itemId());
            return null;
        }
        ItemStack stack = new ItemStack(item, 1);
        if (key.nbt() != null && !key.nbt().isEmpty()) {
            stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                    net.minecraft.world.item.component.CustomData.of(key.nbt().copy()));
        }
        Vec3 center = Vec3.atCenterOf(pos);
        ItemEntity entity = new ItemEntity(level, center.x, center.y + 0.5, center.z, stack);
        entity.setPickUpDelay(Short.MAX_VALUE);
        entity.setUnlimitedLifetime();
        entity.setNoGravity(true);
        entity.noPhysics = true;
        entity.hasImpulse = true;
        level.addFreshEntity(entity);
        return entity;
    }

    /** Linear interpolation with a gentle arc. */
    private static Vec3 lerp(Vec3 from, Vec3 to, double t) {
        double x = from.x + (to.x - from.x) * t;
        double y = from.y + (to.y - from.y) * t + Math.sin(t * Math.PI) * 1.5;
        double z = from.z + (to.z - from.z) * t;
        return new Vec3(x, y, z);
    }

    // ── Record ──

    private static class ActiveTransport {
        final CompletableFuture<Void> future;
        final ItemEntity entity;
        final ItemKey itemKey;
        final Vec3 from, to;
        final long ownerNpcId;
        int elapsed;
        final int duration;

        ActiveTransport(CompletableFuture<Void> future, ItemEntity entity,
                       ItemKey itemKey, Vec3 from, Vec3 to,
                       long ownerNpcId, int elapsed, int duration) {
            this.future = future;
            this.entity = entity;
            this.itemKey = itemKey;
            this.from = from;
            this.to = to;
            this.ownerNpcId = ownerNpcId;
            this.elapsed = elapsed;
            this.duration = duration;
        }
    }
}
