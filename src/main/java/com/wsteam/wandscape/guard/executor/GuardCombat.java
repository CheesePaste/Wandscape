package com.wsteam.wandscape.guard.executor;

import com.wsteam.wandscape.core.component.NavigationState;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.engine.service.ParticleService;
import com.wsteam.wandscape.engine.service.SoundService;
import com.wsteam.wandscape.engine.sound.WandscapeSounds;
import com.wsteam.wandscape.magic.entity.MagicBeamEntity;
import com.wsteam.wandscape.magic.internal.MagicCaster;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * 守卫/自防御共用的战斗引擎（静态工具）：光束重定向、视线、隔墙寻路、施法节流。
 *
 * <p>由 {@link GuardAttackExecutor}（建筑守卫）与 {@link SelfDefenseExecutor}（NPC 自防御）
 * 共用，避免两处各写一套。两者只负责「选出目标」，交给自己目标选择逻辑：
 * <pre>
 *   runCycle:
 *     target = <守卫: 建筑区内最近 / 自防御: 仇恨优先→半径内最近>
 *     if (target == null) { 完成/待命 }
 *     GuardCombat.engage(level, npc, target, world, npcId, circleId, color, lastCastTick);
 *     return RECHECK_TICKS;
 * </pre>
 */
public final class GuardCombat {
    private GuardCombat() {}

    /** 两次施法最小间隔基础（tick），除以 SPELL_SPEED 得实际 CD。施法时间（魔法阵+光束）不参与。 */
    public static final int CAST_MIN_INTERVAL = 40;

    private static final String TAG = "GuardCombat";

    // ── 单轮战斗动作：光束重定向 / LOS / 寻路 / 施法 ──

    /**
     * 对选定目标执行一轮战斗动作（守卫与自防御共用）：
     * <ol>
     *   <li>有活跃光束 → 重定向到目标（主动切换最近目标）。</li>
     *   <li>LOS 被方块挡 → 旧光束快速淡出、向目标寻路（绕过墙体），停手。</li>
     *   <li>LOS 可见 → 停止移动；无光束且 NPC 冷却已过 → {@link MagicCaster#castNpcAt} 施法。</li>
     * </ol>
     *
     * <p>施法冷却由 NPC 自身管理（{@code npc.canCastSpell()/startSpellCooldown}），
     * 实际 CD = 基础 / SPELL_SPEED。
     */
    public static void engage(ServerLevel level, WandscapeNpc npc, LivingEntity target,
                              World world, long npcId, String circleId, int color) {
        MagicBeamEntity beam = findActiveBeam(level, npc);
        if (beam != null) {
            beam.retarget(target); // 主动切换：光束持续指向最近的怪物
        }

        if (!hasLineOfSight(npc, target)) {
            // 看不见（隔墙）：旧光束会在墙上拖拽，先让它快速消失；然后寻路到能打到的位置
            if (beam != null) beam.setLifetime(5);
            navigateToward(world, npcId, target);
            return;
        }

        // 看得见：停止移动，确保有光束（没有才施法，靠 ACTIVE_CASTERS + 冷却防堆叠）
        cancelNavigation(world, npcId);
        if (beam == null && npc.canCastSpell()) {
            boolean ok = MagicCaster.castNpcAt(level, npc, target, circleId, color);
            if (ok) {
                npc.startSpellCooldown(CAST_MIN_INTERVAL);
                // 杖尖彩色爆闪（施法颜色）
                float[] rgb = rgbOf(color);
                ParticleService.burstColored(level, npc.getStaffPosition(), rgb[0], rgb[1], rgb[2], 6, 0.10f, 15, false);
                SoundService.playAt(level, npc.getX(), npc.getY(), npc.getZ(),
                        WandscapeSounds.GUARD_FIRE, SoundSource.NEUTRAL, 0.6f, 1.0f);
            } else {
            }
        }
    }

    /** 0xAARRGGBB → [r,g,b]（0-1）。 */
    private static float[] rgbOf(int argb) {
        return new float[]{ ((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f, (argb & 0xFF) / 255f };
    }

    // ── 光束 ──

    /** 找该 NPC 当前活跃的信标光束（光束源点位于持杖手附近，故在 NPC 周围小范围查询）。 */
    public static MagicBeamEntity findActiveBeam(ServerLevel level, WandscapeNpc npc) {
        AABB box = npc.getBoundingBox().inflate(30);
        for (MagicBeamEntity beam : level.getEntitiesOfClass(MagicBeamEntity.class, box)) {
            if (beam.isRemoved()) continue;
            if (beam.getCasterUuid().map(u -> u.equals(npc.getUUID())).orElse(false)) {
                return beam;
            }
        }
        return null;
    }

    // ── 视线 ──

    /** 持杖手 → 目标身体中心 射线无方块阻挡（起点沿方向前移 0.5 避免自贴墙误判）。 */
    public static boolean hasLineOfSight(WandscapeNpc npc, LivingEntity target) {
        if (!(npc.level() instanceof ServerLevel level)) return false;
        Vec3 from = npc.getStaffPosition();
        Vec3 to = target.getBoundingBox().getCenter();
        Vec3 delta = to.subtract(from);
        double dist = delta.length();
        if (dist < 0.1) return true;
        Vec3 start = from.add(delta.normalize().scale(0.5));
        HitResult hit = level.clip(new ClipContext(start, to,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
        return hit.getType() != HitResult.Type.BLOCK;
    }

    // ── 寻路 ──

    /** LOS 被挡时，向目标位置寻路（寻路会绕过墙体，LOS 一清就停手施法）。 */
    public static void navigateToward(World world, long npcId, LivingEntity target) {
        if (world == null || world.movementOps == null) return;
        NavigationState nav = world.get(npcId, NavigationState.class);
        if (nav != null && nav.mode != NavigationState.Mode.IDLE) return; // 已在寻路中

        Vec3 mobPos = target.position();
        world.movementOps.navigateTo(npcId,
                Mth.floor(mobPos.x), Mth.floor(mobPos.y), Mth.floor(mobPos.z));
    }

    /** 停止寻路（LOS 已通过 / 要施法时调用），让 NPC 站定。 */
    public static void cancelNavigation(World world, long npcId) {
        if (world == null || world.movementOps == null) return;
        NavigationState nav = world.get(npcId, NavigationState.class);
        if (nav != null && nav.mode != NavigationState.Mode.IDLE) {
            world.movementOps.cancelNavigation(npcId);
        }
    }
}
