package com.wsteam.wandscape.building.executor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.wsteam.wandscape.building.internal.AltarCastHandler;
import com.wsteam.wandscape.building.internal.AltarCastState;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.magic.data.MagicCircleSpec;
import com.wsteam.wandscape.magic.data.MagicDef;
import com.wsteam.wandscape.magic.internal.MagicCircleLoader;
import com.wsteam.wandscape.magic.internal.SpellbookLoader;
import com.wsteam.wandscape.npc.data.DeathRecord;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.npc.internal.ColonyDeathRegistry;
import com.wsteam.wandscape.npc.internal.EntityComponentBridge;
import com.wsteam.wandscape.npc.internal.ReviveHandler;
import com.wsteam.wandscape.op.api.AtomicOp;
import com.wsteam.wandscape.op.executor.OpExecutor;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.network.MagicCircleCastPacket;
import com.wsteam.wandscape.shared.registry.WandscapeApis;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 祭坛施法执行器：接取祭坛施法任务的 NPC 走到祭坛旁后执行 {@link AtomicOp.AltarCastOp}。
 *
 * <p>流程：幂等复核（祭坛 CD 已过 + NPC 魔力够 + 施法锁空）→ {@code tryAltarCast} 扣蓝占锁
 * （**不设置 NPC 每魔法 CD**，祭坛 CD 独立）→ 祭坛中心起法阵 → 引导 {@code altarDuration} tick →
 * 到期 {@link #fireEffect} 释放效果（当前仅 revive）并**起祭坛 CD**——发布即锁定
 * （AltarCastHandler 查任务池），施放结束才进入 CD，二者接续无缝隙。
 *
 * <p>引导为内存态（Pending 列表，仿 GuardAttackExecutor），由 {@link #tickAll()} 驱动
 * （接线在 Wandscape.onServerTick）。NPC 接取任务时的魔力门槛由 SchedulerSystem 保证，
 * 此处 {@code tryAltarCast} 是最终原子复核。
 */
public final class AltarCastExecutor implements OpExecutor<AtomicOp.AltarCastOp> {

    private static final String TAG = "AltarCast";

    private record Pending(CompletableFuture<Void> future, ServerLevel level, UUID altarId,
                           String magicId, int remainingTicks) {}

    private final List<Pending> pending = new ArrayList<>();

    @Override
    public Class<AtomicOp.AltarCastOp> opType() {
        return AtomicOp.AltarCastOp.class;
    }

    @Override
    public CompletableFuture<Void> execute(AtomicOp.AltarCastOp op, World world, long npcId) {
        WandscapeNpc npc = EntityComponentBridge.INSTANCE.getNpc(npcId);
        if (npc == null || npc.isRemoved() || !(npc.level() instanceof ServerLevel level)) {
            return CompletableFuture.completedFuture(null);
        }
        MagicDef def = SpellbookLoader.getSpec(op.magicId());
        if (def == null || !def.altarOnly()) {
            Log.warn(TAG, "NPC {} — 魔法 '{}' 不存在或非祭坛魔法，任务跳过", npcId, op.magicId());
            return CompletableFuture.completedFuture(null);
        }
        String altarStr = op.params().get("altar");
        if (altarStr == null) {
            return CompletableFuture.completedFuture(null);
        }
        UUID altarId;
        try {
            altarId = UUID.fromString(altarStr);
        } catch (IllegalArgumentException e) {
            Log.warn(TAG, "NPC {} — 非法祭坛 id '{}'，任务跳过", npcId, altarStr);
            return CompletableFuture.completedFuture(null);
        }

        var buildingApi = WandscapeApis.getBuildingApiSilently();
        BoundingBox bounds = buildingApi != null ? buildingApi.getBuildingBounds(altarId) : null;
        if (bounds == null) {
            Log.warn(TAG, "NPC {} — 祭坛 {} 不存在/未完工，施法取消",
                    npcId, altarStr.substring(0, Math.min(8, altarStr.length())));
            return CompletableFuture.completedFuture(null);
        }

        AltarCastState state = AltarCastState.get(level);
        if (state.getCooldown(altarId, op.magicId()) > 0) {
            Log.info(TAG, "NPC {} — 祭坛 {} 冷却中，施法跳过（任务幂等结束）", npcId, altarId.toString().substring(0, 8));
            return CompletableFuture.completedFuture(null);
        }

        int duration = Math.max(1, def.altarDuration());
        if (!npc.tryAltarCast(def.manaCost(), duration)) {
            // 调度器已按魔力门槛分派，这里通常是引导期间又被战斗施法占用锁或魔力临时下降
            Log.info(TAG, "NPC {} — 祭坛施法被拒（魔力不足/施法锁占用），施法跳过", npcId);
            return CompletableFuture.completedFuture(null);
        }

        BlockPos center = AltarCastHandler.centerTop(bounds);
        sendCircle(level, center, def);
        npc.faceTarget(center);
        npc.startManualCast(duration);

        CompletableFuture<Void> future = world.startAsyncOp("altar_cast_" + npcId);
        pending.add(new Pending(future, level, altarId, op.magicId(), duration));
        Log.info(TAG, "NPC {} — 祭坛施法 {}（扣蓝 {}）引导 {} tick @ {}",
                npcId, op.magicId(), def.manaCost(), duration, center.toShortString());
        return future;
    }

    /** 每个 MC tick 调用：引导倒数，到期释放魔法效果。 */
    public void tickAll() {
        if (pending.isEmpty()) return;
        List<Pending> next = new ArrayList<>(pending.size());
        List<CompletableFuture<Void>> done = new ArrayList<>();
        for (Pending p : pending) {
            int rem = p.remainingTicks() - 1;
            if (rem <= 0) {
                fireEffect(p.level(), p.altarId(), p.magicId());
                done.add(p.future());
            } else {
                next.add(new Pending(p.future(), p.level(), p.altarId(), p.magicId(), rem));
            }
        }
        pending.clear();
        pending.addAll(next);
        for (CompletableFuture<Void> f : done) {
            f.complete(null);
        }
    }

    public boolean hasPendingOps() {
        return !pending.isEmpty();
    }

    /** 引导完成释放魔法效果（当前仅 revive）；祭坛 CD 自施放结束起算（发布即锁定，见 AltarCastHandler）。 */
    private void fireEffect(ServerLevel level, UUID altarId, String magicId) {
        MagicDef def = SpellbookLoader.getSpec(magicId);
        if (def != null && def.altarCooldown() > 0) {
            AltarCastState.get(level).setCooldown(altarId, magicId, def.altarCooldown());
        }
        switch (magicId) {
            case ReviveHandler.REVIVE_MAGIC_ID -> fireRevive(level, altarId);
            default -> Log.warn(TAG, "未知祭坛魔法 '{}' — 无效果", magicId);
        }
    }

    /** 复活：最近死去的死亡记录在祭坛中心最上方重生。 */
    private void fireRevive(ServerLevel level, UUID altarId) {
        var buildingApi = WandscapeApis.getBuildingApiSilently();
        BoundingBox bounds = buildingApi != null ? buildingApi.getBuildingBounds(altarId) : null;
        if (bounds == null) {
            Log.warn(TAG, "复活：祭坛 {} 已不存在", altarId.toString().substring(0, 8));
            return;
        }
        DeathRecord rec = ColonyDeathRegistry.get(level).latest();
        if (rec == null) {
            Log.warn(TAG, "复活：无死亡记录可复活（祭坛 {}）", altarId.toString().substring(0, 8));
            return;
        }
        BlockPos center = AltarCastHandler.centerTop(bounds);
        ReviveHandler.spawnFromRecordAt(level, rec, center);
    }

    /** 祭坛中心生成地面法阵（spec 驱动；时长应与魔法 altar_duration 对齐）。 */
    private void sendCircle(ServerLevel level, BlockPos center, MagicDef def) {
        String circleId = def.effectCircleId() != null ? def.effectCircleId() : "arcane_hexagram";
        MagicCircleSpec spec = MagicCircleLoader.getSpec(circleId);
        if (spec == null) {
            Log.warn(TAG, "祭坛法阵 '{}' 缺失 — 无法阵", circleId);
            return;
        }
        Vec3 axis = new Vec3(0, 1, 0);
        Vec3 origin = new Vec3(center.getX() + 0.5, center.getY() + spec.height, center.getZ() + 0.5);
        PacketDistributor.sendToPlayersTrackingChunk(level, new ChunkPos(center),
                new MagicCircleCastPacket(UUID.randomUUID(), origin, axis, circleId));
    }
}
