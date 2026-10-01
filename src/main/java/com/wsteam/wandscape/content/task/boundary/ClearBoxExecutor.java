package com.wsteam.wandscape.content.task.boundary;

import com.wsteam.wandscape.content.npc.internal.EntityComponentBridge;
import com.wsteam.wandscape.content.npc.worker.ColonyWorker;
import com.wsteam.wandscape.content.task.ecs.World;
import com.wsteam.wandscape.content.task.op.api.AtomicOp;
import com.wsteam.wandscape.content.task.op.executor.OpExecutor;
import com.wsteam.wandscape.content.task.types.BlockType;
import com.wsteam.wandscape.content.task.types.GridPos;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.registry.WandscapeSounds;
import com.wsteam.wandscape.foundation.sound.SoundService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * {@link AtomicOp.ClearBoxOp} 执行器 —— 整箱清空（一栋楼提交时先把场地清成空气）。
 *
 * <p>与 {@link AsyncTransformExecutor} 同构：返回一个未完成的 future，引擎等到它完成才推进
 * 步骤；区别是这个 op 本身就是**多 tick**的，所以由 {@link #tickAll()} 每 tick 推进游标。
 *
 * <p><b>为什么要做成分批游标，而不是「每格一个 op」或「一个 op 一次扫完」</b>：
 * 一栋超大建筑的 boundary 是 710 万格。每格一个 op 会在三处炸——提交时造 710 万条 JSON
 * （实测 6.0 s）、提交后约 1 秒的蓝图编译（实测 2.5 s）、第一次动工在单帧里跳过 666 万个
 * 空气 op（每次跳过都要查方块注册表并造一个 {@code BlockType}）。一次扫完则会在单帧里
 * 打满 710 万次 {@code getBlockState}（约 0.7 s 的帧尖峰）。分批游标把这三笔一起消掉：
 * 盒内格子只活在执行期，每 tick 恒定预算。
 */
public final class ClearBoxExecutor implements OpExecutor<AtomicOp.ClearBoxOp> {

    private static final String TAG = "ClearBoxExecutor";

    /**
     * 每 tick 处理格数。标定：spark 实测 58 万次 {@code getBlockState} ≈ 60 ms（约 0.1 µs/次），
     * 而这里每格只做 {@code BlockOps#isAir}（比 {@code getBlock} 便宜——不查方块注册表、
     * 不造 {@code BlockType}），16384 格约 1.7 ms/tick。
     * magic_academy 的 710 万格盒子约 433 tick ≈ 22 秒扫完，且没有单帧尖峰。
     */
    private static final int VOXELS_PER_TICK = 16384;

    /** NPC 施法音节流间隔（tick）：与方块放置音同频，避免每格都响。 */
    private static final int NPC_CAST_THROTTLE_TICKS = 10;

    private final List<Pending> pending = new ArrayList<>();

    @Override
    public Class<AtomicOp.ClearBoxOp> opType() {
        return AtomicOp.ClearBoxOp.class;
    }

    @Override
    public CompletableFuture<Void> execute(AtomicOp.ClearBoxOp op, World world, long npcId) {
        CompletableFuture<Void> future = world.startAsyncOp(
                "clear_box_" + op.min() + "_" + op.max());
        pending.add(new Pending(future, op, world, npcId));
        Log.info(TAG, "[ClearBox] start: box={}..{} volume={} excluded={}",
                op.min(), op.max(), op.volume(), op.excludedIndices().length);
        return future;
    }

    /** 每个 MC tick 推进所有在扫的清场游标（由 {@code TaskRuntime#tick} 调用）。 */
    public void tickAll() {
        if (pending.isEmpty()) return;
        // 倒序遍历：完成即移除。多个游标在同一 tick 里是**顺序**执行的，这一点是「不会重复
        // 回收掉落物」的前提：每格「读旧方块 → 回收 → 置空气」三步之间不会插进另一个游标。
        for (int i = pending.size() - 1; i >= 0; i--) {
            Pending p = pending.get(i);
            if (sweep(p)) {
                pending.remove(i);
                p.future.complete(null);
            }
        }
    }

    /** 推进一个游标；扫完整个盒子返回 true。 */
    private static boolean sweep(Pending p) {
        BlockOps blocks = p.world.blockOps;
        if (blocks == null) return true;

        AtomicOp.ClearBoxOp op = p.op;
        int dx = op.sizeX();
        int dz = op.sizeZ();
        long volume = op.volume();
        long[] excluded = op.excludedIndices();

        int budget = VOXELS_PER_TICK;
        while (p.visited < volume && budget-- > 0) {
            // 盒内排名：与排除集用的是同一个公式（AtomicOp.ClearBoxOp#index），
            // 而下面的进位序（z 最快、x 次之、y 最外）正是该排名的增长序 —— 逐格 +1。
            long rank = AtomicOp.ClearBoxOp.index(dx, dz, p.xRel, p.yRel, p.zRel);
            // 排除集升序，游标只前进：逐格比对 O(1) 摊还，不用查找表。
            while (p.exIdx < excluded.length && excluded[p.exIdx] < rank) p.exIdx++;

            GridPos pos = new GridPos(op.min().x() + p.xRel, op.min().y() + p.yRel, op.min().z() + p.zRel);
            if (!blocks.isAir(pos)) {
                if (p.exIdx < excluded.length && excluded[p.exIdx] == rank) {
                    // pattern 格归放置 op —— 清场碰它会把已建好的方块回收进仓库、再由放置 op
                    // 从 NPC 背包重放一遍，仓库满时那一份掉落物就是一次物品复制。
                    p.skippedPattern++;
                } else {
                    BlockSalvage.salvage(p.world, p.npcId, pos, BlockType.AIR);
                    blocks.setBlock(pos, BlockType.AIR);
                    p.cleared++;
                }
            }

            p.visited++;
            if (++p.zRel >= dz) {
                p.zRel = 0;
                if (++p.xRel >= dx) {
                    p.xRel = 0;
                    p.yRel++;
                }
            }
        }

        if (p.visited < volume) {
            // 施工表现：每 tick 在游标处挥一次手 + 节流施法音（方块自身的拆除音由 setBlock 播）。
            playSweepFeedback(p);
            return false;
        }
        Log.info(TAG, "[ClearBox] done: cleared={} pattern-skipped={} volume={}",
                p.cleared, p.skippedPattern, volume);
        return true;
    }

    private static void playSweepFeedback(Pending p) {
        ColonyWorker worker = EntityComponentBridge.INSTANCE.getWorker(p.npcId);
        if (worker == null) return;
        int x = p.op.min().x() + p.xRel;
        int y = p.op.min().y() + p.yRel;
        int z = p.op.min().z() + p.zRel;
        worker.doWorkAnimation(new BlockPos(x, y, z));
        if (worker.entity().level() instanceof ServerLevel sl) {
            SoundService.playAtThrottled(sl, x + 0.5, y + 0.5, z + 0.5,
                    WandscapeSounds.NPC_CAST, SoundSource.NEUTRAL, 0.5f, 1.0f, NPC_CAST_THROTTLE_TICKS);
        }
    }

    public boolean hasPendingOps() {
        return !pending.isEmpty();
    }

    /** 一个在扫的盒子：游标（盒内相对坐标 + 已扫格数）+ 排除集游标 + 统计。 */
    private static final class Pending {
        final CompletableFuture<Void> future;
        final AtomicOp.ClearBoxOp op;
        final World world;
        final long npcId;
        final long[] excludedIndices;   // 与 op 里同一份引用，避免每 tick 取一次
        int xRel;
        int yRel;
        int zRel;
        long visited;
        int exIdx;
        int cleared;
        int skippedPattern;

        Pending(CompletableFuture<Void> future, AtomicOp.ClearBoxOp op, World world, long npcId) {
            this.future = future;
            this.op = op;
            this.world = world;
            this.npcId = npcId;
            this.excludedIndices = op.excludedIndices();
        }
    }
}
