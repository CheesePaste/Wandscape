package com.wsteam.wandscape.magic.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.shared.log.Log;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import org.joml.Vector3f;

@EventBusSubscriber(modid = Wandscape.MODID)
public final class MagicEventHandler {

    private static final String TAG = "MagicEventHandler";

    public record HealAura(ServerLevel level, Vec3 center, @Nullable Entity targetEntity,
                           long expireTick, float healAmount, double radius) {}

    public record MeteorTracker(ServerLevel level, FallingBlockEntity entity,
                                WandscapeNpc caster, double spawnY, double targetY,
                                float damage, double radius) {}

    public record Shockwave(ServerLevel level, Vec3 center, double maxRadius,
                            int totalTicks, int remaining) {}

    private static final List<HealAura> HEAL_AURAS = new ArrayList<>();
    private static final List<MeteorTracker> METEORS = new ArrayList<>();
    private static final List<Shockwave> SHOCKWAVES = new ArrayList<>();

    // ── 陨石连落（meteor 6 颗按 1/6 持续时长逐颗落下） ──
    // 施法时由 MagicSpellExecutors 登记 6 个延迟落点，到 fireTick 生成 1 颗 FallingBlockEntity 并转交 MeteorTracker。
    private record PendingMeteor(ServerLevel level, Vec3 pos, @Nullable WandscapeNpc caster,
                                 float damage, double radius, long fireTick) {}
    private static final List<PendingMeteor> PENDING_METEORS = new ArrayList<>();

    // ── 感化 ──
    // 受感化影响的生物 UUID → 到期 tick，用于 ServerTick 每 0.5s 重定向攻击目标。
    // 由 MagicSpellExecutors 在施加效果时写入。
    private record ConversionEntry(UUID entityId, ResourceKey<Level> dimension, long expireTick) {}
    private static final Map<UUID, ConversionEntry> CONVERSIONS = new HashMap<>();

    public static synchronized void addHealAura(HealAura aura) {
        HEAL_AURAS.add(aura);
    }

    public static synchronized void addMeteorTracker(MeteorTracker tracker) {
        METEORS.add(tracker);
    }

    /** 登记一颗延迟落下的陨石：在 fireTick 时于 pos 处生成 1 颗（meteor 连落 6 颗由施法方按 1/6 持续时长间隔调用）。 */
    public static synchronized void addPendingMeteor(ServerLevel level, Vec3 pos,
                                                     @Nullable WandscapeNpc caster,
                                                     float damage, double radius, long fireTick) {
        PENDING_METEORS.add(new PendingMeteor(level, pos, caster, damage, radius, fireTick));
    }

    /** 到期（gameTime ≥ fireTick）的延迟陨石生成 FallingBlockEntity，转交 MeteorTracker 落地结算。 */
    private static synchronized void tickPendingMeteors() {
        if (PENDING_METEORS.isEmpty()) return;
        Iterator<PendingMeteor> it = PENDING_METEORS.iterator();
        while (it.hasNext()) {
            PendingMeteor pm = it.next();
            if (pm.level().getGameTime() >= pm.fireTick()) {
                MagicSpellExecutors.spawnMeteorsAt(pm.level(), pm.caster(), pm.pos(), 1, pm.damage(), pm.radius());
                it.remove();
            }
        }
    }

    /** 注册陨石落地冲击波：一圈一圈向外扩散的红色发光环。 */
    public static synchronized void addShockwave(ServerLevel level, Vec3 center, double maxRadius) {
        SHOCKWAVES.add(new Shockwave(level, center, maxRadius, SHOCKWAVE_TICKS, SHOCKWAVE_TICKS));
    }

    /** 注册一个被感化的实体（由 MagicSpellExecutors 在施放时调用）。 */
    public static synchronized void addConversion(LivingEntity entity, int durationTicks) {
        CONVERSIONS.put(entity.getUUID(),
                new ConversionEntry(entity.getUUID(), entity.level().dimension(),
                        entity.level().getGameTime() + durationTicks));
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        tickPendingMeteors();
        tickHealAuras();
        tickMeteors();
        tickShockwaves();
        tickConversions();
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

            // 撞击条件：触底、接近目标 Y，或已离开出生点开始下落后停滞（防悬浮在半空卡住）。
            // 刚生成的陨石 deltaMovement 为 0，不能算落地——必须已落下离开出生点才启用停滞检查，
            // 否则 NPC 施法（与 tickMeteors 同在 ServerTickEvent.Post 触发）会让陨石在出生点半空瞬爆。
            boolean falling = entity.getY() < meteor.spawnY() - 0.5;
            boolean landed = entity.onGround()
                    || entity.getY() <= meteor.targetY() + 0.5
                    || (falling && entity.getDeltaMovement().lengthSqr() < 0.001);

            if (landed) {
                Vec3 impactPos = entity.position();
                BlockPos bpos = entity.blockPosition();

                // 破碎粒子与音效
                level.levelEvent(2001, bpos, Block.getId(Blocks.MAGMA_BLOCK.defaultBlockState()));
                level.playSound(null, impactPos.x, impactPos.y, impactPos.z,
                        SoundEvents.GENERIC_EXPLODE, SoundSource.NEUTRAL, 1.0f, 1.2f);

                // 对半径 4 内生物造成魔法伤害。溅射伤害沿用光束的伤害边界（canBeamHurt）：
                // 默认只伤敌对生物（Enemy）——普通 NPC 的陨石不会伤到友方 NPC / 村民 / 玩家；
                // 邪恶法师按 canBeamHurt 判定（额外伤生存玩家）。和平模式 NPC 的已落地陨石不结算。
                AABB area = new AABB(impactPos.x - meteor.radius(), impactPos.y - 2, impactPos.z - meteor.radius(),
                                     impactPos.x + meteor.radius(), impactPos.y + 3, impactPos.z + meteor.radius());

                List<LivingEntity> targets = level.getEntitiesOfClass(LivingEntity.class, area, e -> {
                    if (!e.isAlive()) return false;
                    if (e instanceof Enemy) return true;
                    WandscapeNpc caster = meteor.caster();
                    return caster != null && !caster.isRemoved() && !caster.isPeaceMode() && caster.canBeamHurt(e);
                });
                for (LivingEntity target : targets) {
                    // 同目标多颗陨石叠伤（保底集中砸）：重置无敌帧保证每颗都结算，否则后落的被伤害免疫吞掉
                    target.invulnerableTime = 0;
                    target.hurt(level.damageSources().indirectMagic(meteor.caster(), meteor.caster()), meteor.damage());
                }

                // 移除陨石实体，不上方块
                entity.discard();
                it.remove();

                // 发光冲击波：一圈圈向外扩散的红色发光环
                addShockwave(level, impactPos, meteor.radius());
            }
        }
    }

    // ── 陨石冲击波：一圈一圈向外扩散的红色发光冲击环 ──

    private static final int SHOCKWAVE_TICKS = 12; // 扩散持续时间（tick）
    private static final ParticleOptions METEOR_SHOCKWAVE_PARTICLE =
            new DustParticleOptions(new Vector3f(1.0f, 0.2f, 0.1f), 1.0f);

    private static synchronized void tickShockwaves() {
        if (SHOCKWAVES.isEmpty()) return;
        for (int i = 0; i < SHOCKWAVES.size(); i++) {
            Shockwave sw = SHOCKWAVES.get(i);
            double progress = 1.0 - (double) sw.remaining() / sw.totalTicks();
            spawnShockwaveRing(sw.level(), sw.center(), sw.maxRadius() * progress);
            if (sw.remaining() <= 1) {
                SHOCKWAVES.remove(i);
                i--;
            } else {
                SHOCKWAVES.set(i, new Shockwave(sw.level(), sw.center(), sw.maxRadius(),
                        sw.totalTicks(), sw.remaining() - 1));
            }
        }
    }

    /** 在半径 radius 处生成一圈红色发光粒子；半径增大时加密粒子，首帧附加中心上升爆闪。 */
    private static void spawnShockwaveRing(ServerLevel level, Vec3 center, double radius) {
        double y = center.y + 0.15;
        int count = Math.max(16, (int) Math.round(radius * 8.0));
        for (int i = 0; i < count; i++) {
            double angle = 2.0 * Math.PI * i / count;
            double x = center.x + Math.cos(angle) * radius;
            double z = center.z + Math.sin(angle) * radius;
            level.sendParticles(METEOR_SHOCKWAVE_PARTICLE, x, y, z, 1, 0.15, 0.0, 0.15, 0.0);
        }
        if (radius < 0.4) {
            level.sendParticles(METEOR_SHOCKWAVE_PARTICLE, center.x, center.y + 0.3, center.z,
                    16, 0.5, 0.8, 0.5, 0.06);
        }
    }

    // ── 感化：每 0.5s 重定向受感化生物的攻击目标 ──

    /** 感化目标搜索半径（方块）。 */
    private static final double CONVERSION_SEARCH_RANGE = 16.0;
    /** 感化处理间隔（tick）。 */
    private static final int CONVERSION_TICK_INTERVAL = 10;

    private static synchronized void tickConversions() {
        if (CONVERSIONS.isEmpty()) return;

        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        long now = server.getTickCount();

        Iterator<Map.Entry<UUID, ConversionEntry>> it = CONVERSIONS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, ConversionEntry> entry = it.next();
            ConversionEntry conv = entry.getValue();

            if (now >= conv.expireTick()) {
                it.remove();
                continue;
            }

            // 每 10 tick (0.5s) 处理一次
            if (now % CONVERSION_TICK_INTERVAL != 0) continue;

            ServerLevel level = server.getLevel(conv.dimension());
            if (level == null) continue;

            Entity entity = level.getEntity(conv.entityId());
            if (!(entity instanceof Mob mob && mob.isAlive()
                    && mob.hasEffect(WandscapeEffects.CONVERSION))) {
                it.remove();
                continue;
            }

            // 已有有效敌对目标（Enemy 且非自身）则保留，否则重选
            LivingEntity currentTarget = mob.getTarget();
            if (currentTarget != null && currentTarget.isAlive()
                    && currentTarget instanceof Enemy
                    && currentTarget != mob) {
                continue;
            }

            // 搜索附近最近的 Enemy
            AABB box = mob.getBoundingBox().inflate(CONVERSION_SEARCH_RANGE);
            List<Mob> enemies = level.getEntitiesOfClass(Mob.class, box,
                    e -> e instanceof Enemy && e.isAlive() && e != mob);
            if (!enemies.isEmpty()) {
                Mob nearest = enemies.get(0);
                double nearestDist = mob.distanceToSqr(nearest);
                for (int i = 1; i < enemies.size(); i++) {
                    double d = mob.distanceToSqr(enemies.get(i));
                    if (d < nearestDist) {
                        nearest = enemies.get(i);
                        nearestDist = d;
                    }
                }
                mob.setTarget(nearest);
            }
        }
    }

    /**
     * 石化 Buff 减伤逻辑：受到伤害 -2，低于 2 彻底无视（归 0）。
     * 护甲削减：有效护甲 = 当前护甲 − shredAmount，可负，负值 = 增伤。
     * 背水：有效护甲 = −当前护甲/2（反转减伤为增伤）。
     */
    @SubscribeEvent
    public static void onLivingDamage(LivingDamageEvent.Pre event) {
        LivingEntity entity = event.getEntity();
        if (entity == null) return;

        // ── 石化减伤 ──
        if (entity.hasEffect(WandscapeEffects.PETRIFICATION)) {
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

        // ── 护甲削减（可负 = 增伤） ──
        if (entity.hasEffect(WandscapeEffects.ARMOR_SHRED)) {
            int amplifier = entity.getEffect(WandscapeEffects.ARMOR_SHRED).getAmplifier();
            float shredAmount = 4.0f * (amplifier + 1);       // 每级 -4 护甲
            float armor = entity.getArmorValue();              // 当前护甲（0-30）
            float effectiveArmor = armor - shredAmount;        // 可负

            // vanilla 会在 Pre 之后计算：final = raw × (1 − clamp(armor, 0, 20) / 25)
            // 我们要：            final = raw × (1 − clamp(effectiveArmor, −shredAmount, 20) / 25)
            // → 调整 Pre 值使 vanilla 处理后等于目标值
            float vanillaMultiplier = 1.0f - Math.min(20.0f, Math.max(0.0f, armor)) / 25.0f;
            float desiredMultiplier = 1.0f
                    - Math.min(20.0f, Math.max(-shredAmount, effectiveArmor)) / 25.0f;

            if (vanillaMultiplier > 0.001f) {
                float raw = event.getNewDamage();
                event.setNewDamage(raw * (desiredMultiplier / vanillaMultiplier));
            }
        }

        // ── 背水：有效护甲 = −当前护甲/2（护甲反转 → 护甲越高受伤越重） ──
        if (entity.hasEffect(WandscapeEffects.DESPERATION)) {
            float armor = entity.getArmorValue();              // 当前护甲（0-30）
            float effectiveArmor = -armor * 0.5f;              // 反转：护甲10 → −5
            float worst = -armor * 0.5f;                       // 下界 = 反转值（最负）

            float vanillaMultiplier = 1.0f - Math.min(20.0f, Math.max(0.0f, armor)) / 25.0f;
            float desiredMultiplier = 1.0f
                    - Math.min(20.0f, Math.max(worst, effectiveArmor)) / 25.0f;

            if (vanillaMultiplier > 0.001f) {
                float raw = event.getNewDamage();
                event.setNewDamage(raw * (desiredMultiplier / vanillaMultiplier));
            }
        }
    }
}
