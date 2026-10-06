package com.wsteam.wandscape.content.task.boundary;
import com.wsteam.wandscape.content.task.boundary.EntityOps;
import com.wsteam.wandscape.content.task.component.TaskExecutor;

import com.wsteam.wandscape.content.task.boundary.BlockOps;
import com.wsteam.wandscape.content.task.component.NpcInventory;
import com.wsteam.wandscape.content.task.ecs.World;
import com.wsteam.wandscape.content.task.types.BlockType;
import com.wsteam.wandscape.foundation.sound.SoundService;
import com.wsteam.wandscape.foundation.registry.WandscapeSounds;
import com.wsteam.wandscape.content.npc.worker.ColonyWorker;
import com.wsteam.wandscape.content.npc.internal.EntityComponentBridge;
import com.wsteam.wandscape.content.task.op.api.AtomicOp;
import com.wsteam.wandscape.content.task.op.executor.OpExecutor;
import com.wsteam.wandscape.content.task.op.executor.ResourceShortageException;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.api.WandscapeApis;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * TransformOp 执行器（放置 / 拆除 / 铺地的唯一实现）。
 *
 * <p><b>两种节奏</b>，由构造参数 {@code delayTicks} 决定：
 * <ul>
 *   <li>{@code delayTicks <= 0}（当前 {@code EngineBootstrap} 用的）：拍内直接完成——放置
 *       **速度**改由 {@code TaskExecutionSystem} 的每 tick 工作速度额度决定
 *       （一拍放 {@code floor(工作速度)} 格，至少 1，见 {@code instantOpBudget}）。</li>
 *   <li>{@code delayTicks > 0}：老的 V2.5 promise 模型——返回 {@link World#startAsyncOp} 的未完成
 *       future，引擎存进 {@code TaskExecutor.pendingFuture} 等它；到期后在 {@code thenRun} 里落块。
 *       注意这条路上「每格占 {@code delayTicks} 拍」，工作速度额度用不上，两者别同时开。</li>
 * </ul>
 *
 * <p>两条路都走同一份 {@link #placeNow}：清障回收 → 置块 + 方块实体数据 → 工作动作与施法音。
 *
 * <p>When the op carries a {@link AtomicOp.TransformOp#consumable()}, the item is
 * removed from NPC inventory first. On shortage, a
 * {@link ResourceShortageException} is thrown — the engine marks the task
 * AWAITING_RESOURCES and releases the NPC.
 *
 * <p>When an existing block in the world is broken, cleared, flattened, or replaced,
 * this executor intercepts the destruction, calculates dropped items, and deposits
 * the materials directly into the colony warehouse (no flying-item animation — batch
 * demolition would flood the client with transport entities).
 */
public class AsyncTransformExecutor implements OpExecutor<AtomicOp.TransformOp> {

    private static final String TAG = "AsyncTransformExecutor";

    /** NPC 施法音节流间隔（tick）：与 WandscapeBlockOps 方块放置/拆除音同频，避免每块方块都播 Evoker 施法声刷屏。 */
    private static final int NPC_CAST_THROTTLE_TICKS = 10;

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
            String pureId = op.consumable().resource().stripBlockStateSuffix().id();
            if (WandscapeApis.getElementApi().hasElementMapping(pureId)) {
                NpcInventory inv = world.get(npcId, NpcInventory.class);
                if (inv == null || !inv.hasEnough(op.consumable().resource(),
                        op.consumable().amount())) {
                    return CompletableFuture.failedFuture(
                            new ResourceShortageException(List.of(op.consumable())));
                }
                inv.remove(op.consumable().resource(), op.consumable().amount());
            }
        }

        // ── Placement ──
        // delayTicks <= 0：拍内直接放。放置**速度**不在这里管——「一拍放几格」由
        // TaskExecutionSystem 的每 tick 工作速度额度（instantOpBudget）决定，两处各司其职。
        if (delayTicks <= 0) {
            placeNow(op, world, npcId);
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
            placeNow(p.op(), p.world(), p.npcId());
        });

        return future;
    }

    /**
     * 当场放下一格：清障回收 → 置块 + 方块实体数据 → 工作动作与施法音。
     *
     * <p>拍内路径与延迟路径共用同一份，避免两条路各自漂移（原先拍内路径既不挥手也不出声，
     * 一旦把延迟调成 0，整栋楼就会「方块在长、法师不动」）。
     */
    private void placeNow(AtomicOp.TransformOp op, World world, long npcId) {
        // Intercept & return salvaged block items to colony warehouse with visual flight
        performSalvage(op, world, npcId);

        BlockOps blockOps = world.blockOps;
        if (blockOps != null) {
            blockOps.setBlock(op.target(), op.to());
            blockOps.setBlockEntityData(op.target(), op.blockNbtBase64());
        }

        // Visual feedback on the NPC that performed the work
        ColonyWorker worker = EntityComponentBridge.INSTANCE.getWorker(npcId);
        if (worker != null) {
            worker.doWorkAnimation(new BlockPos(
                    op.target().x(), op.target().y(), op.target().z()));
            // NPC 施法放置音（守卫/自防御不走这里，避免与 GuardCombat 开火音重叠）
            // 节流与方块放置/拆除音同频：整栋楼连续施工时不会每块都响
            if (worker.entity().level() instanceof ServerLevel sl) {
                SoundService.playAtThrottled(sl, op.target().x() + 0.5,
                        op.target().y() + 0.5, op.target().z() + 0.5,
                        WandscapeSounds.NPC_CAST, SoundSource.NEUTRAL, 0.5f, 1.0f,
                        NPC_CAST_THROTTLE_TICKS);
            }
        }
    }

    /** Effective per-block delay for this NPC: base delayTicks divided by WORK_SPEED. */
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
    }

    public boolean hasPendingOps() { return !pending.isEmpty(); }

    private Pending findPending(CompletableFuture<Void> future) {
        for (Pending p : pending) {
            if (p.future() == future) return p;
        }
        return null;
    }

    // ════════════════════════════════════════════════════════════
    //  Dismantling / Salvage Logistics Interception
    // ════════════════════════════════════════════════════════════

    /** 回收实现见 {@link BlockSalvage}（与整箱清空的批量清格执行器共用同一份）。 */
    private void performSalvage(AtomicOp.TransformOp op, World world, long npcId) {
        BlockSalvage.salvage(world, npcId, op.target(), op.to());
    }
}
