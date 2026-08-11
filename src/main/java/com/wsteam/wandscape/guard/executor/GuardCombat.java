package com.wsteam.wandscape.guard.executor;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.wsteam.wandscape.Wandscape;
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
import com.wsteam.wandscape.magic.internal.SpellbookLoader;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.shared.log.Log;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
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
     *
     * <p>L1 无有效魔法（{@code CastBrain.select} 返回 null）时回落 L2 兜底普通攻击
     * （{@link #normalAttack}）：物理伤害 / 2s 攻速 / 不耗蓝。
     */
    public static void engage(ServerLevel level, WandscapeNpc npc, LivingEntity target,
                              World world, long npcId, String circleId, int color) {
        // L0 硬性覆盖（docs/spell-casting.md 三层决策）：自身或治疗范围内友方血量危机且会治疗 →
        // 无视玩家策略/conditions 强制施奶；只门控 CD/蓝/互斥锁，失败回落 L1。
        if (l0EmergencyHeal(level, npc, circleId, color)) return;

        // 和平模式：不施法、不追击、不重定向光束（自防御/守卫执行器在目标选择层已拦下，
        // 这里兜底）。L0 紧急自奶不受影响——治疗不是攻击。
        if (npc.isPeaceMode()) return;

        MagicBeamEntity beam = findActiveBeam(level, npc);
        if (beam != null) {
            beam.retarget(target); // 主动切换：光束持续指向最近的怪物
        }

        if (!hasLineOfSight(npc, target)) {
            // 看不见（隔墙）：旧光束会在墙上拖拽，先让它快速消失；然后寻路到能打到的安全落点
            if (beam != null) beam.setLifetime(5);
            navigateToward(level, npc, world, npcId, target);
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
            if (chosen == null) {
                // L2 兜底：无有效魔法（列表全不可施 / conditions 不满足）→ 普通攻击（物理，不耗蓝，2s 攻速）
                normalAttack(level, npc, target);
                return;
            }
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

    // ── L2 兜底：普通攻击（无有效魔法时，docs/spell-casting.md 三层决策） ──

    /** 普通攻击基础伤害（物理近战；经 NpcSpellPowerHandler 按 SPELL_POWER 倍率结算，默认 1.0 → 正好 5 点）。 */
    public static final float MELEE_DAMAGE = 5.0f;
    /** 普通攻击冷却（tick）：2 秒。 */
    public static final int MELEE_COOLDOWN_TICKS = 40;
    /** 白色粒子线步长（方块），与建筑交互射线的 CastBolt 粒子步长一致。 */
    private static final double MELEE_RAY_STEP = 0.4;
    /** 普通攻击伤害类型（data/wandscape/damage_type/melee.json）：物理近战，走正常护甲流程。 */
    private static final ResourceKey<DamageType> MELEE_DAMAGE_TYPE =
            ResourceKey.create(Registries.DAMAGE_TYPE,
                    ResourceLocation.fromNamespaceAndPath("wandscape", "melee"));

    /**
     * L2 兜底：无有效魔法（CastBrain.select 返回 null）时对目标普通攻击——
     * 物理伤害 5 点基础（×SPELL_POWER）/ 2s 攻速 / 不耗蓝 / 不占施法互斥锁。
     * 发射与建筑交互一致的白色 CastBolt 粒子线（持杖手 → 目标身体中心）。
     * 施法互斥锁占用期间（正引导某魔法）不普攻，避免打断施法视觉。
     */
    private static void normalAttack(ServerLevel level, WandscapeNpc npc, LivingEntity target) {
        if (npc.magic.getLockTicks() > 0) return; // 正施法（锁占用）不普攻
        if (!npc.canMeleeAttack(level.getGameTime())) return; // 攻速冷却未到
        if (target == null || target.isRemoved() || !target.isAlive()) return;
        if (!(target instanceof Enemy) && !npc.canBeamHurt(target)) return;

        npc.markMeleeAttack(level.getGameTime(), MELEE_COOLDOWN_TICKS);

        // 白色粒子线：持杖手 → 目标身体中心，与建筑交互射线的 CastBolt 粒子一致（先打视觉，
        // 即使目标被这一击击杀也不丢特效）
        Vec3 from = npc.getStaffPosition();
        Vec3 to = target.getBoundingBox().getCenter();
        Vec3 delta = to.subtract(from);
        double dist = delta.length();
        if (dist >= 0.1) {
            Vec3 dir = delta.normalize();
            for (double d = 0.8; d <= dist; d += MELEE_RAY_STEP) {
                Vec3 p = from.add(dir.scale(d));
                level.sendParticles(Wandscape.CAST_BOLT.get(), p.x, p.y, p.z, 1, 0, 0, 0, 0);
            }
        }

        // 单体伤害：source(key, npc, npc) → getEntity()=npc → 怪物 HurtByTargetGoal 反击（记仇自防御）、
        // NpcSpellPowerHandler 按 SPELL_POWER 结算；重置无敌帧保证这一击落地（同光束做法）。
        target.invulnerableTime = 0;
        target.hurt(level.damageSources().source(MELEE_DAMAGE_TYPE, npc, npc), MELEE_DAMAGE);

        level.playSound(null, npc.getX(), npc.getY(), npc.getZ(),
                SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.NEUTRAL, 0.5f, 1.0f);
    }

    // ── L0 硬性覆盖：血量危机施奶（docs/spell-casting.md 三层决策） ──

    /** L0 血量危机阈值：自身或治疗范围内友方血量比例低于此值时，无视玩家策略强制施放治疗
     *  （heal 以自身为圆心，可同时奶到范围内友方与自身）。 */
    private static final float L0_HEAL_THRESHOLD = 0.35f;

    /**
     * L0 硬性覆盖：自身或治疗半径（{@link MagicSpellExecutors#HEAL_RADIUS}）内友方血量低于阈值
     * 且会治疗 → 无视玩家策略/conditions 强制施放 heal。治疗以自身为圆心，范围内低血友方
     * 也在覆盖内，故施放必然够得着目标。独立于 {@link CastBrain#select}（不走 priority /
     * conditions），仅门控 CD/蓝/互斥锁；不满足则返回 false 回落 L1 正常决策。
     */
    private static boolean l0EmergencyHeal(ServerLevel level, WandscapeNpc npc, String circleId, int color) {
        if (lowestAllyHpRatio(level, npc, MagicSpellExecutors.HEAL_RADIUS, true) >= L0_HEAL_THRESHOLD) return false;
        if (!npc.spellbook.knows("heal")) return false;
        MagicDef heal = SpellbookLoader.getSpec("heal");
        if (heal == null) return false;
        if (!npc.magic.canCast("heal") || npc.magic.getMana() < heal.manaCost()) return false;
        return MagicSpellExecutors.dispatch(level, npc, npc, heal, circleId, color);
    }

    // ── 决策快照：每轮战斗循环构造（敌数/自血/友方最低血/状态），喂给 CastBrain.select ──

    /** 快照扫描半径（方块）：AOE 敌数 / 友方最低血的"附近"范围。 */
    private static final int SNAPSHOT_SCAN_RADIUS = 16;

    private static WorldSnapshot buildSnapshot(ServerLevel level, WandscapeNpc npc) {
        return new WorldSnapshot(
                countEnemies(level, npc),
                npc.getHealth() / Math.max(1f, npc.getMaxHealth()),
                lowestAllyHpRatio(level, npc, SNAPSHOT_SCAN_RADIUS, false),
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

    /** 半径内友方（NPC/村民）的最低血量比例，可含自身；无对象 = 1。
     *  {@code includeSelf=false} 供 L1 快照（ally_hp_max 只管队友）；L0 紧急奶传 true 且半径用
     *  治疗光环半径，使"范围内任意友方低血"即可触发。 */
    private static float lowestAllyHpRatio(ServerLevel level, WandscapeNpc npc, double radius, boolean includeSelf) {
        float lowest = 1f;
        for (Entity e : level.getEntities((Entity) null, npc.getBoundingBox().inflate(radius),
                e -> e instanceof WandscapeNpc || e instanceof Villager)) {
            if (!includeSelf && e == npc) continue;
            if (!e.isAlive() || e.isRemoved()) continue;
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
        return positionHasLineOfSight(level, npc.getStaffPosition(), target.getBoundingBox().getCenter());
    }

    /** 两点间射线无方块阻挡（起点沿方向前移 0.5 避免自贴墙误判）；{@code from} 一般为持杖手位置。 */
    private static boolean positionHasLineOfSight(ServerLevel level, Vec3 from, Vec3 to) {
        Vec3 delta = to.subtract(from);
        double dist = delta.length();
        if (dist < 0.1) return true;
        Vec3 start = from.add(delta.normalize().scale(0.5));
        HitResult hit = level.clip(new ClipContext(start, to,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
        return hit.getType() != HitResult.Type.BLOCK;
    }

    // ── 寻路 ──

    /** 与目标交战的期望水平间距（方块）：贴脸落点会被近战 / 苦力怕爆炸波及，故保留安全距离。 */
    private static final double ENGAGE_STANDOFF = 6.0;
    /** 以目标为圆心、standoff 半径环上的候选采样数。 */
    private static final int ENGAGE_SAMPLES = 16;

    /**
     * LOS 被挡时，向目标附近一个安全的交战落点寻路（绕墙走到能打到的位置）。
     *
     * <p>落点 = 以目标为圆心、{@link #ENGAGE_STANDOFF} 半径的环上，优先选「与目标有视线」且
     * 「尽量靠近守卫」的格子；环上无有视线落点则退化为最近的可站立格。这样无论走路还是传送兜底
     * （NavigationSystem 寻路失败会传送直达 nav 目标），守卫都**不会落在怪物脸上**——
     * 避免被苦力怕贴身爆炸秒杀。
     */
    private static void navigateToward(ServerLevel level, WandscapeNpc npc, World world,
                                       long npcId, LivingEntity target) {
        if (world == null || world.movementOps == null) return;
        if (target == null || !target.isAlive()) return;
        NavigationState nav = world.get(npcId, NavigationState.class);
        if (nav != null && nav.mode != NavigationState.Mode.IDLE) return; // 已在寻路中

        BlockPos dest = findEngagePos(level, npc, target);
        world.movementOps.navigateTo(npcId, dest.getX(), dest.getY(), dest.getZ());
    }

    /** 选择交战落点：目标为圆心、standoff 半径环上「有视线且尽量靠近守卫」的格子；返回站立脚底 Y。 */
    private static BlockPos findEngagePos(ServerLevel level, WandscapeNpc npc, LivingEntity target) {
        Vec3 mobCenter = target.getBoundingBox().getCenter();
        Vec3 guardPos = npc.getStaffPosition();
        double towardGuard = Math.atan2(guardPos.z - mobCenter.z, guardPos.x - mobCenter.x);

        BlockPos best = null;                 // 有视线的候选中最靠近守卫的
        double bestLosDistSq = Double.MAX_VALUE;
        BlockPos fallback = null;             // 无任何有视线候选时的最近可站立格
        double fallbackDistSq = Double.MAX_VALUE;

        for (int i = 0; i < ENGAGE_SAMPLES; i++) {
            double angle = towardGuard + Math.PI * 2 * i / ENGAGE_SAMPLES;
            int bx = Mth.floor(mobCenter.x + Math.cos(angle) * ENGAGE_STANDOFF);
            int bz = Mth.floor(mobCenter.z + Math.sin(angle) * ENGAGE_STANDOFF);
            int groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz);
            BlockPos cand = new BlockPos(bx, groundY + 1, bz);
            double dSq = cand.distToCenterSqr(guardPos.x, guardPos.y, guardPos.z);
            if (dSq < fallbackDistSq) {
                fallbackDistSq = dSq;
                fallback = cand;
            }
            if (positionHasLineOfSight(level, staffOf(cand), mobCenter) && dSq < bestLosDistSq) {
                bestLosDistSq = dSq;
                best = cand;
            }
        }
        if (best != null) return best;
        if (fallback != null) return fallback;

        // 极端兜底：沿守卫→目标连线、目标身前 standoff 处（绝不压到目标）
        Vec3 dir = mobCenter.subtract(guardPos);
        if (dir.lengthSqr() < 0.01) dir = new Vec3(1, 0, 0);
        Vec3 fb = mobCenter.subtract(dir.normalize().scale(ENGAGE_STANDOFF));
        int groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(fb.x), Mth.floor(fb.z));
        return new BlockPos(Mth.floor(fb.x), groundY + 1, Mth.floor(fb.z));
    }

    /** 站立落点（脚底）上持杖手的大致高度位置。 */
    private static Vec3 staffOf(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5, pos.getY() + 1.6, pos.getZ() + 0.5);
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
