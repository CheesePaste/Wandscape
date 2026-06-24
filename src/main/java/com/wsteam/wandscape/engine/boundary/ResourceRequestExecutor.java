package com.wsteam.wandscape.engine.boundary;

import com.wsteam.wandscape.core.boundary.ColonyResourceAccess;
import com.wsteam.wandscape.core.component.ColonyMember;
import com.wsteam.wandscape.core.component.Inventory;
import com.wsteam.wandscape.core.component.TaskExecutor;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.op.AtomicOp;
import com.wsteam.wandscape.core.op.OpExecutor;
import com.wsteam.wandscape.core.op.ResourceShortageException;
import com.wsteam.wandscape.core.types.ResourceStack;
import com.wsteam.wandscape.core.road.PathPoint;
import com.wsteam.wandscape.core.road.RoadRouter;
import com.wsteam.wandscape.core.road.RouteSegment;
import com.wsteam.wandscape.engine.transport.ItemTransportManager;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.npc.internal.EntityComponentBridge;
import com.wsteam.wandscape.shared.api.BuildingApi;
import com.wsteam.wandscape.shared.data.BuildingData;
import com.wsteam.wandscape.shared.data.ItemKey;
import com.wsteam.wandscape.shared.registry.WandscapeApis;

import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Executes {@link AtomicOp.ResourceRequestOp} by starting item transport
 * from the colony warehouse to the NPC.
 *
 * <p>Items are launched 1 per tick (staggered) for a visual stream effect.
 * The returned future completes when ALL items have arrived and inventory
 * has been credited.
 *
 * <p>On resource shortage throws {@link ResourceShortageException}
 * (wrapped in a failed future), which the engine recognizes and
 * converts into an AWAITING_RESOURCES state transition.
 */
public class ResourceRequestExecutor implements OpExecutor<AtomicOp.ResourceRequestOp> {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final ItemTransportManager transporter;

    /** Pending staggered launches — one per tick. */
    private final List<PendingBatch> batches = new ArrayList<>();

    private record PendingBatch(CompletableFuture<Void> doneFuture,
                                int launchCountdown, // ticks until next send()
                                int itemsRemaining,  // how many yet to launch
                                List<CompletableFuture<Void>> inFlight, // completed send() futures
                                ResourceStack requested,
                                ColonyResourceAccess resources,
                                long npcId, World world,
                                BlockPos from, BlockPos to,
                                net.minecraft.world.level.Level level,
                                List<RouteSegment> route) {}

    public ResourceRequestExecutor(ItemTransportManager transporter) {
        this.transporter = transporter;
    }

    @Override
    public Class<AtomicOp.ResourceRequestOp> opType() {
        return AtomicOp.ResourceRequestOp.class;
    }

    @Override
    public CompletableFuture<Void> execute(AtomicOp.ResourceRequestOp op, World world, long npcId) {
        ResourceStack requested = op.requested();
        ColonyResourceAccess resources = world.colonyResources;

        // 1. Check stock & reserve
        if (!resources.hasEnough(requested.resource(), requested.amount())) {
            return CompletableFuture.failedFuture(new ResourceShortageException(requested));
        }
        if (!resources.reserve(requested.resource(), requested.amount())) {
            return CompletableFuture.failedFuture(new ResourceShortageException(requested));
        }

        // 2. Resolve positions
        WandscapeNpc npc = EntityComponentBridge.INSTANCE.getNpc(npcId);
        if (npc == null || npc.isRemoved()) {
            resources.release(requested.resource(), requested.amount());
            return CompletableFuture.failedFuture(
                    new IllegalStateException("[ResourceReq] NPC " + npcId + " not found"));
        }
        UUID colonyId = resolveColonyId(npc, world);
        BlockPos warehousePos = findNearestStorage(colonyId, npc.blockPosition());
        if (warehousePos == null) {
            resources.release(requested.resource(), requested.amount());
            return CompletableFuture.failedFuture(
                    new IllegalStateException("[ResourceReq] no storage for colony " + colonyId));
        }

        BlockPos npcPos = npc.blockPosition();
        List<RouteSegment> route = planRoute(colonyId, warehousePos, npcPos);

        // 3. Launch first item immediately, queue the rest 1/tick
        CompletableFuture<Void> doneFuture = new CompletableFuture<>();
        List<CompletableFuture<Void>> inFlight = new ArrayList<>();

        String itemId = requested.resource().id();
        ItemKey key = ItemKey.of(itemId, null);
        inFlight.add(transporter.send(key, warehousePos, npcPos, npc.level(), npcId, route));

        int remaining = requested.amount() - 1;
        if (remaining > 0) {
            batches.add(new PendingBatch(doneFuture,
                    1 /* next launch in 1 tick */,
                    remaining, inFlight,
                    requested, resources, npcId, world,
                    warehousePos, npcPos, npc.level(), route));
        } else {
            // Only 1 item — no staggering needed, complete when it arrives
            inFlight.get(0).thenRun(() -> finish(doneFuture, requested, resources, world, npcId));
        }

        LOGGER.info("[ResourceReq] NPC {} requesting {} x {} ({} staggered)",
                npcId, requested.amount(), itemId, remaining);
        return doneFuture;
    }

    /** Called every MC tick. Decrements counters, launches items, checks completion. */
    public void tickAll() {
        if (batches.isEmpty()) return;

        for (int i = 0; i < batches.size(); i++) {
            PendingBatch b = batches.get(i);
            int cd = b.launchCountdown() - 1;

            if (cd <= 0 && b.itemsRemaining() > 0) {
                // Launch one more item now
                String itemId = b.requested().resource().id();
                ItemKey key = ItemKey.of(itemId, null);
                b.inFlight().add(transporter.send(
                        key, b.from(), b.to(), b.level(), -1, b.route()));

                int newRemaining = b.itemsRemaining() - 1;
                if (newRemaining > 0) {
                    batches.set(i, new PendingBatch(b.doneFuture(),
                            1 /* next in 1 tick */, newRemaining,
                            b.inFlight(), b.requested(), b.resources(),
                            b.npcId(), b.world(), b.from(), b.to(),
                            b.level(), b.route()));
                } else {
                    // All items launched — wait for all to arrive
                    batches.remove(i);
                    i--;
                    CompletableFuture.allOf(b.inFlight().toArray(new CompletableFuture[0]))
                            .thenRun(() -> finish(b.doneFuture(), b.requested(),
                                    b.resources(), b.world(), b.npcId()));
                    LOGGER.debug("[ResourceReq] all {} items launched, awaiting arrivals",
                            b.inFlight().size());
                }
            } else if (b.itemsRemaining() > 0) {
                // Still counting down
                batches.set(i, new PendingBatch(b.doneFuture(),
                        cd, b.itemsRemaining(), b.inFlight(),
                        b.requested(), b.resources(), b.npcId(), b.world(),
                        b.from(), b.to(), b.level(), b.route()));
            }
        }
    }

    public boolean hasPendingBatches() { return !batches.isEmpty(); }

    private void finish(CompletableFuture<Void> doneFuture, ResourceStack requested,
                        ColonyResourceAccess resources, World world, long npcId) {
        Inventory inv = world.get(npcId, Inventory.class);
        if (inv == null || !inv.add(requested)) {
            resources.release(requested.resource(),
                    requested.amount() - (inv != null ? inv.count(requested.resource()) : 0));
            LOGGER.warn("[ResourceReq] NPC {} inventory full, released remaining {}",
                    npcId, requested.resource().id());
        } else {
            resources.commit(requested.resource(), requested.amount());
        }
        TaskExecutor exec = world.get(npcId, TaskExecutor.class);
        if (exec != null) {
            exec.state = com.wsteam.wandscape.core.task.ExecutorState.ACTIVE;
        }
        LOGGER.debug("[ResourceReq] NPC {} received {} x {}",
                npcId, requested.amount(), requested.resource().id());
        doneFuture.complete(null);
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
        var ids = api.getBuildingsByCategory(colonyId, "storage");
        if (ids == null || ids.isEmpty()) return null;
        BlockPos nearest = null;
        double best = Double.MAX_VALUE;
        for (UUID id : ids) {
            BuildingData bd = api.getBuilding(id);
            if (bd == null || bd.isShutdown()) continue;
            BlockPos p = bd.getPosition();
            double d = p.distSqr(npcPos);
            if (d < best) { best = d; nearest = p; }
        }
        return nearest;
    }

    private static List<RouteSegment> planRoute(UUID colonyId, BlockPos from, BlockPos to) {
        try {
            var roadApi = WandscapeApis.getRoadApi();
            if (roadApi == null) return List.of();
            return RoadRouter.plan(roadApi.getNetwork(colonyId),
                    new PathPoint(from.getX(), from.getY(), from.getZ()),
                    new PathPoint(to.getX(), to.getY(), to.getZ()));
        } catch (Exception e) {
            return List.of();
        }
    }
}
