package com.wsteam.wandscape.guard.executor;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.wsteam.wandscape.core.component.NavigationState;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.engine.service.ParticleService;
import com.wsteam.wandscape.engine.service.SoundService;
import com.wsteam.wandscape.engine.sound.WandscapeSounds;
import com.wsteam.wandscape.magic.data.MagicDef;
import com.wsteam.wandscape.magic.data.WorldSnapshot;
import com.wsteam.wandscape.magic.entity.MagicBeamEntity;
import com.wsteam.wandscape.magic.internal.CastBrain;
import com.wsteam.wandscape.magic.internal.MagicCaster;
import com.wsteam.wandscape.magic.internal.MagicSpellExecutors;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.shared.log.Log;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.Villager;
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

    private static final String TAG = "GuardCombat";

    // ── 单轮战斗动作：光束重定向 / LOS / 寻路 / 施法 ──

    /**
     * 对选定目标执行一轮战斗动作（守卫与自防御共用）：
     * <ol>
     *   <li>有活跃光束 → 重定向到目标（主动切换最近目标）。</li>
     *   <li>LOS 被方块挡 → 旧光束快速淡出、向目标寻路（绕过墙体），停手。</li>
     *   <li>LOS 可见 → 停止移动；无光束 → {@link MagicCaster#castNpcAt} 施法。</li>
     * </ol>
     *
     * <p>施法决策经 {@link CastBrain}（L1 优先级扫描：CD/蓝/目标规则），选出后按魔法 id
     * 分发执行；门控（施法互斥锁 + 光束独立 CD + 固定魔力）在 {@code MagicCaster} 内部原子复验，
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

        // 看得见：停止移动，无光束则经 CastBrain 选魔法再施放（CD/蓝/锁在 MagicCaster 内部门控原子复验）
        cancelNavigation(world, npcId);
        if (beam == null) {
            // known = 玩家策略解析出的魔法级优先级；快照（敌数/自血/友方最低血/状态）驱动目标规则与 conditions
            List<MagicDef> known = CastBrain.resolvePriority(npc.castStrategy,
                    CastBrain.knownSpells(npc.spellbook.ids()));
            WorldSnapshot snapshot = buildSnapshot(level, npc);
            MagicDef chosen = CastBrain.select(known,
                    def -> npc.magic.canCast(def.id()) && npc.magic.getMana() >= def.manaCost(), snapshot);
            if (chosen == null) return;
            boolean ok = MagicSpellExecutors.dispatch(level, npc, target, chosen, circleId, color);
            if (ok) {
                // 杖尖彩色爆闪（施法颜色）
                float[] rgb = rgbOf(color);
                ParticleService.burstColored(level, npc.getStaffPosition(), rgb[0], rgb[1], rgb[2], 6, 0.10f, 15, false);
                SoundService.playAt(level, npc.getX(), npc.getY(), npc.getZ(),
                        WandscapeSounds.GUARD_FIRE, SoundSource.NEUTRAL, 0.6f, 1.0f);
            }
        }
    }

    /** 0xAARRGGBB → [r,g,b]（0-1）。 */
    private static float[] rgbOf(int argb) {
        return new float[]{ ((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f, (argb & 0xFF) / 255f };
    }

    // ── 决策快照：每轮战斗循环构造（敌数/自血/友方最低血/状态），喂给 CastBrain.select ──

    /** 快照扫描半径（方块）：AOE 敌数 / 友方最低血的"附近"范围。 */
    private static final int SNAPSHOT_SCAN_RADIUS = 16;

    private static WorldSnapshot buildSnapshot(ServerLevel level, WandscapeNpc npc) {
        return new WorldSnapshot(
                countEnemies(level, npc),
                npc.getHealth() / Math.max(1f, npc.getMaxHealth()),
                lowestAllyHpRatio(level, npc),
                activeEffectIds(npc));
    }

    /** 半径内可被该法师伤害的存活目标数量（min_enemies 用）。
     *  默认只数 Enemy；敌对法师（canBeamHurt 覆盖为也伤生存玩家）会把生存玩家计入敌数，
     *  使 hostile_nearest / min_enemies 对玩家目标成立。 */
    private static int countEnemies(ServerLevel level, WandscapeNpc npc) {
        int count = 0;
        for (Entity e : level.getEntities((Entity) null, npc.getBoundingBox().inflate(SNAPSHOT_SCAN_RADIUS),
                e -> e instanceof Enemy
                        || (e instanceof LivingEntity le && npc.canBeamHurt(le)))) {
            if (e.isAlive() && !e.isRemoved()) count++;
        }
        return count;
    }

    /** 半径内其他友方（NPC/村民）的最低血量比例；无友方 = 1（ally_hp_max 用）。 */
    private static float lowestAllyHpRatio(ServerLevel level, WandscapeNpc npc) {
        float lowest = 1f;
        for (Entity e : level.getEntities((Entity) null, npc.getBoundingBox().inflate(SNAPSHOT_SCAN_RADIUS),
                e -> e instanceof WandscapeNpc || e instanceof Villager)) {
            if (e == npc || !e.isAlive() || e.isRemoved()) continue;
            if (e instanceof LivingEntity le) {
                lowest = Math.min(lowest, le.getHealth() / Math.max(1f, le.getMaxHealth()));
            }
        }
        return lowest;
    }

    /** 自身已有状态 id（"minecraft:absorption" 等），no_effect 用。 */
    private static Set<String> activeEffectIds(WandscapeNpc npc) {
        Set<String> ids = new HashSet<>();
        for (MobEffectInstance effect : npc.getActiveEffects()) {
            effect.getEffect().unwrapKey()
                    .ifPresent(key -> ids.add(key.location().toString()));
        }
        return ids;
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
