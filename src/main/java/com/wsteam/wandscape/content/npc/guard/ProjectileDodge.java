package com.wsteam.wandscape.content.npc.guard;
import com.wsteam.wandscape.content.task.component.Position;

import com.wsteam.wandscape.content.task.ecs.World;
import com.wsteam.wandscape.content.npc.guard.executor.GuardCombat;
import com.wsteam.wandscape.content.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.content.npc.internal.EntityComponentBridge;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.log.LogCategory;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 投掷物躲避：侦测朝 NPC 飞来的**敌对投掷物**（箭/凋零骷髅头/火球等，发射者是 {@link Enemy}），
 * 轨迹预判命中则用走位式导航（{@link GuardCombat#navigateDodge}）**走开**让出弹道——复用
 * 战斗走位的形式（ECS 导航 + 可站立/LOS 落点），不跳不走，靠移速躲开。
 *
 * <p>由 {@code Wandscape.onServerTick} 每 tick 调用（内部按 {@link #DETECT_INTERVAL_TICKS} 节流）。
 * 与自防御（躲近战怪）互补：本模块管「远程投掷物」，SelfDefenseExecutor 管「近身敌人」。
 *
 * <p>纯数学的轨迹预判抽在 {@link #willHit}（无 MC 依赖），可单测。
 */
public final class ProjectileDodge {
    private static final String TAG = "ProjectileDodge";

    /** 侦测节流（tick）：每 N tick 扫一轮所有 NPC 周围。 */
    private static final int DETECT_INTERVAL_TICKS = 3;
    /** 投掷物侦测半径（方块）：此半径内的敌对投掷物才考虑躲避。 */
    private static final double DETECT_RADIUS = 20.0;
    /** 预判窗口（tick）：预计此时间内会命中则躲避。 */
    private static final double PREDICTION_WINDOW = 16.0;
    /** 命中判定半径（方块）：投射物与 NPC 质心最近距离小于此值视为会命中。 */
    private static final double HIT_RADIUS = 1.0;
    /** 太近不躲（tick）：预计 <2 tick 命中，躲也来不及，避免反复触发空闪。 */
    private static final double MIN_TICKS_TO_IMPACT = 2.0;
    /** 单 NPC 躲避冷却（tick）：期间不重复触发，防止连续侦测把 NPC 来回拽。 */
    private static final int DODGE_COOLDOWN = 12;

    /** 各 NPC 上次发起躲避的 game tick（防止持续弹幕下每 3 tick 反复改目的地）。 */
    private static final Map<Long, Long> lastDodgeTick = new ConcurrentHashMap<>();

    private static int tickCounter;

    private ProjectileDodge() {}

    /** 每 server tick 调用：节流侦测所有小镇 NPC 附近敌对投掷物，命中风险则走位躲开。 */
    public static void tick(World world) {
        if (++tickCounter % DETECT_INTERVAL_TICKS != 0) return;

        Map<Long, WandscapeNpc> npcs = EntityComponentBridge.INSTANCE.allNpcs();
        // 清理已移除 NPC 的冷却记录
        lastDodgeTick.keySet().retainAll(npcs.keySet());

        for (Map.Entry<Long, WandscapeNpc> entry : npcs.entrySet()) {
            long npcId = entry.getKey();
            WandscapeNpc npc = entry.getValue();
            if (npc == null || npc.isRemoved() || npc.level().isClientSide) continue;
            if (!(npc.level() instanceof ServerLevel level)) continue;
            if (!npc.isColonyNpc()) continue;
            // 传送引导中：定身等法阵展开，走不了（减伤 75% 由 SelfDefenseHandler 处理）
            if (npc.isTeleportChanneling(level.getGameTime())) continue;

            Long last = lastDodgeTick.get(npcId);
            if (last != null && level.getGameTime() - last < DODGE_COOLDOWN) continue;

            Projectile threat = findThreat(npc, level);
            if (threat == null) continue;

            GuardCombat.navigateDodge(level, npc, world, npcId, threat);
            lastDodgeTick.put(npcId, level.getGameTime());
            Log.debug(LogCategory.NPC, "guard", "NPC {} — dodge projectile {} ({} blocks away)",
                    npcId, threat.getType().getDescriptionId(), (int) Math.sqrt(npc.distanceToSqr(threat)));
        }
    }

    /** 半径内「敌对（Enemy 发射）+ 轨迹预判命中 NPC」的最近投掷物；无则 null。 */
    private static Projectile findThreat(WandscapeNpc npc, ServerLevel level) {
        Projectile nearest = null;
        double bestSq = DETECT_RADIUS * DETECT_RADIUS;
        Vec3 center = npc.getBoundingBox().getCenter();
        for (Entity ent : level.getEntities((Entity) null, npc.getBoundingBox().inflate(DETECT_RADIUS),
                e -> e instanceof Projectile)) {
            if (!(ent instanceof Projectile proj) || proj.isRemoved()) continue;
            Entity owner = proj.getOwner();
            if (!(owner instanceof Enemy) || owner == npc) continue; // 只躲敌对生物发射的
            if (!onCollisionCourse(center, proj)) continue;
            double d = proj.distanceToSqr(npc);
            if (d <= bestSq) {
                bestSq = d;
                nearest = proj;
            }
        }
        return nearest;
    }

    /** 投掷物当前位置 + 当前速度（每 tick 位移）能否在窗口内逼近 NPC 质心到命中半径内。 */
    private static boolean onCollisionCourse(Vec3 npcCenter, Projectile proj) {
        Vec3 p = proj.position();
        Vec3 v = proj.getDeltaMovement();
        return willHit(p.x, p.y, p.z, v.x, v.y, v.z,
                npcCenter.x, npcCenter.y, npcCenter.z, HIT_RADIUS, PREDICTION_WINDOW);
    }

    /**
     * 纯数学：投射物沿直线飞行时，是否在 {@code windowTicks} 内与目标点最近距离小于
     * {@code hitRadius}。最近点时间 t = -dot(r, v)/|v|²（r = 起点-目标），t 落在
     * [MIN_TICKS_TO_IMPACT, window] 且垂直距离足够近才算命中。无 MC 依赖，可单测。
     */
    static boolean willHit(double px, double py, double pz,
                           double vx, double vy, double vz,
                           double nx, double ny, double nz,
                           double hitRadius, double windowTicks) {
        double rx = px - nx, ry = py - ny, rz = pz - nz;
        double vv = vx * vx + vy * vy + vz * vz;
        if (vv < 0.02) return false; // 几乎不动（未发射/将落地）
        double t = -(rx * vx + ry * vy + rz * vz) / vv;
        if (t < MIN_TICKS_TO_IMPACT || t > windowTicks) return false;
        double cx = rx + vx * t, cy = ry + vy * t, cz = rz + vz * t;
        return Math.sqrt(cx * cx + cy * cy + cz * cz) < hitRadius;
    }
}
