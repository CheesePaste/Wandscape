package com.wsteam.wandscape.engine.boundary;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.core.boundary.BlockOps;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.op.AtomicOp;
import com.wsteam.wandscape.core.op.OpExecutor;
import com.wsteam.wandscape.core.op.OpResult;

/**
 * Async TransformOp executor — exercises the V2.5 CompletableFuture gating.
 *
 * <p>Each TransformOp:
 * <ol>
 *   <li>Registers a CompletableFuture via {@link World#startAsyncOp} → gate closes</li>
 *   <li>Stores a {@link Pending} record with countdown ticks</li>
 *   <li>Returns WAITING → engine tick pauses</li>
 *   <li>{@link #tickAll()} (called each MC tick from Wandscape.onServerTick)
 *       decrements counters and completes expired futures</li>
 *   <li>When the future completes, actual block placement executes</li>
 *   <li>Gate re-opens when all futures resolve → next engine logic tick</li>
 * </ol>
 *
 * <p>Set {@code V1_ASYNC_DELAY_TICKS = 0} for sync mode (standard TransformExecutor).
 */
public class AsyncTransformExecutor implements OpExecutor<AtomicOp.TransformOp> {

    private static final Logger LOGGER = LogUtils.getLogger();
    private final int delayTicks;

    record Pending(CompletableFuture<Void> future, AtomicOp.TransformOp op, World world, long npcId,
                   int remainingTicks) {}

    private final List<Pending> pending = new ArrayList<>();

    public AsyncTransformExecutor(int delayTicks) {
        this.delayTicks = delayTicks;
        LOGGER.info("AsyncTransformExecutor delay={} ticks", delayTicks);
    }

    @Override
    public Class<AtomicOp.TransformOp> opType() {
        return AtomicOp.TransformOp.class;
    }

    @Override
    public OpResult execute(AtomicOp.TransformOp op, World world, long npcId) {
        if (delayTicks <= 0) {
            // Sync fallback
            BlockOps blockOps = world.blockOps;
            if (blockOps != null) {
                blockOps.setBlock(op.target(), op.to());
            }
            return OpResult.DONE;
        }

        // ① Register future → gate closes
        CompletableFuture<Void> future = world.startAsyncOp(
                "place_" + op.to().id() + "_" + op.target());

        // ② Store for delayed completion + actual placement
        pending.add(new Pending(future, op, world, npcId, delayTicks));

        // ③ Hook: when future completes, do the actual block placement
        future.thenRun(() -> {
            Pending p = findPending(future);
            if (p == null) return;
            pending.remove(p);
            if (p.world.blockOps != null) {
                p.world.blockOps.setBlock(p.op.target(), p.op.to());
            }
            LOGGER.debug("async TransformOp DONE: {}→{} at {}",
                    p.op.from().id(), p.op.to().id(), p.op.target());
        });

        LOGGER.debug("async TransformOp started: {}→{} at {} ({} ticks)",
                op.from().id(), op.to().id(), op.target(), delayTicks);
        return OpResult.WAITING;
    }

    /**
     * Called every MC tick. Decrements all countdowns and completes expired futures.
     */
    public void tickAll() {
        if (pending.isEmpty()) return;

        int completed = 0;
        Iterator<Pending> iter = pending.iterator();
        while (iter.hasNext()) {
            Pending p = iter.next();
            int remaining = p.remainingTicks() - 1;
            if (remaining <= 0) {
                p.future().complete(null);
                completed++;
            } else {
                // Replace with decremented ticks (record is immutable)
                int idx = pending.indexOf(p);
                pending.set(idx, new Pending(p.future(), p.op(), p.world(), p.npcId(), remaining));
            }
        }
        if (completed > 0) {
            LOGGER.debug("async tickAll: {} completed, {} remaining",
                    completed, pending.size());
        }
    }

    public boolean hasPendingOps() {
        return !pending.isEmpty();
    }

    private Pending findPending(CompletableFuture<Void> future) {
        for (Pending p : pending) {
            if (p.future() == future) return p;
        }
        return null;
    }
}
