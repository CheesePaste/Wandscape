package com.wsteam.wandscape.engine.boundary;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.core.boundary.BlockOps;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.op.AtomicOp;
import com.wsteam.wandscape.core.op.OpExecutor;
import com.wsteam.wandscape.core.op.OpResult;
import com.wsteam.wandscape.core.types.GridPos;

/**
 * Async TransformOp executor — exercises the V2.5 CompletableFuture gating.
 *
 * <p>Two-phase execution:
 * <ol>
 *   <li>First call: creates a CompletableFuture → returns WAITING → gate closes</li>
 *   <li>MC ticks count down via {@link #tickAll()} → future completes →
 *       block placed by callback</li>
 *   <li>Engine tick resumes, re-invokes same TransformOp → recognized as
 *       already-done via {@code started} set → returns DONE → stepIndex advances</li>
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
    /** Ops whose futures have completed (or are in-flight). Second call returns DONE. */
    private final Set<GridPos> started = new HashSet<>();

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
            BlockOps blockOps = world.blockOps;
            if (blockOps != null) {
                blockOps.setBlock(op.target(), op.to());
            }
            return OpResult.DONE;
        }

        // Already in-flight or completed → return DONE so engine advances stepIndex
        if (started.contains(op.target())) {
            started.remove(op.target());
            LOGGER.debug("async TransformOp DONE (second call): {}→{} at {}",
                    op.from().id(), op.to().id(), op.target());
            return OpResult.DONE;
        }

        // First call: register async op → gate closes → return WAITING
        started.add(op.target());
        CompletableFuture<Void> future = world.startAsyncOp(
                "place_" + op.to().id() + "_" + op.target());
        pending.add(new Pending(future, op, world, npcId, delayTicks));

        // Callback: place block when future completes
        future.thenRun(() -> {
            Pending p = findPending(future);
            if (p == null) return;
            pending.remove(p);
            if (p.world.blockOps != null) {
                p.world.blockOps.setBlock(p.op.target(), p.op.to());
            }
            LOGGER.debug("async TransformOp placed: {}→{} at {}",
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

        // Collect expired futures BEFORE calling complete() — complete() triggers
        // thenRun which modifies pending, so we must not iterate while modifying.
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
            f.complete(null);
        }

        if (!toComplete.isEmpty()) {
            LOGGER.debug("async tickAll: {} completed, {} remaining in-flight",
                    toComplete.size(), pending.size());
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
