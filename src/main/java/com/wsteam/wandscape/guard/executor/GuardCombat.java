package com.wsteam.wandscape.guard.executor;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.wsteam.wandscape.Config;
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
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.block.state.BlockState;
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

    // ── 走位（风筝/群殴）参数：数值归属 docs/decisions.md——guard.kite*/engage/flee* 走 Config（TOML），
    //    群殴扫描等行为常量仍留这里 ──
    /** 群殴阈值：附近可见敌数 ≥ 此值 → 主动拉开避免被围殴。 */
    private static final int CROWD_THRESHOLD = 3;
    /** 群殴扫描半径（方块）：此范围内数可见敌数 + 取质心。 */
    private static final double CROWD_RADIUS = 10.0;
    /** 后撤落点采样数（角度分档）。 */
    private static final int RETREAT_SAMPLES = 16;
    /** 投掷物躲避走位距离（方块）：沿弹道垂直方向走开此距离，正好让出箭/骷髅头的弹道。 */
    private static final double DODGE_DIST = 2.5;

    // ── 战斗态：禁 wandering，让走位自由（战斗结束由执行器恢复） ──
    // tickCastingState 已不再因 isCasting 硬钉停移动（游荡 goal 自会尊重施法态），
    // markInCombat 禁 wandering 只是防止战斗期间 NPC 空闲乱走；走位全由 ECS 导航驱动。

    /** 进入战斗态：禁 wandering（suppressWandering=true）。 */
    public static void markInCombat(WandscapeNpc npc) {
        npc.setAiWanderingEnabled(false);
    }

    /** 战斗结束：恢复 idle wandering（suppressWandering=false）。 */
    public static void markCombatEnd(WandscapeNpc npc) {
        npc.setAiWanderingEnabled(true);
    }

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
        // 这里兜底；和平 NPC 的逃跑走位在 SelfDefenseExecutor 处理）。L0 紧急自奶不受影响。
        if (npc.isPeaceMode()) return;

        // 传送引导中：定身等法阵展开（不走位、不施法），减伤 75% 由 SelfDefenseHandler 处理
        if (npc.isTeleportChanneling(level.getGameTime())) {
            return;
        }

        // 战斗态：禁 wandering，走位由本引擎的导航驱动（战斗结束由执行器 markCombatEnd）
        markInCombat(npc);

        // ── 低血逃跑态（guard.flee*，保命优先）：血量比例低于阈值 → 走位距离改用逃跑档
        //    （fleeStartDist/fleeStandoff），LOS 被墙挡也继续后撤不走近交战；光束边走边打仍在输出。
        //    能奶的低血已被上方 L0 紧急奶拦下，这里是「奶不了」的兜底逃跑。──
        boolean fleeing = npc.getHealth() / Math.max(1f, npc.getMaxHealth())
                < Config.GUARD_FLEE_HP_THRESHOLD.get();
        double startDist = fleeing ? Config.GUARD_FLEE_START_DIST.get() : Config.GUARD_KITE_START_DIST.get();
        double standoff = fleeing ? Config.GUARD_FLEE_STANDOFF.get() : Config.GUARD_KITE_STANDOFF.get();

        MagicBeamEntity beam = findActiveBeam(level, npc);
        if (beam != null) {
            beam.retarget(target); // 主动切换：光束持续指向最近的怪物
        }

        // ── 群殴规避：附近可见敌数 ≥ CROWD_THRESHOLD → 往敌方质心反方向走位（边走边打），
        //    别被围在墙角群殴。走位由 ECS 导航驱动（施法不锁移动）。──
        Crowd crowd = scanCrowd(level, npc);
        if (crowd.count >= CROWD_THRESHOLD) {
            navigateAway(level, npc, world, npcId, crowd.centroid, standoff);
            if (beam == null) castSelected(level, npc, target, circleId, color);
            return;
        }

        if (!hasLineOfSight(npc, target)) {
            // 看不见（隔墙）：旧光束会在墙上拖拽，先让它快速消失
            if (beam != null) beam.setLifetime(5);
            if (fleeing) {
                // 低血逃跑：不回走近交战（墙后近战威胁大），继续向后撤，保命优先
                navigateAway(level, npc, world, npcId, target.getBoundingBox().getCenter(), standoff);
            } else {
                navigateToward(level, npc, world, npcId, target); // 寻路到能打到的安全落点
            }
            return;
        }

        // ── 战斗风筝：LOS 可见但目标进入威胁距离（startDist）→ 后撤拉开距离（边走边打）。
        //    落点只选可站立 + 有 LOS 的格子，贴墙被堵死时静默站定继续打，不会寻路进墙/卡死。──
        if (horizontalDistSq(npc, target) < startDist * startDist) {
            navigateAway(level, npc, world, npcId, target.getBoundingBox().getCenter(), standoff);
            if (beam == null) castSelected(level, npc, target, circleId, color);
            return;
        }

        // 看得见且安全距离：停止移动，面向目标（每轮战斗循环刷新朝向，目标走位时脸跟着转）。
        // 无光束则经 CastBrain 选魔法再施放（CD/蓝/锁在 MagicCaster 内部门控原子复验）
        cancelNpcNavigation(world, npcId, npc); // 战斗安全版：world=null（敌对法师）自动跳过
        npc.faceTarget(BlockPos.containing(target.getBoundingBox().getCenter()));
        if (beam == null) {
            castSelected(level, npc, target, circleId, color);
        }
    }

    /**
     * 经 CastBrain 选魔法再施放（CD/蓝/锁在 MagicCaster 内部门控原子复验）；选不出 → L2 普通攻击兜底。
     * 站定施法与风筝/群殴走位三处共用——走位期间也能开火（光束独立于施法者每 tick 跟随并径向伤害）。
     */
    private static void castSelected(ServerLevel level, WandscapeNpc npc, LivingEntity target,
                                     String circleId, int color) {
        // known = 玩家策略解析出的魔法级优先级；快照（敌数/自血/友方最低血/状态）驱动目标规则与 conditions
        List<MagicDef> known = CastBrain.resolvePriority(npc.castStrategy,
                CastBrain.knownSpells(npc.equippedMagic));
        WorldSnapshot snapshot = buildSnapshot(level, npc);
        MagicDef chosen = CastBrain.select(known,
                def -> com.wsteam.wandscape.core.component.MagicState.isFreeCast()
                        || (npc.magic.canCast(def.id()) && npc.magic.getMana() >= def.manaCost()), snapshot);
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

        // 面向目标：普攻前先转身（脸/身体/手臂朝向目标），粒子线与伤害方向随之对准
        Vec3 aim = target.getBoundingBox().getCenter();
        npc.faceTarget(BlockPos.containing(aim));

        // 白色粒子线：持杖手 → 目标身体中心，与建筑交互射线的 CastBolt 粒子一致（先打视觉，
        // 即使目标被这一击击杀也不丢特效）
        Vec3 from = npc.getStaffPosition();
        Vec3 to = aim;
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
    private static final float L0_HEAL_THRESHOLD = 0.5f;

    /**
     * L0 硬性覆盖：自身或治疗半径（{@link MagicSpellExecutors#HEAL_RADIUS}）内友方血量低于阈值
     * 且会治疗 → 无视玩家策略/conditions 强制施放 heal。治疗以自身为圆心，范围内低血友方
     * 也在覆盖内，故施放必然够得着目标。独立于 {@link CastBrain#select}（不走 priority /
     * conditions），仅门控 CD/蓝/互斥锁；不满足则返回 false 回落 L1 正常决策。
     */
    private static boolean l0EmergencyHeal(ServerLevel level, WandscapeNpc npc, String circleId, int color) {
        if (lowestAllyHpRatio(level, npc, MagicSpellExecutors.HEAL_RADIUS, true) >= L0_HEAL_THRESHOLD) return false;
        if (!npc.knowsSpecialSpell("heal")) return false;
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
     *  伤害按 Enemy 结算：默认只数 Enemy（含和平中立生物，与伤害口径一致）；敌对法师
     *  （canBeamHurt 覆盖为也伤生存玩家）会把生存玩家计入敌数，使 hostile_nearest /
     *  min_enemies 对玩家目标成立。 */
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

    /** 以目标为圆心、standoff 半径环上的候选采样数。 */
    private static final int ENGAGE_SAMPLES = 16;
    /** 交战落点与怪物脚底允许的楼层高度差：往上 2 格（覆盖坑里/台阶上的怪）、往下 4 格（覆盖低洼）。 */
    private static final int ENGAGE_FLOOR_UP = 2;
    private static final int ENGAGE_FLOOR_DOWN = -4;

    /**
     * LOS 被挡时，向目标附近一个安全的交战落点寻路（绕墙走到能打到的位置）。
     *
     * <p>落点 = 以目标为圆心、{@code guard.engageStandoff} 半径的环上，优先选「与目标有视线」且
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

    /**
     * 选择交战落点：目标为圆心、standoff 半径环上「有视线且尽量靠近守卫」的格子；返回站立脚底 Y。
     *
     * <p>候选高度不取世界最高表面（{@code MOTION_BLOCKING} 会把高层建筑房顶当地面，导致守卫
     * 传送到楼顶下不来），而是取怪物脚底所在楼层附近的可站立面——怪物在楼下则候选在地面，
     * 在楼顶则候选在楼顶，始终与怪物同层。列内怪物楼层附近无可站立格（如落在建筑墙体内）
     * 则跳过该列，避免把守卫送进墙里或楼顶。
     */
    private static BlockPos findEngagePos(ServerLevel level, WandscapeNpc npc, LivingEntity target) {
        Vec3 mobCenter = target.getBoundingBox().getCenter();
        Vec3 guardPos = npc.getStaffPosition();
        int mobFeetY = Mth.floor(target.getY());
        double towardGuard = Math.atan2(guardPos.z - mobCenter.z, guardPos.x - mobCenter.x);
        double standoff = Config.GUARD_ENGAGE_STANDOFF.get();

        BlockPos best = null;                 // 有视线的候选中最靠近守卫的
        double bestLosDistSq = Double.MAX_VALUE;
        BlockPos fallback = null;             // 无任何有视线候选时的最近可站立格
        double fallbackDistSq = Double.MAX_VALUE;

        for (int i = 0; i < ENGAGE_SAMPLES; i++) {
            double angle = towardGuard + Math.PI * 2 * i / ENGAGE_SAMPLES;
            int bx = Mth.floor(mobCenter.x + Math.cos(angle) * standoff);
            int bz = Mth.floor(mobCenter.z + Math.sin(angle) * standoff);
            int standY = findStandingYNear(level, bx, bz, mobFeetY);
            if (standY == Integer.MIN_VALUE) continue; // 该列怪物楼层附近无可站立格 → 跳过
            BlockPos cand = new BlockPos(bx, standY, bz);
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
        Vec3 fb = mobCenter.subtract(dir.normalize().scale(standoff));
        int bx = Mth.floor(fb.x);
        int bz = Mth.floor(fb.z);
        int standY = findStandingYNear(level, bx, bz, mobFeetY);
        if (standY == Integer.MIN_VALUE) {
            standY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz) + 1;
        }
        return new BlockPos(bx, standY, bz);
    }

    /** 寻找立足点 Y 轴偏移优先顺序：优先同层(0)，再向上/下 1 格，最后 2 格/地下。避免优先选到屋顶。 */
    private static final int[] Y_OFFSETS_BY_PROXIMITY = {0, 1, -1, 2, -2, -3, -4};

    /** 列 (x,z) 上离怪物脚底 {@code nearY} 最近的可站立脚底 Y；楼层差超出
     *  {@code ENGAGE_FLOOR_UP/DOWN} 或找不到返回 {@link Integer#MIN_VALUE}（调用方跳过该列）。 */
    private static int findStandingYNear(ServerLevel level, int x, int z, int nearY) {
        for (int dy : Y_OFFSETS_BY_PROXIMITY) {
            int y = nearY + dy;
            if (isStandable(level, x, y, z)) return y;
        }
        return Integer.MIN_VALUE;
    }

    /** 站立位置 {@code (x,y,z)}（y 为脚底）是否可行：脚/头两格无碰撞非液体、脚下有实心地面。 */
    private static boolean isStandable(ServerLevel level, int x, int y, int z) {
        BlockPos feet = new BlockPos(x, y, z);
        BlockPos head = new BlockPos(x, y + 1, z);
        BlockPos ground = new BlockPos(x, y - 1, z);
        if (!level.isLoaded(feet) || !level.isLoaded(head) || !level.isLoaded(ground)) return false;
        BlockState feetState = level.getBlockState(feet);
        BlockState headState = level.getBlockState(head);
        if (!feetState.getFluidState().isEmpty() || !headState.getFluidState().isEmpty()) return false;
        if (!feetState.getCollisionShape(level, feet).isEmpty()
                || !headState.getCollisionShape(level, head).isEmpty()) {
            return false;
        }
        BlockState groundState = level.getBlockState(ground);
        if (groundState.isAir()) return false;
        return !groundState.getCollisionShape(level, ground).isEmpty();
    }

    /** 站立落点（脚底）上持杖手的大致高度位置。 */
    private static Vec3 staffOf(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5, pos.getY() + 1.6, pos.getZ() + 0.5);
    }

    // ── 走位：战斗风筝 / 群殴规避 / 和平逃跑后撤 ──

    private record Crowd(int count, Vec3 centroid) {}

    /** 半径 {@link #CROWD_RADIUS} 内「LOS 可见且存活」的 Enemy 计数与位置质心（群殴判定用）。 */
    private static Crowd scanCrowd(ServerLevel level, WandscapeNpc npc) {
        Vec3 sum = Vec3.ZERO;
        int count = 0;
        for (Entity e : level.getEntities((Entity) null, npc.getBoundingBox().inflate(CROWD_RADIUS),
                e -> e instanceof Enemy)) {
            if (!(e instanceof LivingEntity mob) || mob.isRemoved() || !mob.isAlive()) continue;
            if (!hasLineOfSight(npc, mob)) continue;
            sum = sum.add(e.position());
            count++;
        }
        return new Crowd(count, count == 0 ? npc.position() : sum.scale(1.0 / count));
    }

    /** 与目标水平距离平方（贴脸判定用，不看高度差）。 */
    private static double horizontalDistSq(WandscapeNpc npc, LivingEntity target) {
        double dx = npc.getX() - target.getX();
        double dz = npc.getZ() - target.getZ();
        return dx * dx + dz * dz;
    }

    /**
     * 向威胁点（目标中心 / 敌方质心）的**反方向**后撤：由 ECS 导航驱动
     * （施法不锁移动，走位导航畅通），落点不可站立时静默放弃（站定继续打）。
     * 守卫/自防御/和平逃跑/低血逃跑共用。寻路失败时 NavigationSystem 会回退 self_teleport——
     * 正常走位不会失败（见 findRetreatPos 的可达性约束），传送留给狭小地带真正走投无路时逃生。
     */
    public static void navigateAway(ServerLevel level, WandscapeNpc npc, World world,
                                    long npcId, Vec3 threat, double standoff) {
        if (world == null || world.movementOps == null) return;
        BlockPos dest = findRetreatPos(level, npc, threat, standoff);
        if (dest == null) return;
        world.movementOps.navigateTo(npcId, dest.getX(), dest.getY(), dest.getZ());
    }

    /** 便捷重载：落点间距用默认 {@link Config#GUARD_KITE_STANDOFF}（和平模式逃跑等调用）。 */
    public static void navigateAway(ServerLevel level, WandscapeNpc npc, World world,
                                    long npcId, Vec3 threat) {
        navigateAway(level, npc, world, npcId, threat, Config.GUARD_KITE_STANDOFF.get());
    }

    /**
     * 投掷物躲避（走位式）：朝弹道的垂直方向**走开**让出弹道（不走不跳、不寻路进墙），
     * 由 ECS 导航驱动、落点不可站立/无墙时静默放弃（站定硬吃）。与 {@link #navigateAway}
     * 同一个走位形式——只换了躲避方向（垂直弹道而非反方向）。由 {@code ProjectileDodge} 调用。
     */
    public static void navigateDodge(ServerLevel level, WandscapeNpc npc, World world,
                                     long npcId, Projectile proj) {
        if (world == null || world.movementOps == null) return;
        BlockPos dest = findDodgePos(level, npc, proj);
        if (dest == null) return;
        world.movementOps.navigateTo(npcId, dest.getX(), dest.getY(), dest.getZ());
    }

    /**
     * 后撤落点：威胁点周围 {@code standoff} 环上、采样角集中在「远离威胁」方向 ±半圆
     * （向身后/侧后方退，不绕到怪物对面）；优先「NPC→落点 无墙 且 落点→威胁 有 LOS 且离 NPC 最近」
     * 的可站立格（NPC→落点 无墙 ≈ 走得过去，源头避免寻路失败→传送），
     * 退化「最近可站立格」，极端兜底沿 NPC→威胁 反方向退 2 格（不可站立返回 null）。
     */
    private static BlockPos findRetreatPos(ServerLevel level, WandscapeNpc npc, Vec3 threat, double standoff) {
        Vec3 npcPos = npc.getStaffPosition();
        Vec3 away = npcPos.subtract(threat);
        if (away.lengthSqr() < 0.01) away = new Vec3(1, 0, 0);
        double baseAngle = Math.atan2(away.z, away.x);
        int threatFeetY = Mth.floor(threat.y);

        BlockPos best = null;
        double bestLosDistSq = Double.MAX_VALUE;
        BlockPos fallback = null;
        double fallbackDistSq = Double.MAX_VALUE;

        for (int i = 0; i < RETREAT_SAMPLES; i++) {
            double angle = baseAngle + (i - RETREAT_SAMPLES / 2) * Math.PI / (RETREAT_SAMPLES - 1);
            int bx = Mth.floor(threat.x + Math.cos(angle) * standoff);
            int bz = Mth.floor(threat.z + Math.sin(angle) * standoff);
            int standY = findStandingYNear(level, bx, bz, threatFeetY);
            if (standY == Integer.MIN_VALUE) continue;
            BlockPos cand = new BlockPos(bx, standY, bz);
            double dSq = cand.distToCenterSqr(npcPos.x, npcPos.y, npcPos.z);
            if (dSq < fallbackDistSq) {
                fallbackDistSq = dSq;
                fallback = cand;
            }
            if (positionHasLineOfSight(level, npcPos, staffOf(cand))
                    && positionHasLineOfSight(level, staffOf(cand), threat)
                    && dSq < bestLosDistSq) {
                bestLosDistSq = dSq;
                best = cand;
            }
        }
        if (best != null) return best;
        if (fallback != null) return fallback;

        Vec3 dir = away.normalize();
        int x = Mth.floor(npcPos.x - dir.x * 2);
        int z = Mth.floor(npcPos.z - dir.z * 2);
        int y = findStandingYNear(level, x, z, threatFeetY);
        if (y == Integer.MIN_VALUE) return null;
        return new BlockPos(x, y, z);
    }

    /**
     * 投掷物躲避走位落点：朝弹道**垂直方向**走开 {@link #DODGE_DIST} 格（含少许远离弹道源分量，
     * 保证是让开而不是迎着弹道跑），优先「NPC→落点 无墙」的可站立格（走过去可达，短躲不至于
     * 寻路失败）。两个垂直方向都不可达返回 null（站定硬吃，靠减伤/回血兜底）。
     * 由 {@link #navigateDodge}（guard）调用。
     */
    public static BlockPos findDodgePos(ServerLevel level, WandscapeNpc npc, Projectile proj) {
        Vec3 v = proj.getDeltaMovement();
        double len = Math.sqrt(v.x * v.x + v.z * v.z);
        if (len < 0.05) return null;
        Vec3 npcPos = npc.getStaffPosition();
        Vec3 away = npcPos.subtract(proj.position());
        if (away.lengthSqr() < 0.01) away = new Vec3(1, 0, 0);
        Vec3 awayN = away.normalize();

        // 弹道两个垂直方向，选与「远离弹道源」更接近的那个（侧跳 + 微微后撤）
        Vec3 perpA = new Vec3(-v.z / len, 0, v.x / len);
        Vec3 perpB = new Vec3(v.z / len, 0, -v.x / len);
        Vec3[] candidates = {
                perpA.dot(awayN) >= perpB.dot(awayN)
                        ? perpA.scale(0.7).add(awayN.scale(0.3)).normalize()
                        : perpB.scale(0.7).add(awayN.scale(0.3)).normalize(),
                perpB.dot(awayN) >= perpA.dot(awayN)
                        ? perpB.scale(0.7).add(awayN.scale(0.3)).normalize()
                        : perpA.scale(0.7).add(awayN.scale(0.3)).normalize()
        };

        int feetY = Mth.floor(npc.getY());
        int npcBlockX = Mth.floor(npc.getX());
        int npcBlockZ = Mth.floor(npc.getZ());
        for (Vec3 dir : candidates) {
            for (double dist : new double[] { DODGE_DIST, DODGE_DIST * 0.6 }) {
                int bx = Mth.floor(npcPos.x + dir.x * dist);
                int bz = Mth.floor(npcPos.z + dir.z * dist);
                if (bx == npcBlockX && bz == npcBlockZ) continue;
                int standY = findStandingYNear(level, bx, bz, feetY);
                if (standY == Integer.MIN_VALUE) continue;
                BlockPos cand = new BlockPos(bx, standY, bz);
                if (positionHasLineOfSight(level, npcPos, staffOf(cand))) return cand;
            }
        }
        return null;
    }

    /** 停止寻路（LOS 已通过 / 要施法时调用），让 NPC 站定。 */
    public static void cancelNavigation(World world, long npcId) {
        if (world == null || world.movementOps == null) return;
        NavigationState nav = world.get(npcId, NavigationState.class);
        if (nav != null && nav.mode != NavigationState.Mode.IDLE) {
            world.movementOps.cancelNavigation(npcId);
        }
    }

    /**
     * 站定取消导航（战斗安全版）：取消 ECS 寻路但**不恢复 wandering**——markInCombat 夺回战斗态，
     * 否则通用 {@link #cancelNavigation} 会 setAiWanderingEnabled(true) 放行闲逛。
     * {@code world == null}（敌对法师 EvilMageCastGoal 传 null 走原版导航）直接跳过，不 NPE。
     */
    static void cancelNpcNavigation(World world, long npcId, WandscapeNpc npc) {
        if (world == null) return;
        NavigationState nav = world.get(npcId, NavigationState.class);
        if (nav != null && nav.mode != NavigationState.Mode.IDLE && world.movementOps != null) {
            world.movementOps.cancelNavigation(npcId);
            markInCombat(npc);
        }
    }
}
