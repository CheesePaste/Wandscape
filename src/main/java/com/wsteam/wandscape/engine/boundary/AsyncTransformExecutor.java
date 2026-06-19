package com.wsteam.wandscape.engine.boundary;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.core.boundary.BlockOps;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.op.AtomicOp;
import com.wsteam.wandscape.core.op.OpExecutor;

/**
 * Async TransformOp executor — exercises V2.5 CompletableFuture model.
 *
 * <p>Returns an incomplete future from {@link World#startAsyncOp} (Promise pattern).
 * The engine stores this future in TaskExecutor.pendingFuture and does NOT
 * re-invoke execute(). When the future completes, the engine advances stepIndex.
 *
 * <p>The actual block placement happens via the future's {@code thenRun} callback.
 */
public class AsyncTransformExecutor implements OpExecutor<AtomicOp.TransformOp> {

    private static final Logger LOGGER = LogUtils.getLogger();
    private final int delayTicks;

    record Pending(CompletableFuture<Void> future, AtomicOp.TransformOp op, World world,
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
    public CompletableFuture<Void> execute(AtomicOp.TransformOp op, World world, long npcId) {
        if (delayTicks <= 0) {
            BlockOps blockOps = world.blockOps;
            if (blockOps != null) {
                blockOps.setBlock(op.target(), op.to());
            }
            return CompletableFuture.completedFuture(null);
        }

        // ① Get a promise (CompletableFuture) from the world gate
        CompletableFuture<Void> future = world.startAsyncOp(
                "place_" + op.to().id() + "_" + op.target());

        // ② Schedule: after delayTicks, place block then complete the promise
        //    Engine stores this future in TaskExecutor.pendingFuture,
        //    does NOT re-invoke execute(). When complete() fires, engine
        //    advances stepIndex and calls execute() for the NEXT op.
        pending.add(new Pending(future, op, world, delayTicks));

        // Hook: place block when delay expires
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

        LOGGER.debug("async TransformOp: {}→{} at {} ({} tick delay)",
                op.from().id(), op.to().id(), op.target(), delayTicks);
        return future;
    }

    /** Called every MC tick. Decrements countdowns and completes futures. */
    public void tickAll() {
        if (pending.isEmpty()) return;

        // Collect to-complete BEFORE calling complete() — complete() triggers
        // thenRun which modifies pending, so iterate-copy is required.
        List<CompletableFuture<Void>> toComplete = new ArrayList<>();

        for (int i = 0; i < pending.size(); i++) {
            Pending p = pending.get(i);
            int remaining = p.remainingTicks() - 1;
            if (remaining <= 0) {
                toComplete.add(p.future());
            } else {
                pending.set(i, new Pending(p.future(), p.op(), p.world(), remaining));
            }
        }

        for (CompletableFuture<Void> f : toComplete) {
            f.complete(null); // → triggers thenRun → places block
        }

        if (!toComplete.isEmpty()) {
            LOGGER.debug("async tickAll: {} completed, {} remaining",
                    toComplete.size(), pending.size());
        }
    }

    public boolean hasPendingOps() { return !pending.isEmpty(); }

    private Pending findPending(CompletableFuture<Void> future) {
        for (Pending p : pending) {
            if (p.future() == future) return p;
        }
        return null;
    }
}
