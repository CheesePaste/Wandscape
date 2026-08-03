package com.wsteam.wandscape.engine.boundary;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.wsteam.wandscape.core.boundary.BlockOps;
import com.wsteam.wandscape.core.component.Inventory;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.engine.service.SoundService;
import com.wsteam.wandscape.engine.sound.WandscapeSounds;
import com.wsteam.wandscape.op.api.AtomicOp;
import com.wsteam.wandscape.op.executor.OpExecutor;
import com.wsteam.wandscape.op.executor.ResourceShortageException;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.npc.internal.EntityComponentBridge;
import com.wsteam.wandscape.shared.registry.WandscapeApis;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Async TransformOp executor — exercises V2.5 CompletableFuture model.
 *
 * <p>Returns an incomplete future from {@link World#startAsyncOp} (Promise pattern).
 * The engine stores this future in TaskExecutor.pendingFuture and does NOT
 * re-invoke execute(). When the future completes, the engine advances stepIndex.
 *
 * <p>The actual block placement happens via the future's {@code thenRun} callback.
 *
 * <p>When the op carries a {@link AtomicOp.TransformOp#consumable()}, the item is
 * removed from NPC inventory before the delay countdown starts. On shortage, a
 * {@link ResourceShortageException} is thrown — the engine marks the task
 * AWAITING_RESOURCES and releases the NPC.
 */
public class AsyncTransformExecutor implements OpExecutor<AtomicOp.TransformOp> {

    private static final String TAG = "AsyncTransformExecutor";
    private final int delayTicks;

    record Pending(CompletableFuture<Void> future, AtomicOp.TransformOp op, World world,
                   long npcId, int remainingTicks) {}

    private final List<Pending> pending = new ArrayList<>();

    public AsyncTransformExecutor(int delayTicks) {
        this.delayTicks = delayTicks;
        Log.info(TAG, "AsyncTransformExecutor delay={} ticks", delayTicks);
    }

    @Override
    public Class<AtomicOp.TransformOp> opType() {
        return AtomicOp.TransformOp.class;
    }

    @Override
    public CompletableFuture<Void> execute(AtomicOp.TransformOp op, World world, long npcId) {
        // ── Consumable check: remove from NPC inventory before delay countdown ──
        if (op.consumable() != null) {
            // Strip blockstate to check element mapping. Blocks without element
            // mappings are "free" materials — skip inventory consumption and place
            // directly (they were excluded from warehouse transport by computeMaterialData).
            String pureId = op.consumable().resource().id().replaceAll("\\[.*?\\]", "").trim();
            if (WandscapeApis.getElementApi().hasElementMapping(pureId)) {
                Inventory inv = world.get(npcId, Inventory.class);
                if (inv == null || !inv.hasEnough(op.consumable().resource(),
                        op.consumable().amount())) {
                    return CompletableFuture.failedFuture(
                            new ResourceShortageException(List.of(op.consumable())));
                }
                inv.remove(op.consumable().resource(), op.consumable().amount());
                Log.debug(TAG, "TransformOp consumable: -{} x{} from NPC {}",
                        op.consumable().resource().id(), op.consumable().amount(), npcId);
            } else {
                Log.debug(TAG, "TransformOp free block (no element mapping): {} — placing directly",
                        op.consumable().resource().id());
            }
        }

        // ── Placement (existing delay-tick mechanism, shared by both paths) ──
        if (delayTicks <= 0) {
            BlockOps blockOps = world.blockOps;
            if (blockOps != null) {
                blockOps.setBlock(op.target(), op.to());
                blockOps.setBlockEntityData(op.target(), op.blockNbtBase64());
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
        pending.add(new Pending(future, op, world, npcId, delayTicks));

        // Hook: place block when delay expires
        future.thenRun(() -> {
            Pending p = findPending(future);
            if (p == null) return;
            pending.remove(p);
            if (p.world.blockOps != null) {
                p.world.blockOps.setBlock(p.op.target(), p.op.to());
                p.world.blockOps.setBlockEntityData(p.op.target(), p.op.blockNbtBase64());
            }
            // Visual feedback on the NPC that performed the work
            WandscapeNpc npc = EntityComponentBridge.INSTANCE.getNpc(p.npcId());
            if (npc != null) {
                npc.doWorkAnimation(new BlockPos(
                        p.op.target().x(), p.op.target().y(), p.op.target().z()));
                // NPC 施法放置音（守卫/自防御不走这里，避免与 GuardCombat 开火音重叠）
                if (npc.level() instanceof ServerLevel sl) {
                    SoundService.playAt(sl, p.op.target().x() + 0.5,
                            p.op.target().y() + 0.5, p.op.target().z() + 0.5,
                            WandscapeSounds.NPC_CAST, SoundSource.NEUTRAL, 0.5f, 1.0f);
                }
            }
            Log.debug(TAG, "async TransformOp placed: {}→{} at {}",
                    p.op.from().id(), p.op.to().id(), p.op.target());
        });

        Log.debug(TAG, "async TransformOp: {}→{} at {} ({} tick delay)",
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
                pending.set(i, new Pending(p.future(), p.op(), p.world(), p.npcId(), remaining));
            }
        }

        for (CompletableFuture<Void> f : toComplete) {
            f.complete(null); // → triggers thenRun → places block
        }

        if (!toComplete.isEmpty()) {
            Log.debug(TAG, "async tickAll: {} completed, {} remaining",
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
