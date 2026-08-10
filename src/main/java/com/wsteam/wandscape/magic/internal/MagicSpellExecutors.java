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
            case "heal" -> castHeal(level, npc, target, def, effCircle);
            case "meteor" -> castMeteor(level, npc, target, def, effCircle);
            case "petrification" -> castPetrification(level, npc, def, effCircle);
            default -> {
                Log.warn(TAG, "未知魔法执行器 id={}", def.id());
                yield false;
            }
        };
    }

    // ── 1. 治疗魔法 (Heal) ──

    public static boolean castHeal(ServerLevel level, WandscapeNpc npc, @Nullable LivingEntity target,
                                  MagicDef def, String circleId) {
        MagicCircleSpec spec = MagicCircleLoader.getSpec(circleId);
        int durationTicks = spec != null ? spec.durationTicks : 120;

        if (!npc.tryCastSpell(def.id(), def.baseCooldown(), def.manaCost(), durationTicks)) {
            return false;
        }

        LivingEntity centerEntity = target != null && target.isAlive() ? target : npc;
        Vec3 pos = centerEntity.position();
        UUID effectId = UUID.randomUUID();

        // 广播法阵在地面生成
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(centerEntity,
                new MagicCircleCastPacket(effectId, pos, new Vec3(0, 1, 0), circleId));

        // 注册持续治疗任务（6秒=120t，每20t治疗4生命）
        MagicEventHandler.addHealAura(new MagicEventHandler.HealAura(
                level, pos, centerEntity, level.getGameTime() + durationTicks, 4.0f, 6.0));

        level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.NEUTRAL, 1.0f, 1.2f);
        Log.info(TAG, "castHeal caster={} pos={} duration={}", npc.getUUID().toString().substring(0, 8), pos, durationTicks);
        return true;
    }

    // ── 2. 陨石魔法 (Meteor) ──

    public static boolean castMeteor(ServerLevel level, WandscapeNpc npc, @Nullable LivingEntity target,
                                    MagicDef def, String circleId) {
        MagicCircleSpec spec = MagicCircleLoader.getSpec(circleId);
        int durationTicks = spec != null ? spec.durationTicks : 120;

        if (!npc.tryCastSpell(def.id(), def.baseCooldown(), def.manaCost(), durationTicks)) {
            return false;
        }

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
                    level, fallingBlock, npc, targetPos.y, 10.0f, 4.0));
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

        if (!npc.tryCastSpell(def.id(), def.baseCooldown(), def.manaCost(), durationTicks)) {
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
                            level, fallingBlock, null, targetPos.y, 10.0f, 4.0));
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
