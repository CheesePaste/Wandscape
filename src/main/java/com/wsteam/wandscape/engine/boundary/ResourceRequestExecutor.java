package com.wsteam.wandscape.engine.boundary;

import com.wsteam.wandscape.core.boundary.ColonyResourceAccess;
import com.wsteam.wandscape.core.component.ColonyMember;
import com.wsteam.wandscape.core.component.Inventory;
import com.wsteam.wandscape.core.component.Position;
import com.wsteam.wandscape.core.component.TaskExecutor;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.op.AtomicOp;
import com.wsteam.wandscape.core.op.OpExecutor;
import com.wsteam.wandscape.core.op.ResourceShortageException;
import com.wsteam.wandscape.core.types.ResourceStack;
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

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Executes {@link AtomicOp.ResourceRequestOp} by starting an item transport
 * chain from the colony warehouse to the NPC.
 *
 * <p>Each item flies visually via {@link ItemTransportManager}.
 * All transports are chained serially; the returned future completes
 * when the last item arrives and is added to the NPC's inventory.
 *
 * <p>On resource shortage throws {@link ResourceShortageException}
 * (wrapped in a failed future), which the engine recognizes and
 * converts into an AWAITING_RESOURCES state transition.
 */
public class ResourceRequestExecutor implements OpExecutor<AtomicOp.ResourceRequestOp> {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final ItemTransportManager transporter;

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

        // 1. Check stock
        if (!resources.hasEnough(requested.resource(), requested.amount())) {
            return CompletableFuture.failedFuture(
                    new ResourceShortageException(requested));
        }

        // 2. Reserve
        if (!resources.reserve(requested.resource(), requested.amount())) {
            return CompletableFuture.failedFuture(
                    new ResourceShortageException(requested));
        }

        // 3. Resolve positions
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
        String resourceName = requested.resource().id();
        int amount = requested.amount();

        // 4. Build transport chain: one ItemEntity per item, serial
        ItemKey itemKey = ItemKey.of(resourceName, null);
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);

        for (int i = 0; i < amount; i++) {
            final int idx = i;
            chain = chain.thenCompose(v ->
                    transporter.send(itemKey, warehousePos, npcPos, npc.level(), npcId));
        }

        // 5. On all arrivals: add to inventory + commit
        chain = chain.thenRun(() -> {
            Inventory inv = world.get(npcId, Inventory.class);
            if (inv == null || !inv.add(requested)) {
                // Inventory full — items lost (NPC should have been checked earlier)
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
                    npcId, amount, resourceName);
        });

        LOGGER.info("[ResourceReq] NPC {} requesting {} x {} ({} ticks est.)",
                npcId, amount, resourceName,
                amount * ItemTransportManager.DURATION_TICKS);

        return chain;
    }

    // ── Helpers (shared logic with WandEquipExecutor) ──

    private static UUID resolveColonyId(WandscapeNpc npc, World world) {
        var member = world.get(npc.ecsEntityId, ColonyMember.class);
        if (member != null && member.colonyId() != null) {
            return member.colonyId();
        }
        return npc.colonyId != null ? npc.colonyId : new UUID(0, 0);
    }

    private static BlockPos findNearestStorage(UUID colonyId, BlockPos npcPos) {
        BuildingApi buildingApi = WandscapeApis.getBuildingApi();
        if (buildingApi == null) return null;

        var storageIds = buildingApi.getBuildingsByCategory(colonyId, "storage");
        if (storageIds == null || storageIds.isEmpty()) return null;

        BlockPos nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (UUID id : storageIds) {
            BuildingData bd = buildingApi.getBuilding(id);
            if (bd == null || bd.isShutdown()) continue;
            BlockPos pos = bd.getPosition();
            double dist = pos.distSqr(npcPos);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = pos;
            }
        }
        return nearest;
    }
}
