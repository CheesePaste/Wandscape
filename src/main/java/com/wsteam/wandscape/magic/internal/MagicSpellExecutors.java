package com.wsteam.wandscape.magic.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import com.wsteam.wandscape.magic.data.MagicCircleSpec;
import com.wsteam.wandscape.magic.data.MagicDef;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.network.MagicCircleCastPacket;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 魔法效果执行器集合：负责治疗、陨石、石化等法术的落地效果触发与持续任务注册。
 */
public final class MagicSpellExecutors {

    private static final String TAG = "MagicSpellExecutors";

    private MagicSpellExecutors() {}

    /**
     * 根据 MagicDef id 分发到对应的魔法逻辑实现。
     */
    public static boolean dispatch(ServerLevel level, WandscapeNpc npc, @Nullable LivingEntity target,
                                   MagicDef def, String circleId, int color) {
        if (def == null) return false;
        String effCircle = def.effectCircleId() != null ? def.effectCircleId() : circleId;
        int effColor = def.effectColor() != null ? def.effectColor() : color;

        return switch (def.id()) {
            case MagicCaster.BEAM_MAGIC_ID -> MagicCaster.castNpcAt(level, npc, target, effCircle, effColor);
            case "heal" -> castHeal(level, npc, def, effCircle);
            case "meteor" -> castMeteor(level, npc, target, def, effCircle);
            case "petrification" -> castPetrification(level, npc, def, effCircle);
            case "enfeeble_field" -> castEnfeebleField(level, npc, def, effCircle);
            case "fortification" -> castFortification(level, npc, def, effCircle);
            case "conversion" -> castConversion(level, npc, target, def, effCircle);
            case "desperation" -> castDesperation(level, npc, def, effCircle);
            default -> {
                Log.warn(TAG, "未知魔法执行器 id={}", def.id());
                yield false;
            }
        };
    }

    // ── 1. 治疗魔法 (Heal) ──

    /** 治疗光环覆盖半径（方块）。GuardCombat 的 L0 紧急奶扫描范围须与此一致，保证施放必然够得着目标。 */
    public static final float HEAL_RADIUS = 6.0f;

    public static boolean castHeal(ServerLevel level, WandscapeNpc npc,
                                  MagicDef def, String circleId) {
        MagicCircleSpec spec = MagicCircleLoader.getSpec(circleId);
        int durationTicks = spec != null ? spec.durationTicks : 120;

        if (!npc.tryCastSpell(def.id(), def.baseCooldown(), def.manaCost(), durationTicks / 2)) {
            return false;
        }

        // 治疗以施法者自身为圆心：法阵跟随施法者，覆盖半径内友方 + 施法者自己
        // （落单法师低血时 L0 自奶依赖此圆心；不用战斗 target，避免奶错目标）。
        Vec3 pos = npc.position();
        UUID effectId = UUID.randomUUID();

        // 广播法阵在施法者脚下生成
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(npc,
                new MagicCircleCastPacket(effectId, pos, new Vec3(0, 1, 0), circleId));

        // 注册持续治疗任务（6秒=120t，每20t治疗4生命）
        MagicEventHandler.addHealAura(new MagicEventHandler.HealAura(
                level, pos, npc, level.getGameTime() + durationTicks, 4.0f, HEAL_RADIUS));

        level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.NEUTRAL, 1.0f, 1.2f);
        Log.info(TAG, "castHeal caster={} pos={} duration={}", npc.getUUID().toString().substring(0, 8), pos, durationTicks);
        return true;
    }

    // ── 2. 陨石魔法 (Meteor) ──

    /** 陨石伤害缺省值（magic_spells/meteor.json 未配 effect.damage 时兜底）。 */
    private static final float METEOR_DEFAULT_DAMAGE = 10.0f;

    public static boolean castMeteor(ServerLevel level, WandscapeNpc npc, @Nullable LivingEntity target,
                                    MagicDef def, String circleId) {
        MagicCircleSpec spec = MagicCircleLoader.getSpec(circleId);
        int durationTicks = spec != null ? spec.durationTicks : 120;

        if (!npc.tryCastSpell(def.id(), def.baseCooldown(), def.manaCost(), durationTicks / 2)) {
            return false;
        }

        float damage = def.effectDamage() != null ? def.effectDamage().floatValue() : METEOR_DEFAULT_DAMAGE;
        Vec3 pos = npc.position();
        UUID effectId = UUID.randomUUID();

        // 施法者脚下广播魔法阵
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(npc,
                new MagicCircleCastPacket(effectId, pos, new Vec3(0, 1, 0), circleId));

        // 收集最多 3 个周围生物（优先包含 target，再选半径 16 内的其他敌对生物）
        List<LivingEntity> targets = new ArrayList<>();
        if (target != null && target.isAlive() && !target.isRemoved()) {
            targets.add(target);
        }
        AABB box = npc.getBoundingBox().inflate(16.0);
        for (Entity e : level.getEntities((Entity) null, box, e -> e instanceof Enemy && e.isAlive() && e != target)) {
            if (targets.size() >= 3) break;
            if (e instanceof LivingEntity le) {
                targets.add(le);
            }
        }

        // 为每个目标在头上 14 格生成陨石 (FallingBlockEntity 岩浆块)
        for (LivingEntity t : targets) {
            Vec3 targetPos = t.position();
            BlockPos spawnPos = BlockPos.containing(targetPos.x, targetPos.y + 14.0, targetPos.z);

            FallingBlockEntity fallingBlock = FallingBlockEntity.fall(level, spawnPos, Blocks.MAGMA_BLOCK.defaultBlockState());
            fallingBlock.dropItem = false;
            fallingBlock.disableDrop();

            MagicEventHandler.addMeteorTracker(new MagicEventHandler.MeteorTracker(
                    level, fallingBlock, npc, spawnPos.getY(), targetPos.y, damage, 4.0));
        }

        level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.FIRECHARGE_USE, SoundSource.NEUTRAL, 1.0f, 0.8f);
        Log.info(TAG, "castMeteor caster={} targetsCount={}", npc.getUUID().toString().substring(0, 8), targets.size());
        return true;
    }

    // ── 3. 石化魔法 (Petrification) ──

    public static boolean castPetrification(ServerLevel level, WandscapeNpc npc,
                                           MagicDef def, String circleId) {
        MagicCircleSpec spec = MagicCircleLoader.getSpec(circleId);
        int durationTicks = spec != null ? spec.durationTicks : 100;

        if (!npc.tryCastSpell(def.id(), def.baseCooldown(), def.manaCost(), durationTicks / 2)) {
            return false;
        }

        Vec3 pos = npc.position();
        UUID effectId = UUID.randomUUID();

        // 给自身播脚下石化魔法阵
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(npc,
                new MagicCircleCastPacket(effectId, pos, new Vec3(0, 1, 0), circleId));

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

        if (!npc.tryCastSpell(def.id(), def.baseCooldown(), def.manaCost(), durationTicks / 2)) {
            return false;
        }

        Vec3 pos = npc.position();
        UUID effectId = UUID.randomUUID();

        // 施法者脚下广播魔法阵
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(npc,
                new MagicCircleCastPacket(effectId, pos, new Vec3(0, 1, 0), circleId));

        // 收集半径内所有敌对生物，施加三层 debuff
        AABB box = npc.getBoundingBox().inflate(ENFEEBLE_RADIUS);
        int hitCount = 0;
        for (Entity e : level.getEntities((Entity) null, box,
                entity -> entity instanceof LivingEntity le && le.isAlive()
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

        if (!npc.tryCastSpell(def.id(), def.baseCooldown(), def.manaCost(), durationTicks / 2)) {
            return false;
        }

        Vec3 pos = npc.position();
        UUID effectId = UUID.randomUUID();

        // 自身脚下广播金色赐福法阵
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(npc,
                new MagicCircleCastPacket(effectId, pos, new Vec3(0, 1, 0), circleId));

        npc.addEffect(new MobEffectInstance(WandscapeEffects.FORTIFICATION,
                FORTIFICATION_BUFF_TICKS, 0));                     // 护甲 +4
        npc.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST,
                FORTIFICATION_BUFF_TICKS, 0));                     // 力量 I
        npc.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED,
                FORTIFICATION_BUFF_TICKS, 0));                     // 迅捷 I

        level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.BELL_RESONATE,
                SoundSource.NEUTRAL, 0.7f, 1.0f);
        Log.info(TAG, "castFortification caster={} buffTicks={}",
                npc.getUUID().toString().substring(0, 8), FORTIFICATION_BUFF_TICKS);
        return true;
    }

    // ── 7. 感化 (Conversion) ──
    // 单体控制：长前摇（200t），低消耗，使敌对生物倒戈攻击附近敌人。

    private static final int CONVERSION_DEBUFF_TICKS = 400; // 20s

    public static boolean castConversion(ServerLevel level, WandscapeNpc npc, @Nullable LivingEntity target,
                                          MagicDef def, String circleId) {
        MagicCircleSpec spec = MagicCircleLoader.getSpec(circleId);
        int durationTicks = spec != null ? spec.durationTicks : 200;

        if (!npc.tryCastSpell(def.id(), def.baseCooldown(), def.manaCost(), durationTicks / 2)) {
            return false;
        }

        if (target == null || !target.isAlive()) {
            Log.warn(TAG, "castConversion 目标无效 caster={}", npc.getUUID().toString().substring(0, 8));
            return false;
        }

        Vec3 pos = target.position();
        UUID effectId = UUID.randomUUID();

        // 目标脚下广播感化法阵
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(target,
                new MagicCircleCastPacket(effectId, pos, new Vec3(0, 1, 0), circleId));

        target.addEffect(new MobEffectInstance(WandscapeEffects.CONVERSION,
                CONVERSION_DEBUFF_TICKS, 0));
        MagicEventHandler.addConversion(target, CONVERSION_DEBUFF_TICKS);

        level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.EVOKER_CAST_SPELL,
                SoundSource.NEUTRAL, 0.6f, 1.3f);
        Log.info(TAG, "castConversion caster={} target={} debuffTicks={}",
                npc.getUUID().toString().substring(0, 8),
                target.getName().getString(), CONVERSION_DEBUFF_TICKS);
        return true;
    }

    // ── 8. 背水 (Desperation) ──
    // 自我增益：0 前摇，极高代价输出模式。
    // 有效护甲 = −A/2，力量等级 = floor((A²+55)/55) − 1（二次增长 + 基线补偿）。

    private static final int DESPERATION_BUFF_TICKS = 300; // 15s

    /** 根据护甲值计算背水力量等级（amplifier，0 = 力量 I）。
     *  ≤5 护甲无奖励，6+ 按 A²/48 二次增长。 */
    public static int desperationStrengthAmplifier(float armor) {
        if (armor <= 5.0f) return 0;
        return (int) (armor * armor / 48.0f);
    }

    public static boolean castDesperation(ServerLevel level, WandscapeNpc npc,
                                           MagicDef def, String circleId) {
        MagicCircleSpec spec = MagicCircleLoader.getSpec(circleId);
        int durationTicks = spec != null ? spec.durationTicks : 15;

        if (!npc.tryCastSpell(def.id(), def.baseCooldown(), def.manaCost(), durationTicks / 2)) {
            return false;
        }

        Vec3 pos = npc.position();
        UUID effectId = UUID.randomUUID();

        // 自身脚下广播深红背水法阵
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(npc,
                new MagicCircleCastPacket(effectId, pos, new Vec3(0, 1, 0), circleId));

        float armor = npc.getArmorValue();
        int strengthAmp = desperationStrengthAmplifier(armor);

        npc.addEffect(new MobEffectInstance(WandscapeEffects.DESPERATION,
                DESPERATION_BUFF_TICKS, 0));
        npc.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST,
                DESPERATION_BUFF_TICKS, strengthAmp));

        level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.WITHER_SPAWN,
                SoundSource.NEUTRAL, 0.4f, 0.8f);
        Log.info(TAG, "castDesperation caster={} armor={} strengthAmp={} buffTicks={}",
                npc.getUUID().toString().substring(0, 8), armor, strengthAmp, DESPERATION_BUFF_TICKS);
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
                        level, pos, player, level.getGameTime() + (MagicCircleLoader.getSpec(circleId) != null ? MagicCircleLoader.getSpec(circleId).durationTicks : 120), 4.0f, 6.0));
                level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.NEUTRAL, 1.0f, 1.2f);
                yield true;
            }
            case "meteor" -> {
                Vec3 pos = player.position();
                UUID effectId = UUID.randomUUID();
                PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                        new MagicCircleCastPacket(effectId, pos, new Vec3(0, 1, 0), circleId));

                List<Vec3> targetPositions = new ArrayList<>();
                AABB box = player.getBoundingBox().inflate(16.0);
                for (Entity e : level.getEntities((Entity) null, box, e -> e instanceof Enemy && e.isAlive())) {
                    if (targetPositions.size() >= 3) break;
                    targetPositions.add(e.position());
                }
                if (targetPositions.isEmpty()) {
                    Vec3 look = player.getLookAngle();
                    targetPositions.add(pos.add(look.x * 6, 0, look.z * 6));
                }

                for (Vec3 targetPos : targetPositions) {
                    BlockPos spawnPos = BlockPos.containing(targetPos.x, targetPos.y + 14.0, targetPos.z);
                    FallingBlockEntity fallingBlock = FallingBlockEntity.fall(level, spawnPos, Blocks.MAGMA_BLOCK.defaultBlockState());
                    fallingBlock.dropItem = false;
                    fallingBlock.disableDrop();
                    MagicEventHandler.addMeteorTracker(new MagicEventHandler.MeteorTracker(
                            level, fallingBlock, null, spawnPos.getY(), targetPos.y,
                            def.effectDamage() != null ? def.effectDamage().floatValue() : METEOR_DEFAULT_DAMAGE, 4.0));
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
                BlockPos targetPos = BlockPos.containing(source.add(dir.scale(32.0)));
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
                player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, 300, 0));
                player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 300, 0));
                level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.BELL_RESONATE,
                        SoundSource.NEUTRAL, 0.7f, 1.0f);
                Log.info(TAG, "castFortification player");
                yield true;
            }
            case "conversion" -> {
                // 玩家感化：搜索视线方向最近敌对生物
                Vec3 eye = player.getEyePosition();
                Vec3 look = player.getLookAngle();
                LivingEntity target = null;
                double bestDist = Double.MAX_VALUE;
                for (Entity e : level.getEntities((Entity) null,
                        player.getBoundingBox().inflate(16.0),
                        entity -> entity instanceof Enemy && entity.isAlive())) {
                    Vec3 toTarget = e.position().subtract(eye);
                    double dist = toTarget.length();
                    if (dist < bestDist && dist <= 16.0) {
                        // 粗略视线检查：夹角 < 30°
                        double dot = look.dot(toTarget.normalize());
                        if (dot > 0.866) { // cos 30°
                            target = (LivingEntity) e;
                            bestDist = dist;
                        }
                    }
                }
                if (target != null) {
                    Vec3 pos = target.position();
                    UUID effectId = UUID.randomUUID();
                    PacketDistributor.sendToPlayersTrackingEntityAndSelf(target,
                            new MagicCircleCastPacket(effectId, pos, new Vec3(0, 1, 0), circleId));
                    target.addEffect(new MobEffectInstance(WandscapeEffects.CONVERSION, 400, 0));
                    MagicEventHandler.addConversion(target, 400);
                    level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.EVOKER_CAST_SPELL,
                            SoundSource.NEUTRAL, 0.6f, 1.3f);
                    Log.info(TAG, "castConversion player target={}", target.getName().getString());
                }
                yield true;
            }
            case "desperation" -> {
                Vec3 pos = player.position();
                UUID effectId = UUID.randomUUID();
                PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                        new MagicCircleCastPacket(effectId, pos, new Vec3(0, 1, 0), circleId));
                float armor = player.getArmorValue();
                int strengthAmp = desperationStrengthAmplifier(armor);
                player.addEffect(new MobEffectInstance(WandscapeEffects.DESPERATION, 300, 0));
                player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, 300, strengthAmp));
                level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.WITHER_SPAWN,
                        SoundSource.NEUTRAL, 0.4f, 0.8f);
                Log.info(TAG, "castDesperation player armor={} strengthAmp={}", armor, strengthAmp);
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
