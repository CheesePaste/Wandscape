package com.wsteam.wandscape.content.magic.internal;
import com.wsteam.wandscape.content.npc.component.EquippedMagicComponent;
import com.wsteam.wandscape.content.task.types.EffectId;
import com.wsteam.wandscape.content.task.component.Position;
import com.wsteam.wandscape.content.task.ecs.World;

import com.wsteam.wandscape.content.npc.attributes.NpcAttributes.AttributeType;
import com.wsteam.wandscape.content.magic.data.MagicCircleSpec;
import com.wsteam.wandscape.content.magic.data.MagicDef;
import com.wsteam.wandscape.content.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.content.magic.network.MagicCircleCastPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 魔法效果执行器集合：负责治疗、陨石、石化等法术的落地效果触发与持续任务注册。
 */
public final class MagicSpellExecutors {

    private static final String TAG = "MagicSpellExecutors";

    private MagicSpellExecutors() {}

    // ── 魔力强化独立乘区 ──
    // 魔法输出（伤害/治疗）除 SPELL_POWER 外再乘魔力强化倍率，两倍率各自乘算。
    // SPELL_POWER 是 ECS 自定义属性（非 vanilla Attribute），MobEffect 的 attribute modifier
    // 挂不上，故在核算入口手动乘：伤害走 NpcSpellPowerHandler（guard），治疗走下方 castHeal。

    /** 魔力强化每级加成：1 级 +10%、2 级 +20%（独立乘区，每级 +0.1）。 */
    public static final float MAGIC_ENHANCE_PER_LEVEL = 0.1f;

    /** 魔力强化倍率（纯函数）：amplifier 0（I 级）= 1.2，每级 +0.1。 */
    public static float magicEnhanceMultiplier(int amplifier) {
        return 1f + MAGIC_ENHANCE_PER_LEVEL * (amplifier + 1);
    }

    /** 施法者身上的魔力强化倍率；无施法者或无该效果时 = 1。 */
    public static float magicEnhanceMultiplier(@Nullable LivingEntity caster) {
        if (caster == null || !caster.hasEffect(WandscapeEffects.MAGIC_ENHANCE)) return 1f;
        return magicEnhanceMultiplier(caster.getEffect(WandscapeEffects.MAGIC_ENHANCE).getAmplifier());
    }

    /** 根据 MagicDef id 分发到对应的魔法逻辑实现。 */
    public static boolean dispatch(ServerLevel level, WandscapeNpc npc, @Nullable LivingEntity target,
                                   MagicDef def, String circleId, int color) {
        if (def == null) return false;
        String effCircle = def.effectCircleId() != null ? def.effectCircleId() : circleId;
        int effColor = def.effectColor() != null ? def.effectColor() : color;

        return switch (def.id()) {
            case MagicCaster.BEAM_MAGIC_ID -> MagicCaster.castNpcAt(level, npc, target, effCircle, effColor);
            case "heal" -> castHeal(level, npc, def, effCircle);
            case "meteor" -> castMeteor(level, npc, def, effCircle);
            case "petrification" -> castPetrification(level, npc, def, effCircle);
            case "enfeeble_field" -> castEnfeebleField(level, npc, def, effCircle);
            case "fortification" -> castFortification(level, npc, def, effCircle);
            case "conversion" -> castConversion(level, npc, def, effCircle);
            case "desperation" -> castDesperation(level, npc, def, effCircle);
            default -> {
                if (com.wsteam.wandscape.compat.ironspellbooks.IronSpellsCompat.isLoaded()
                        && com.wsteam.wandscape.compat.ironspellbooks.IronSpellsHelper.isValidSpell(def.id())) {
                    com.wsteam.wandscape.content.npc.component.EquippedMagicComponent.SpellEntry entry =
                            npc.equippedMagic.getEntry(def.id());
                    int spellLevel = entry != null ? entry.level() : 1;
                    yield com.wsteam.wandscape.compat.ironspellbooks.IronSpellsCaster.cast(
                            level, npc, target, def.id(), spellLevel);
                }
                Log.warn(TAG, "未知魔法执行器 id={}", def.id());
                yield false;
            }
        };
    }

    // ── 1. 治疗魔法 (Heal) ──

    /**
     * 根据施法者 SPELL_SPEED 属性（含铁魔法冷却/吟唱缩减装备加成）计算实际施法锁定时长（前摇引导）。
     */
    private static int computeLockTicks(WandscapeNpc npc, int durationTicks) {
        float speed = Math.max(0.1f, npc.getEffectiveAttribute(AttributeType.SPELL_SPEED));
        return Math.max(5, (int) Math.ceil((durationTicks / 2.0) / speed));
    }

    /** 治疗光环覆盖半径（方块）。GuardCombat 的 L0 紧急奶扫描范围须与此一致，保证施放必然够得着目标。 */
    public static final float HEAL_RADIUS = 6.0f;

    /** 治疗光环每脉冲基础量（SPELL_POWER=1 时每 20t 治疗量，默认 4 = 2 颗心）；按施法者 SPELL_POWER 放大。 */
    private static final float HEAL_BASE_AMOUNT = 4.0f;

    public static boolean castHeal(ServerLevel level, WandscapeNpc npc,
                                  MagicDef def, String circleId) {
        MagicCircleSpec spec = MagicCircleLoader.getSpec(circleId);
        int durationTicks = spec != null ? spec.durationTicks : 120;

        if (!npc.tryCastSpell(def.id(), def.baseCooldown(), def.manaCost(), computeLockTicks(npc, durationTicks))) {
            return false;
        }

        // 治疗以施法者自身为圆心：法阵跟随施法者，覆盖半径内友方 + 施法者自己
        // （落单法师低血时 L0 自奶依赖此圆心；不用战斗 target，避免奶错目标）。
        Vec3 pos = npc.position();
        UUID effectId = UUID.randomUUID();

        // 广播法阵在施法者脚下生成
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(npc,
                new MagicCircleCastPacket(effectId, pos, new Vec3(0, 1, 0), circleId, npc.getUUID()));

        // 注册持续治疗任务（6秒=120t，每20t治疗 HEAL_BASE_AMOUNT × SPELL_POWER × 魔力强化 生命）：
        // 治疗量与伤害同源走 SPELL_POWER 加成（默认 1.0 → 每脉冲 4 点，强法师奶更多），
        // 再乘魔力强化独立乘区（I 级 +20%）；玩家命令 castForPlayer 走基础量，玩家无 SPELL_POWER
        float healAmount = HEAL_BASE_AMOUNT
                * Math.max(0f, npc.getEffectiveAttribute(AttributeType.SPELL_POWER))
                * magicEnhanceMultiplier(npc);
        MagicEventHandler.addHealAura(new MagicEventHandler.HealAura(
                level, pos, npc, level.getGameTime() + durationTicks, healAmount, HEAL_RADIUS));

        level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.NEUTRAL, 1.0f, 1.0f);
        Log.info(TAG, "castHeal caster={} healAmount={} durationTicks={}",
                npc.getUUID().toString().substring(0, 8), healAmount, durationTicks);
        return true;
    }

    // ── 2. 陨石魔法 (Meteor) ──

    /** 连落陨石总颗数（法阵周期内逐颗发射，每颗发射时动态重瞄最近敌人）。 */
    public static final int METEOR_TOTAL = 6;

    /** 连落陨石默认每颗伤害（MagicDef.effectDamage 为 null 时保底）。 */
    public static final float METEOR_DEFAULT_DAMAGE = 10.0f;

    /** 陨石重瞄扫描半径（方块）。 */
    public static final double METEOR_SCAN_RADIUS = 32.0;

    /** 每颗陨石发射间隔 tick（= durationTicks / METEOR_TOTAL）。 */
    static int meteorIntervalTicks(int durationTicks) {
        return Math.max(1, durationTicks / METEOR_TOTAL);
    }

    /** 在目标头顶 14 格生成 1 颗陨石砸向 pos（落点即溅射圆心）。caster 为空（玩家命令）时陨石不跳过施法者。 */
    static void spawnMeteorAt(ServerLevel level, @Nullable WandscapeNpc caster,
                              Vec3 pos, float damage, double radius) {
        BlockPos spawnPos = BlockPos.containing(pos.x, pos.y + 14.0, pos.z);
        FallingBlockEntity fallingBlock = FallingBlockEntity.fall(level, spawnPos, Blocks.MAGMA_BLOCK.defaultBlockState());
        fallingBlock.dropItem = false;
        fallingBlock.disableDrop();
        MagicEventHandler.addMeteorTracker(new MagicEventHandler.MeteorTracker(
                level, fallingBlock, caster, spawnPos.getY(), pos.y, damage, radius));
    }

    /** 连落陨石发射时的动态重瞄：以 caster 当前位置（已移除则 origin）为基准，在 scanRadius 内找最近存活敌对生物，
     *  把 1 颗陨石砸向它当前的位置；无目标（已被清完）则跳过本颗。 */
    static void fireMeteorAtNearestEnemy(ServerLevel level, Vec3 origin, @Nullable WandscapeNpc caster,
                                         float damage, double radius, double scanRadius) {
        Vec3 center = (caster != null && !caster.isRemoved()) ? caster.position() : origin;
        AABB box = new AABB(center.x - scanRadius, center.y - 16.0, center.z - scanRadius,
                            center.x + scanRadius, center.y + 16.0, center.z + scanRadius);
        LivingEntity nearest = null;
        double bestSqr = Double.MAX_VALUE;
        for (Entity e : level.getEntities((Entity) null, box, e -> e instanceof Enemy && e.isAlive())) {
            if (e instanceof LivingEntity le) {
                // 友军（含己方/同殖民地召唤物）不重瞄
                if (caster != null && !caster.isRemoved() && caster.isFriendlyForce(le)) continue;
                double d = center.distanceToSqr(le.position());
                if (d < bestSqr) {
                    bestSqr = d;
                    nearest = le;
                }
            }
        }
        if (nearest == null) {
            Log.info(TAG, "fireMeteorAtNearestEnemy 无目标 skip origin={}", origin);
            return;
        }
        spawnMeteorAt(level, caster, nearest.position(), damage, radius);
    }

    public static boolean castMeteor(ServerLevel level, WandscapeNpc npc,
                                    MagicDef def, String circleId) {
        MagicCircleSpec spec = MagicCircleLoader.getSpec(circleId);
        int durationTicks = spec != null ? spec.durationTicks : 120;

        if (!npc.tryCastSpell(def.id(), def.baseCooldown(), def.manaCost(), computeLockTicks(npc, durationTicks))) {
            return false;
        }

        float damage = (def.effectDamage() != null ? def.effectDamage().floatValue() : METEOR_DEFAULT_DAMAGE)
                * Math.max(0f, npc.getEffectiveAttribute(AttributeType.SPELL_POWER))
                * magicEnhanceMultiplier(npc);
        Vec3 pos = npc.position();
        UUID effectId = UUID.randomUUID();

        // 施法者脚下广播魔法阵
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(npc,
                new MagicCircleCastPacket(effectId, pos, new Vec3(0, 1, 0), circleId, npc.getUUID()));

        // 连落 6 颗：按 1/6 持续时长逐颗发射，每颗发射时动态重选当时最近的敌对目标（不预分配落点）
        int interval = meteorIntervalTicks(durationTicks);
        long now = level.getGameTime();
        for (int i = 0; i < METEOR_TOTAL; i++) {
            MagicEventHandler.addPendingMeteor(level, pos, npc, damage, 4.0, METEOR_SCAN_RADIUS, now + (long) i * interval);
        }

        level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.FIRECHARGE_USE, SoundSource.NEUTRAL, 1.0f, 0.8f);
        Log.info(TAG, "castMeteor caster={} interval={}", npc.getUUID().toString().substring(0, 8), interval);
        return true;
    }

    // ── 3. 石化魔法 (Petrification) ──

    public static boolean castPetrification(ServerLevel level, WandscapeNpc npc,
                                           MagicDef def, String circleId) {
        MagicCircleSpec spec = MagicCircleLoader.getSpec(circleId);
        int durationTicks = spec != null ? spec.durationTicks : 100;

        if (!npc.tryCastSpell(def.id(), def.baseCooldown(), def.manaCost(), computeLockTicks(npc, durationTicks))) {
            return false;
        }

        Vec3 pos = npc.position();
        UUID effectId = UUID.randomUUID();

        // 给自身播脚下石化魔法阵
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(npc,
                new MagicCircleCastPacket(effectId, pos, new Vec3(0, 1, 0), circleId, npc.getUUID()));

        // 给自己施加 30 秒 (600 ticks) 石化 buff
        npc.addEffect(new MobEffectInstance(WandscapeEffects.PETRIFICATION, 600, 0));

        level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.STONE_BREAK, SoundSource.NEUTRAL, 1.2f, 0.6f);
        Log.info(TAG, "castPetrification caster={} buffDuration=600", npc.getUUID().toString().substring(0, 8));
        return true;
    }

    // ── 5. 群体诅咒魔法 (Enfeeble Field) ──
    // 喷溅式 AoE：施法瞬间对半径内所有敌对生物施加迟缓 I + 虚弱 I + 护甲削减（30 秒）。

    private static final int ENFEEBLE_DEBUFF_TICKS = 300; // 15s
    private static final double ENFEEBLE_RADIUS = 5.8;    // 与法阵最大半径对齐

    public static boolean castEnfeebleField(ServerLevel level, WandscapeNpc npc,
                                            MagicDef def, String circleId) {
        MagicCircleSpec spec = MagicCircleLoader.getSpec(circleId);
        int durationTicks = spec != null ? spec.durationTicks : 140;

        if (!npc.tryCastSpell(def.id(), def.baseCooldown(), def.manaCost(), computeLockTicks(npc, durationTicks))) {
            return false;
        }

        Vec3 pos = npc.position();
        UUID effectId = UUID.randomUUID();

        // 施法者脚下广播魔法阵
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(npc,
                new MagicCircleCastPacket(effectId, pos, new Vec3(0, 1, 0), circleId, npc.getUUID()));

        // 收集半径内所有敌对生物，施加三层 debuff（友军——含己方/同殖民地召唤物——不中招）
        AABB box = npc.getBoundingBox().inflate(ENFEEBLE_RADIUS);
        int hitCount = 0;
        for (Entity e : level.getEntities((Entity) null, box,
                entity -> entity instanceof LivingEntity le && le.isAlive()
                        && !npc.isFriendlyForce(le)
                        && (le instanceof Enemy || npc.canBeamHurt(le)))) {
            LivingEntity target = (LivingEntity) e;
            target.addEffect(new MobEffectInstance(
                    MobEffects.MOVEMENT_SLOWDOWN,
                    ENFEEBLE_DEBUFF_TICKS, 0)); // 迟缓 I
            target.addEffect(new MobEffectInstance(
                    MobEffects.WEAKNESS,
                    ENFEEBLE_DEBUFF_TICKS, 0)); // 虚弱 I
            target.addEffect(new MobEffectInstance(
                    WandscapeEffects.ARMOR_SHRED,
                    ENFEEBLE_DEBUFF_TICKS, 0)); // 护甲 -4
            hitCount++;
        }

        level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.WARDEN_SONIC_BOOM,
                SoundSource.NEUTRAL, 0.5f, 0.4f);
        Log.info(TAG, "castEnfeebleField caster={} hits={} debuffTicks={}",
                npc.getUUID().toString().substring(0, 8), hitCount, ENFEEBLE_DEBUFF_TICKS);
        return true;
    }

    // ── 6. 战争赐福 (Fortification) ──
    // 自我增益：护甲 +4 + 力量 I + 迅捷 I（30 秒）。

    private static final int FORTIFICATION_BUFF_TICKS = 300; // 15s

    public static boolean castFortification(ServerLevel level, WandscapeNpc npc,
                                            MagicDef def, String circleId) {
        MagicCircleSpec spec = MagicCircleLoader.getSpec(circleId);
        int durationTicks = spec != null ? spec.durationTicks : 120;

        if (!npc.tryCastSpell(def.id(), def.baseCooldown(), def.manaCost(), computeLockTicks(npc, durationTicks))) {
            return false;
        }

        Vec3 pos = npc.position();
        UUID effectId = UUID.randomUUID();

        // 自身脚下广播金色赐福法阵
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(npc,
                new MagicCircleCastPacket(effectId, pos, new Vec3(0, 1, 0), circleId, npc.getUUID()));

        // 护甲 +4 + 魔力强化 I（+20% 魔法伤害）+ 迅捷 I（30 秒）。
        // 原 vanilla 力量（DAMAGE_BOOST）只加成近战攻击力，对纯法师无效，替换为魔力强化。
        npc.addEffect(new MobEffectInstance(WandscapeEffects.FORTIFICATION,
                FORTIFICATION_BUFF_TICKS, 0));                     // 护甲 +4
        npc.addEffect(new MobEffectInstance(WandscapeEffects.MAGIC_ENHANCE,
                FORTIFICATION_BUFF_TICKS, 0));                     // 魔力强化 I
        npc.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED,
                FORTIFICATION_BUFF_TICKS, 0));                     // 迅捷 I

        level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.BELL_RESONATE,
                SoundSource.NEUTRAL, 0.7f, 1.0f);
        Log.info(TAG, "castFortification caster={} buffTicks={}",
                npc.getUUID().toString().substring(0, 8), FORTIFICATION_BUFF_TICKS);
        return true;
    }

    // ── 7. 感化 (Conversion) ──
    // 群体控制：施法瞬间魅惑最近的 N 个敌对生物（不中途追加），使其倒戈攻击附近敌人；受伤即解除（见 onLivingDamage）。

    private static final int CONVERSION_DEBUFF_TICKS = 400; // 20s

    /** 一次魅惑的敌人数（最近的 N 个，施法瞬间全部命中）。 */
    private static final int CONVERSION_CHARM_COUNT = 3;

    public static boolean castConversion(ServerLevel level, WandscapeNpc npc,
                                          MagicDef def, String circleId) {
        MagicCircleSpec spec = MagicCircleLoader.getSpec(circleId);
        int durationTicks = spec != null ? spec.durationTicks : 200;

        if (!npc.tryCastSpell(def.id(), def.baseCooldown(), def.manaCost(), computeLockTicks(npc, durationTicks))) {
            return false;
        }

        // 施法瞬间魅惑最近的 CONVERSION_CHARM_COUNT 个敌对生物（16 格内按距施法者近→远，不中途追加；
        // 受伤即解除 charm，见 MagicEventHandler.onLivingDamage）。友军（含己方/同殖民地召唤物）不魅惑。
        AABB box = npc.getBoundingBox().inflate(16.0);
        List<LivingEntity> enemies = new ArrayList<>();
        for (Entity e : level.getEntities((Entity) null, box,
                e -> e instanceof LivingEntity le && le instanceof Enemy
                        && le.isAlive() && !npc.isFriendlyForce(le))) {
            enemies.add((LivingEntity) e);
        }
        enemies.sort(Comparator.comparingDouble(t -> npc.distanceToSqr(t)));
        if (enemies.isEmpty()) {
            Log.warn(TAG, "castConversion 无敌人可魅惑 caster={}", npc.getUUID().toString().substring(0, 8));
            return false;
        }

        Vec3 pos = npc.position();
        UUID effectId = UUID.randomUUID();

        // 施法者脚下广播感化法阵（跟随 NPC，范围覆盖最近几个敌人）
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(npc,
                new MagicCircleCastPacket(effectId, pos, new Vec3(0, 1, 0), circleId, npc.getUUID()));

        int count = Math.min(CONVERSION_CHARM_COUNT, enemies.size());
        for (int i = 0; i < count; i++) {
            LivingEntity e = enemies.get(i);
            e.addEffect(new MobEffectInstance(WandscapeEffects.CONVERSION,
                    CONVERSION_DEBUFF_TICKS, 0));
            MagicEventHandler.addConversion(e, CONVERSION_DEBUFF_TICKS);
        }

        level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.EVOKER_CAST_SPELL,
                SoundSource.NEUTRAL, 0.6f, 1.3f);
        Log.info(TAG, "castConversion caster={} charmed={}/{} debuffTicks={}",
                npc.getUUID().toString().substring(0, 8), count, enemies.size(), CONVERSION_DEBUFF_TICKS);
        return true;
    }

    // ── 8. 背水一战 (Desperation) ──
    // 自我增益：0 前摇，极高代价输出模式。
    // 有效护甲 = −A/2（下限 −16，见 onLivingDamage），魔力强化等级 = min(10, ⌊A²/100⌋)
    // （二次增长，护甲 <10 无奖励；2026-08-19 从 A²/48 削弱并加上限。原为力量等级，
    // 力量对纯法师无效，改作魔力强化——护甲越高魔法伤害加成越多）。

    private static final int DESPERATION_BUFF_TICKS = 300; // 15s

    /** 背水魔力强化等级上限（amplifier ≤ 10，护甲再高也不超过强化 X）。 */
    private static final int DESPERATION_MAX_ENHANCE_AMPLIFIER = 10;

    /** 根据护甲值计算背水魔力强化等级（amplifier，0 = 强化 I）。
     *  <10 护甲无奖励，10+ 按 A²/100 二次增长，最高强化 X（amplifier 10）。 */
    public static int desperationEnhanceAmplifier(float armor) {
        if (armor < 10.0f) return 0;
        return Math.min(DESPERATION_MAX_ENHANCE_AMPLIFIER, (int) (armor * armor / 100.0f));
    }

    public static boolean castDesperation(ServerLevel level, WandscapeNpc npc,
                                           MagicDef def, String circleId) {
        MagicCircleSpec spec = MagicCircleLoader.getSpec(circleId);
        int durationTicks = spec != null ? spec.durationTicks : 15;

        if (!npc.tryCastSpell(def.id(), def.baseCooldown(), def.manaCost(), computeLockTicks(npc, durationTicks))) {
            return false;
        }

        Vec3 pos = npc.position();
        UUID effectId = UUID.randomUUID();

        // 自身脚下广播深红背水法阵
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(npc,
                new MagicCircleCastPacket(effectId, pos, new Vec3(0, 1, 0), circleId, npc.getUUID()));

        float armor = npc.getArmorValue();
        int enhanceAmp = desperationEnhanceAmplifier(armor);

        npc.addEffect(new MobEffectInstance(WandscapeEffects.DESPERATION,
                DESPERATION_BUFF_TICKS, 0));
        npc.addEffect(new MobEffectInstance(WandscapeEffects.MAGIC_ENHANCE,
                DESPERATION_BUFF_TICKS, enhanceAmp));

        level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.WITHER_SPAWN,
                SoundSource.NEUTRAL, 0.4f, 0.8f);
        Log.info(TAG, "castDesperation caster={} armor={} enhanceAmp={} buffTicks={}",
                npc.getUUID().toString().substring(0, 8), armor, enhanceAmp, DESPERATION_BUFF_TICKS);
        return true;
    }

    // ── 4. 为玩家直接施加/测试魔法 ──

    public static boolean castForPlayer(net.minecraft.server.level.ServerPlayer player, MagicDef def) {
        if (def == null || player == null) return false;
        ServerLevel level = player.serverLevel();
        String circleId = def.effectCircleId() != null ? def.effectCircleId() : MagicCaster.DEFAULT_CIRCLE;
        int color = def.effectColor() != null ? def.effectColor() : MagicCaster.DEFAULT_COLOR;

        return switch (def.id()) {
            case "heal" -> {
                Vec3 pos = player.position();
                UUID effectId = UUID.randomUUID();
                PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                        new MagicCircleCastPacket(effectId, pos, new Vec3(0, 1, 0), circleId));
                MagicEventHandler.addHealAura(new MagicEventHandler.HealAura(
                        level, pos, player, level.getGameTime() + (MagicCircleLoader.getSpec(circleId) != null ? MagicCircleLoader.getSpec(circleId).durationTicks : 120), HEAL_BASE_AMOUNT, 6.0));
                level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.NEUTRAL, 1.0f, 1.2f);
                yield true;
            }
            case "meteor" -> {
                Vec3 pos = player.position();
                UUID effectId = UUID.randomUUID();
                PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                        new MagicCircleCastPacket(effectId, pos, new Vec3(0, 1, 0), circleId));

                float damage = def.effectDamage() != null ? def.effectDamage().floatValue() : METEOR_DEFAULT_DAMAGE;

                // 无目标 → 视线前方 6 格落 1 颗（调试命令兜底）；有目标 → 连落 6 颗，
                // 每颗发射时动态重选当时最近的敌对目标（以施法瞬间玩家位置为扫描基准）
                AABB box = player.getBoundingBox().inflate(16.0);
                boolean hasEnemy = !level.getEntities((Entity) null, box, e -> e instanceof Enemy && e.isAlive()).isEmpty();
                if (!hasEnemy) {
                    Vec3 look = player.getLookAngle();
                    spawnMeteorAt(level, null, pos.add(look.x * 6, 0, look.z * 6), damage, 4.0);
                } else {
                    int durationTicks = MagicCircleLoader.getSpec(circleId) != null
                            ? MagicCircleLoader.getSpec(circleId).durationTicks : 120;
                    int interval = meteorIntervalTicks(durationTicks);
                    long now = level.getGameTime();
                    for (int i = 0; i < METEOR_TOTAL; i++) {
                        MagicEventHandler.addPendingMeteor(level, pos, null, damage, 4.0, METEOR_SCAN_RADIUS, now + (long) i * interval);
                    }
                }

                level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.FIRECHARGE_USE, SoundSource.NEUTRAL, 1.0f, 0.8f);
                yield true;
            }
            case "petrification" -> {
                Vec3 pos = player.position();
                UUID effectId = UUID.randomUUID();
                PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                        new MagicCircleCastPacket(effectId, pos, new Vec3(0, 1, 0), circleId));
                player.addEffect(new MobEffectInstance(WandscapeEffects.PETRIFICATION, 600, 0));
                level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.STONE_BREAK, SoundSource.NEUTRAL, 1.2f, 0.6f);
                yield true;
            }
            case MagicCaster.BEAM_MAGIC_ID -> {
                Vec3 hand = player.getEyePosition();
                Vec3 dir = player.getLookAngle();
                Vec3 source = hand.add(dir.scale(1.0));
                Vec3 targetPos = source.add(dir.scale(32.0));
                UUID effectId = player.getUUID();
                PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                        new MagicCircleCastPacket(effectId, source, dir, circleId));
                MagicCastManager.schedule(level, effectId, source, targetPos, color,
                        MagicCaster.BEAM_SPAWN_DELAY, 120, null, null);
                yield true;
            }
            case "enfeeble_field" -> {
                Vec3 pos = player.position();
                UUID effectId = UUID.randomUUID();
                PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                        new MagicCircleCastPacket(effectId, pos, new Vec3(0, 1, 0), circleId));

                AABB box = player.getBoundingBox().inflate(5.8);
                int hitCount = 0;
                for (Entity e : level.getEntities((Entity) null, box,
                        entity -> entity instanceof LivingEntity le && le.isAlive()
                                && (le instanceof Enemy || le instanceof net.minecraft.world.entity.player.Player))) {
                    LivingEntity target = (LivingEntity) e;
                    target.addEffect(new MobEffectInstance(
                            MobEffects.MOVEMENT_SLOWDOWN, 300, 0));
                    target.addEffect(new MobEffectInstance(
                            MobEffects.WEAKNESS, 300, 0));
                    target.addEffect(new MobEffectInstance(
                            WandscapeEffects.ARMOR_SHRED, 300, 0));
                    hitCount++;
                }
                level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.WARDEN_SONIC_BOOM,
                        SoundSource.NEUTRAL, 0.5f, 0.4f);
                Log.info(TAG, "castEnfeebleField player hits={}", hitCount);
                yield true;
            }
            case "fortification" -> {
                Vec3 pos = player.position();
                UUID effectId = UUID.randomUUID();
                PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                        new MagicCircleCastPacket(effectId, pos, new Vec3(0, 1, 0), circleId));
                player.addEffect(new MobEffectInstance(WandscapeEffects.FORTIFICATION, 300, 0));
                player.addEffect(new MobEffectInstance(WandscapeEffects.MAGIC_ENHANCE, 300, 0)); // 魔力强化 I（玩家暂无施法入口，仅显示；不保留力量）
                player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 300, 0));
                level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.BELL_RESONATE,
                        SoundSource.NEUTRAL, 0.7f, 1.0f);
                Log.info(TAG, "castFortification player");
                yield true;
            }
            case "conversion" -> {
                // 玩家感化：施法瞬间魅惑最近的 3 个敌对生物（同 NPC castConversion，受伤即解除）
                Vec3 pos = player.position();
                UUID effectId = UUID.randomUUID();
                PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                        new MagicCircleCastPacket(effectId, pos, new Vec3(0, 1, 0), circleId));

                AABB box = player.getBoundingBox().inflate(16.0);
                List<LivingEntity> enemies = new ArrayList<>();
                for (Entity e : level.getEntities((Entity) null, box, entity -> entity instanceof Enemy && entity.isAlive())) {
                    if (e instanceof LivingEntity le) enemies.add(le);
                }
                enemies.sort(Comparator.comparingDouble(t -> player.distanceToSqr(t)));
                int count = Math.min(CONVERSION_CHARM_COUNT, enemies.size());
                for (int i = 0; i < count; i++) {
                    LivingEntity e = enemies.get(i);
                    e.addEffect(new MobEffectInstance(WandscapeEffects.CONVERSION, CONVERSION_DEBUFF_TICKS, 0));
                    MagicEventHandler.addConversion(e, CONVERSION_DEBUFF_TICKS);
                }
                level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.EVOKER_CAST_SPELL,
                        SoundSource.NEUTRAL, 0.6f, 1.3f);
                Log.info(TAG, "castConversion player charmed={}", count);
                yield true;
            }
            case "desperation" -> {
                Vec3 pos = player.position();
                UUID effectId = UUID.randomUUID();
                PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                        new MagicCircleCastPacket(effectId, pos, new Vec3(0, 1, 0), circleId));
                float armor = player.getArmorValue();
                int enhanceAmp = desperationEnhanceAmplifier(armor);
                player.addEffect(new MobEffectInstance(WandscapeEffects.DESPERATION, 300, 0));
                player.addEffect(new MobEffectInstance(WandscapeEffects.MAGIC_ENHANCE, 300, enhanceAmp)); // 魔力强化（玩家暂无施法入口，仅显示；不保留力量）
                level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.WITHER_SPAWN,
                        SoundSource.NEUTRAL, 0.4f, 0.8f);
                Log.info(TAG, "castDesperation player armor={} enhanceAmp={}", armor, enhanceAmp);
                yield true;
            }
            default -> {
                Vec3 pos = player.position();
                UUID effectId = UUID.randomUUID();
                PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                        new MagicCircleCastPacket(effectId, pos, new Vec3(0, 1, 0), circleId));
                level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.NEUTRAL, 1.0f, 1.0f);
                yield true;
            }
        };
    }
}
