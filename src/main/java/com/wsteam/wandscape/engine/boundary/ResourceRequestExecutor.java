package com.wsteam.wandscape.engine.boundary;

import com.wsteam.wandscape.core.boundary.ColonyResourceAccess;
import com.wsteam.wandscape.core.component.ColonyMember;
import com.wsteam.wandscape.core.component.Inventory;
import com.wsteam.wandscape.core.component.TaskExecutor;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.types.ResourceStack;
import com.wsteam.wandscape.engine.transport.ItemTransportManager;
import com.wsteam.wandscape.content.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.content.npc.internal.EntityComponentBridge;
import com.wsteam.wandscape.content.task.op.api.AtomicOp;
import com.wsteam.wandscape.content.task.op.executor.OpExecutor;
import com.wsteam.wandscape.content.task.op.executor.ResourceShortageException;
import com.wsteam.wandscape.api.BuildingApi;
import com.wsteam.wandscape.content.building.data.BuildingData;
import com.wsteam.wandscape.foundation.util.ItemKey;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.content.task.runtime.ExecutorState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Executes {@link AtomicOp.ResourceRequestOp} by starting item transport
 * from the colony warehouse to the NPC.
 *
 * <p><b>All-or-nothing semantics:</b> every item in the request is checked
 * against the warehouse before any is reserved. If any item is short, the
 * entire request fails — no partial deduction.
 *
 * <p><b>Staggered launch:</b> the first item launches immediately; subsequent
 * items launch one per {@link #STAGGER_TICKS} ticks for a visual stream effect.
 * The flight is cosmetic — items never enter the NPC inventory. When all
 * transports have arrived, the reserved materials are committed (deducted from
 * the warehouse) in one batch. This is the "charge at construction start" point:
 * bulk, once, and independent of NPC backpack capacity.
 *
 * <p>On resource shortage throws {@link ResourceShortageException}
 * (wrapped in a failed future), which the engine recognizes and
 * converts into an AWAITING_RESOURCES state transition.
 */
public class ResourceRequestExecutor implements OpExecutor<AtomicOp.ResourceRequestOp> {

    private static final String TAG = "ResourceRequestExecutor";

    private final ItemTransportManager transporter;

    /** Ticks between staggered item launches (visual stream spacing). */
    private static final int STAGGER_TICKS = 5;

    /** Pending batches — driven by {@link #tickAll} each MC tick. */
    private final List<PendingBatch> batches = new ArrayList<>();

    /**
     * A single item-entity to launch in the staggered sequence.
     * Each resource stack of N items becomes 1 launch entry with a count.
     */
    private record LaunchEntry(ItemKey key, int count, BlockPos from, BlockPos to,
                               Level level, long npcId) {}

    private static final class PendingBatch {
        final CompletableFuture<Void> doneFuture;
        int launchCountdown;
        final List<LaunchEntry> remaining;   // items yet to launch
        final List<CompletableFuture<Void>> inFlight; // send() futures
        final List<ResourceStack> needs;     // reserved resources (for rollback)
        final ColonyResourceAccess resources;
        final long npcId;
        final World world;
        final int totalItems;                // total items in this batch (for logging)

        PendingBatch(CompletableFuture<Void> doneFuture, int launchCountdown,
                     List<LaunchEntry> remaining, List<CompletableFuture<Void>> inFlight,
                     List<ResourceStack> needs, ColonyResourceAccess resources,
                     long npcId, World world, int totalItems) {
            this.doneFuture = doneFuture;
            this.launchCountdown = launchCountdown;
            this.remaining = remaining;
            this.inFlight = inFlight;
            this.needs = needs;
            this.resources = resources;
            this.npcId = npcId;
            this.world = world;
            this.totalItems = totalItems;
        }
    }

    public ResourceRequestExecutor(ItemTransportManager transporter) {
        this.transporter = transporter;
    }

    @Override
    public Class<AtomicOp.ResourceRequestOp> opType() {
        return AtomicOp.ResourceRequestOp.class;
    }

    @Override
    public CompletableFuture<Void> execute(AtomicOp.ResourceRequestOp op, World world, long npcId) {
        List<ResourceStack> items = op.items();
        ColonyResourceAccess resources = world.colonyResources;

        // ── 1. Compute shortfalls (NPC inventory offsets) ──
        Inventory inv = world.get(npcId, Inventory.class);
        List<ResourceStack> needs = new ArrayList<>();
        for (ResourceStack item : items) {
            int alreadyHas = inv != null ? inv.count(item.resource()) : 0;
            int shortfall = item.amount() - alreadyHas;
            if (shortfall > 0) {
                needs.add(item.withAmount(shortfall));
            }
        }

        if (needs.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        // ── 2. All-or-nothing check: verify ALL warehouse stock before reserving any ──
        for (ResourceStack need : needs) {
            if (!resources.hasEnough(need.resource(), need.amount())) {
                return CompletableFuture.failedFuture(new ResourceShortageException(items));
            }
        }

        // ── 3. Reserve ALL ──
        for (ResourceStack need : needs) {
            if (!resources.reserve(need.resource(), need.amount())) {
                for (int j = 0; j < needs.indexOf(need); j++) {
                    resources.release(needs.get(j).resource(), needs.get(j).amount());
                }
                return CompletableFuture.failedFuture(new ResourceShortageException(items));
            }
        }

        // ── 4. Resolve positions ──
        WandscapeNpc npc = EntityComponentBridge.INSTANCE.getNpc(npcId);
        if (npc == null || npc.isRemoved()) {
            for (ResourceStack need : needs) {
                resources.release(need.resource(), need.amount());
            }
            return CompletableFuture.failedFuture(
                    new IllegalStateException("[ResourceReq] NPC " + npcId + " not found"));
        }
        UUID colonyId = resolveColonyId(npc, world);
        BlockPos warehousePos = findNearestStorage(colonyId, npc.blockPosition());
        if (warehousePos == null) {
            for (ResourceStack need : needs) {
                resources.release(need.resource(), need.amount());
            }
            return CompletableFuture.failedFuture(
                    new IllegalStateException("[ResourceReq] no storage for colony " + colonyId));
        }

        BlockPos npcPos = npc.blockPosition();
        Level level = npc.level();

        // ── 5. Build flat launch entry list (one entry per item-entity) ──
        List<LaunchEntry> entries = new ArrayList<>();
        for (ResourceStack need : needs) {
            ItemKey key = ItemKey.of(need.resource().id(), null);
            entries.add(new LaunchEntry(key, need.amount(), warehousePos, npcPos, level, npcId));
        }

        int totalItems = entries.size();
        CompletableFuture<Void> doneFuture = new CompletableFuture<>();
        List<CompletableFuture<Void>> inFlight = new ArrayList<>();

        // ── 6. Launch first item immediately ──
        LaunchEntry first = entries.remove(0);
        inFlight.add(launch(first));

        // ── 7. Queue remaining for staggered launch ──
        int remainingCount = entries.size();
        if (remainingCount > 0) {
            batches.add(new PendingBatch(doneFuture, STAGGER_TICKS,
                    entries, inFlight, List.copyOf(needs),
                    resources, npcId, world, totalItems));
        } else {
            // Only 1 item — complete when it arrives
            inFlight.get(0).thenRun(() ->
                    finish(doneFuture, needs, resources, world, npcId));
        }

        Log.info(TAG, "[ResourceReq] NPC {} requesting {} items ({} types, {} of {} staggered)",
                npcId, totalItems, needs.size(),
                remainingCount, totalItems);
        return doneFuture;
    }

    /** Called every MC tick. Decrements counters, launches next item, checks completion. */
    public void tickAll() {
        if (batches.isEmpty()) return;

        for (int i = 0; i < batches.size(); i++) {
            PendingBatch b = batches.get(i);
            int cd = b.launchCountdown - 1;

            if (cd <= 0 && !b.remaining.isEmpty()) {
                // Launch next item
                LaunchEntry next = b.remaining.remove(0);
                b.inFlight.add(launch(next));

                int left = b.remaining.size();
                if (left > 0) {
                    b.launchCountdown = STAGGER_TICKS;
                } else {
                    // All launched — wait for all to arrive
                    batches.remove(i);
                    i--;
                    CompletableFuture.allOf(b.inFlight.toArray(new CompletableFuture[0]))
                            .thenRun(() -> finish(b.doneFuture, b.needs,
                                    b.resources, b.world, b.npcId));
                }
            } else if (!b.remaining.isEmpty()) {
                b.launchCountdown = cd;
            }
        }
    }

    public boolean hasPendingBatches() { return !batches.isEmpty(); }

    /**
     * Cancel pending batches for a dead/despawned NPC and release
     * warehouse reservations. Items were reserved but never consumed,
     * so just releasing is correct.
     */
    public void cancelForNpc(long npcId) {
        var toRemove = new ArrayList<PendingBatch>();
        for (PendingBatch b : batches) {
            if (b.npcId == npcId) toRemove.add(b);
        }
        if (toRemove.isEmpty()) return;

        for (PendingBatch b : toRemove) {
            for (ResourceStack rs : b.needs) {
                b.resources.release(rs.resource(), rs.amount());
            }
            for (CompletableFuture<Void> f : b.inFlight) {
                f.cancel(false);
            }
            b.doneFuture.cancel(false);
            Log.info(TAG, "[ResourceReq] cancelForNpc npc={} — released {} resource reservations",
                    npcId, b.needs.size());
        }
        batches.removeAll(toRemove);
    }

    private void finish(CompletableFuture<Void> doneFuture, List<ResourceStack> needs,
                        ColonyResourceAccess resources, World world, long npcId) {
        // Materials are deducted here (construction start) — they never enter the
        // NPC backpack, so a full inventory can't block the charge.
        for (ResourceStack need : needs) {
            if (!resources.commit(need.resource(), need.amount())) {
                resources.release(need.resource(), need.amount());
                Log.warn(TAG, "[ResourceReq] commit failed for {} x{}, released reservation",
                        need.resource().id(), need.amount());
            }
        }

        TaskExecutor exec = world.get(npcId, TaskExecutor.class);
        if (exec != null) {
            exec.state = ExecutorState.ACTIVE;
        }
        doneFuture.complete(null);
    }

    private CompletableFuture<Void> launch(LaunchEntry e) {
        return transporter.send(e.key(), e.count(), e.from(), e.to(), e.level(), e.npcId());
    }

    // ── Helpers ──

    private static UUID resolveColonyId(WandscapeNpc npc, World world) {
        var member = world.get(npc.ecsEntityId, ColonyMember.class);
        if (member != null && member.colonyId() != null) return member.colonyId();
        return npc.colonyId != null ? npc.colonyId : new UUID(0, 0);
    }

    private static BlockPos findNearestStorage(UUID colonyId, BlockPos npcPos) {
        BuildingApi api = WandscapeApis.getBuildingApi();
        if (api == null) return null;
        BlockPos storage = nearestBuildingOfCategory(api, colonyId, "storage", npcPos);
        if (storage != null) return storage;
        // No warehouse: the town hall acts as the delivery point so material
        // requests don't hard-fail (and retry forever) on a town-hall-only colony.
        return nearestBuildingOfCategory(api, colonyId, "government", npcPos);
    }

    private static BlockPos nearestBuildingOfCategory(BuildingApi api, UUID colonyId,
                                                       String category, BlockPos npcPos) {
        var ids = api.getBuildingsByCategory(colonyId, category);
        if (ids == null || ids.isEmpty()) return null;
        BlockPos nearest = null;
        double best = Double.MAX_VALUE;
        for (UUID id : ids) {
            BuildingData bd = api.getBuilding(id);
            if (bd == null) continue;
            BlockPos p = bd.getPosition();
            double d = p.distSqr(npcPos);
            if (d < best) { best = d; nearest = p; }
        }
        return nearest;
    }
}
