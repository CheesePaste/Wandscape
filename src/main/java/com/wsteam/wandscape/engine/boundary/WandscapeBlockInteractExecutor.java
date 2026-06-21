package com.wsteam.wandscape.engine.boundary;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.core.boundary.BlockOps;
import com.wsteam.wandscape.core.boundary.ColonyResourceAccess;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.op.AtomicOp;
import com.wsteam.wandscape.core.op.OpExecutor;
import com.wsteam.wandscape.core.types.ResourceId;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.npc.internal.EntityComponentBridge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;

/**
 * MC implementation of {@link OpExecutor} for {@link AtomicOp.BlockInteractOp}.
 *
 * <p>Sync actions (toggle/activate/open_gui) execute immediately via {@link BlockOps}.
 *
 * <p>Async actions (gather/decompose/synthesize) use a countdown + thenRun callback.
 * Mana is consumed by {@link com.wsteam.wandscape.core.system.TaskExecutionSystem}
 * BEFORE execution — this executor only handles the timing and side effects.
 *
 * <p>Registered in {@code EngineBootstrap} and ticked via {@link #tickAll()} from
 * the server tick loop.
 */
public class WandscapeBlockInteractExecutor implements OpExecutor<AtomicOp.BlockInteractOp> {

    private static final Logger LOGGER = LogUtils.getLogger();

    record Pending(CompletableFuture<Void> future, AtomicOp.BlockInteractOp op,
                   World world, long npcId, int remainingTicks) {}

    private final List<Pending> pending = new ArrayList<>();

    @Override
    public Class<AtomicOp.BlockInteractOp> opType() {
        return AtomicOp.BlockInteractOp.class;
    }

    @Override
    public CompletableFuture<Void> execute(AtomicOp.BlockInteractOp op, World world, long npcId) {
        String action = op.action().id();

        // ── Sync actions ──
        if ("toggle".equals(action) || "activate".equals(action) || "open_gui".equals(action)) {
            BlockOps blockOps = world.blockOps;
            if (blockOps != null) {
                switch (action) {
                    case "toggle"   -> blockOps.toggle(op.target());
                    case "activate" -> blockOps.activate(op.target());
                    case "open_gui" -> blockOps.openGui(op.target());
                }
            }
            return CompletableFuture.completedFuture(null);
        }

        // ── Async actions ──
        if (op.channelTicks() <= 0) {
            // Zero ticks → execute immediately
            executeAsyncAction(op, world, npcId);
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> future = world.startAsyncOp(
                "block_interact_" + action + "_" + op.target());
        pending.add(new Pending(future, op, world, npcId, op.channelTicks()));

        future.thenRun(() -> executeAsyncAction(op, world, npcId));

        LOGGER.debug("block_interact {}: NPC {} channeling at {} ({} ticks)",
                action, npcId, op.target(), op.channelTicks());
        return future;
    }

    /** Called every MC tick. Decrements countdowns and completes futures. */
    public void tickAll() {
        if (pending.isEmpty()) return;

        List<CompletableFuture<Void>> toComplete = new ArrayList<>();

        for (int i = 0; i < pending.size(); i++) {
            Pending p = pending.get(i);
            int remaining = p.remainingTicks() - 1;
            if (remaining <= 0) {
                toComplete.add(p.future());
            } else {
                pending.set(i, new Pending(p.future(), p.op(), p.world(), p.npcId(), remaining));
            }
        }

        for (CompletableFuture<Void> f : toComplete) {
            f.complete(null); // → triggers thenRun → executeAsyncAction
        }

        pending.removeIf(p -> p.future().isDone());

        if (!toComplete.isEmpty()) {
            LOGGER.debug("block_interact tickAll: {} completed, {} remaining",
                    toComplete.size(), pending.size());
        }
    }

    public boolean hasPendingOps() {
        return !pending.isEmpty();
    }

    // ── Action implementations ──

    private void executeAsyncAction(AtomicOp.BlockInteractOp op, World world, long npcId) {
        String action = op.action().id();
        Map<String, String> params = op.params();

        switch (action) {
            case "gather" -> executeGather(params, world, npcId);
            case "decompose" -> LOGGER.warn("decompose not yet implemented");
            case "synthesize" -> LOGGER.warn("synthesize not yet implemented");
            default -> LOGGER.warn("Unknown async block_interact action: {}", action);
        }
    }

    private void executeGather(Map<String, String> params, World world, long npcId) {
        String element = params.getOrDefault("element", "wood");
        int amount = parseAmount(params);

        ColonyResourceAccess resources = world.colonyResources;
        if (resources == null) {
            LOGGER.warn("block_interact gather: colonyResources is null, cannot inject {}", element);
            return;
        }
        resources.addResource(new ResourceId(element), amount);

        // Visual feedback on the NPC
        WandscapeNpc npc = EntityComponentBridge.INSTANCE.getNpc(npcId);
        if (npc != null && !npc.isRemoved()) {
            for (int i = 0; i < 15; i++) {
                double ox = (npc.getRandom().nextDouble() - 0.5) * 1.0;
                double oy = npc.getRandom().nextDouble() * 2.0;
                double oz = (npc.getRandom().nextDouble() - 0.5) * 1.0;
                npc.level().addParticle(ParticleTypes.HAPPY_VILLAGER,
                        npc.getX() + ox, npc.getY() + oy, npc.getZ() + oz,
                        0, 0, 0);
            }
        }
        LOGGER.info("block_interact gather complete: {} x{} → colony warehouse", element, amount);
    }

    private static int parseAmount(Map<String, String> params) {
        if (params == null) return 10;
        try {
            String raw = params.get("amount");
            return raw != null ? Integer.parseInt(raw) : 10;
        } catch (NumberFormatException e) {
            return 10;
        }
    }
}
