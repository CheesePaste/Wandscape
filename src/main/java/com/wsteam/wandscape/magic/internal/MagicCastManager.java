package com.wsteam.wandscape.magic.internal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.wsteam.wandscape.magic.entity.MagicBeamEntity;
import com.wsteam.wandscape.shared.log.Log;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * 服务端施法调度：法阵动画时长结束后生成信标光束实体。
 * 每个施法者同时只允许一个未发射的施法（去重，防止法阵重叠）。
 */
public final class MagicCastManager {

    private static final String TAG = "MagicCastManager";

    private static final List<PendingCast> PENDING = new ArrayList<>();
    private static final Set<UUID> ACTIVE_CASTERS = new HashSet<>();

    private record PendingCast(UUID caster, ServerLevel level, Vec3 source,
                               BlockPos target, int color, long fireTick) {}

    private MagicCastManager() {}

    /**
     * 登记一次施法：在 {@code delayTicks}（=法阵 duration）后生成光束。
     * 若该施法者已有未发射的施法则拒绝。
     *
     * @return 是否登记成功
     */
    public static boolean schedule(ServerLevel level, ServerPlayer caster,
                                   Vec3 source, BlockPos target, int color, int delayTicks) {
        UUID uid = caster.getUUID();
        if (ACTIVE_CASTERS.contains(uid)) return false;
        PENDING.add(new PendingCast(uid, level, source, target, color,
                level.getGameTime() + Math.max(1, delayTicks)));
        ACTIVE_CASTERS.add(uid);
        return true;
    }

    /** ServerTick：到期生成光束实体并清除登记。 */
    public static void tick() {
        if (PENDING.isEmpty()) return;
        Iterator<PendingCast> it = PENDING.iterator();
        while (it.hasNext()) {
            PendingCast pc = it.next();
            if (pc.level().getGameTime() >= pc.fireTick()) {
                MagicBeamEntity beam = new MagicBeamEntity(pc.level(), pc.source(), pc.target(), pc.color());
                pc.level().addFreshEntity(beam);
                Log.debug(TAG, "beam spawned {} -> {} color=#{}",
                        pc.source(), pc.target(), Integer.toHexString(pc.color()));
                it.remove();
                ACTIVE_CASTERS.remove(pc.caster());
            }
        }
    }
}
