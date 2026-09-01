package com.wsteam.wandscape.content.magic.internal;

import com.wsteam.wandscape.engine.service.SoundService;
import com.wsteam.wandscape.engine.sound.WandscapeSounds;
import com.wsteam.wandscape.content.magic.entity.MagicBeamEntity;
import com.wsteam.wandscape.content.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.shared.log.Log;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.*;

/**
 * 服务端施法调度：法阵动画时长结束后生成信标光束实体。
 * 每个施法者（玩家/NPC 共用 UUID）同时只允许一个未发射的施法（去重，防止法阵重叠）。
 */
public final class MagicCastManager {

    private static final String TAG = "MagicCastManager";

    private static final List<PendingCast> PENDING = new ArrayList<>();
    private static final Set<UUID> ACTIVE_CASTERS = new HashSet<>();

    private record PendingCast(UUID caster, ServerLevel level, Vec3 source,
                               Vec3 target, int color, long fireTick, int lifeTicks,
                               @Nullable WandscapeNpc casterNpc, @Nullable LivingEntity targetNpc) {}

    private MagicCastManager() {}

    /**
     * 登记一次施法：在 {@code delayTicks} 后生成光束（光束总寿命 {@code lifeTicks}）。
     * {@code casterNpc}/{@code targetNpc} 为服务端跟踪用实体引用（null=静态光束）。
     * 若该施法者（玩家或 NPC 的 UUID）已有未发射的施法则拒绝。
     *
     * @return 是否登记成功
     */
    public static boolean schedule(ServerLevel level, UUID casterUuid,
                                   Vec3 source, Vec3 target, int color, int delayTicks, int lifeTicks,
                                   @Nullable WandscapeNpc casterNpc, @Nullable LivingEntity targetNpc) {
        if (ACTIVE_CASTERS.contains(casterUuid)) return false;
        PENDING.add(new PendingCast(casterUuid, level, source, target, color,
                level.getGameTime() + Math.max(1, delayTicks), Math.max(1, lifeTicks),
                casterNpc, targetNpc));
        ACTIVE_CASTERS.add(casterUuid);
        Log.info(TAG, "schedule caster={} source={} target={} fireTick={} life={} targetNpc={} pending={}",
                casterUuid.toString().substring(0, 8), source, target,
                level.getGameTime() + Math.max(1, delayTicks), lifeTicks,
                targetNpc != null ? targetNpc.getUUID().toString().substring(0, 8) : "null", PENDING.size());
        return true;
    }

    /** ServerTick：到期生成光束实体并清除登记。 */
    public static void tick() {
        if (PENDING.isEmpty()) return;
        Iterator<PendingCast> it = PENDING.iterator();
        while (it.hasNext()) {
            PendingCast pc = it.next();
            if (pc.level().getGameTime() >= pc.fireTick()) {
                MagicBeamEntity beam = new MagicBeamEntity(pc.level(), pc.source(), pc.target(),
                        pc.color(), pc.lifeTicks());
                beam.setCaster(pc.caster());
                beam.bindCaster(pc.casterNpc());
                beam.bindTarget(pc.targetNpc());
                pc.level().addFreshEntity(beam);
                SoundService.playAt(pc.level(), pc.source().x, pc.source().y, pc.source().z,
                        WandscapeSounds.MAGIC_BEAM, SoundSource.NEUTRAL, 0.6f, 1.0f);
                Log.info(TAG, "beam spawned id={} source={} target={} color=#{} life={} caster={} targetNpc={} time={}",
                        beam.getId(), pc.source(), pc.target(), Integer.toHexString(pc.color()),
                        pc.lifeTicks(), pc.caster().toString().substring(0, 8),
                        pc.targetNpc() != null ? pc.targetNpc().getUUID().toString().substring(0, 8) : "null",
                        pc.level().getGameTime());
                it.remove();
                ACTIVE_CASTERS.remove(pc.caster());
            }
        }
    }
}
