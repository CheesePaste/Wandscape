package com.wsteam.wandscape.magic.internal;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.annotation.Nullable;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.shared.log.Log;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@EventBusSubscriber(modid = Wandscape.MODID)
public final class MagicEventHandler {

    private static final String TAG = "MagicEventHandler";

    public record HealAura(ServerLevel level, Vec3 center, @Nullable Entity targetEntity,
                           long expireTick, float healAmount, double radius) {}

    public record MeteorTracker(ServerLevel level, FallingBlockEntity entity,
                                WandscapeNpc caster, double targetY, float damage, double radius) {}

    private static final List<HealAura> HEAL_AURAS = new ArrayList<>();
    private static final List<MeteorTracker> METEORS = new ArrayList<>();

    public static synchronized void addHealAura(HealAura aura) {
        HEAL_AURAS.add(aura);
    }

    public static synchronized void addMeteorTracker(MeteorTracker tracker) {
        METEORS.add(tracker);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        tickHealAuras();
        tickMeteors();
    }

    private static synchronized void tickHealAuras() {
        if (HEAL_AURAS.isEmpty()) return;
        Iterator<HealAura> it = HEAL_AURAS.iterator();
        while (it.hasNext()) {
            HealAura aura = it.next();
            long currentTime = aura.level().getGameTime();
            if (currentTime >= aura.expireTick()) {
                it.remove();
                continue;
            }

            // 每秒 (20 ticks) 触发一次治疗
            if (currentTime % 20 == 0) {
                Vec3 center = aura.targetEntity() != null && aura.targetEntity().isAlive()
                        ? aura.targetEntity().position()
                        : aura.center();

                AABB area = new AABB(center.x - aura.radius(), center.y - 2, center.z - aura.radius(),
                                     center.x + aura.radius(), center.y + 4, center.z + aura.radius());

                List<LivingEntity> allies = aura.level().getEntitiesOfClass(LivingEntity.class, area, e ->
                        e.isAlive() && (e instanceof WandscapeNpc || e instanceof Player || e instanceof Villager));

                for (LivingEntity ally : allies) {
                    ally.heal(aura.healAmount());
                    aura.level().sendParticles(ParticleTypes.HAPPY_VILLAGER,
                            ally.getX(), ally.getY() + 1.0, ally.getZ(),
                            5, 0.3, 0.5, 0.3, 0.05);
                }
            }
        }
    }

    private static synchronized void tickMeteors() {
        if (METEORS.isEmpty()) return;
        Iterator<MeteorTracker> it = METEORS.iterator();
        while (it.hasNext()) {
            MeteorTracker meteor = it.next();
            FallingBlockEntity entity = meteor.entity();
            ServerLevel level = meteor.level();

            // 如果实体已被移除，清理项
            if (entity.isRemoved()) {
                it.remove();
                continue;
            }

            // 撞击条件：接近目标 Y 坐标、触底或停滞
            boolean landed = entity.onGround() || entity.getY() <= meteor.targetY() + 0.5 || entity.getDeltaMovement().lengthSqr() < 0.001;

            if (landed) {
                Vec3 impactPos = entity.position();
                BlockPos bpos = entity.blockPosition();

                // 破碎粒子与音效
                level.levelEvent(2001, bpos, Block.getId(Blocks.MAGMA_BLOCK.defaultBlockState()));
                level.playSound(null, impactPos.x, impactPos.y, impactPos.z,
                        SoundEvents.GENERIC_EXPLODE, SoundSource.NEUTRAL, 1.0f, 1.2f);

                // 对半径 4 内生物造成 10 点魔法伤害
                AABB area = new AABB(impactPos.x - meteor.radius(), impactPos.y - 2, impactPos.z - meteor.radius(),
                                     impactPos.x + meteor.radius(), impactPos.y + 3, impactPos.z + meteor.radius());

                List<LivingEntity> targets = level.getEntitiesOfClass(LivingEntity.class, area, LivingEntity::isAlive);
                for (LivingEntity target : targets) {
                    if (meteor.caster() != null && target == meteor.caster()) continue;
                    target.hurt(level.damageSources().indirectMagic(meteor.caster(), meteor.caster()), meteor.damage());
                }

                // 移除陨石实体，不上方块
                entity.discard();
                it.remove();
            }
        }
    }

    /**
     * 石化 Buff 减伤逻辑：受到伤害 -2，低于 2 彻底无视（归 0）。
     */
    @SubscribeEvent
    public static void onLivingDamage(LivingDamageEvent.Pre event) {
        LivingEntity entity = event.getEntity();
        if (entity != null && entity.hasEffect(WandscapeEffects.PETRIFICATION)) {
            float originalDamage = event.getNewDamage();
            float reducedDamage = Math.max(0.0f, originalDamage - 2.0f);
            event.setNewDamage(reducedDamage);

            if (entity.level() instanceof ServerLevel level) {
                level.sendParticles(ParticleTypes.CRIT,
                        entity.getX(), entity.getY() + entity.getBbHeight() * 0.5, entity.getZ(),
                        6, 0.2, 0.3, 0.2, 0.05);
                level.playSound(null, entity.getX(), entity.getY(), entity.getZ(),
                        SoundEvents.STONE_HIT, SoundSource.NEUTRAL, 0.8f, 1.0f);
            }

            Log.info(TAG, "Petrification damage reduction: raw={}, final={} for entity={}",
                    originalDamage, reducedDamage, entity.getName().getString());
        }
    }
}
