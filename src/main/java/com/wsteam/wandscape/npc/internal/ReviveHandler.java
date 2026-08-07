package com.wsteam.wandscape.npc.internal;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.core.component.ColonyMember;
import com.wsteam.wandscape.core.component.EquipmentComponent;
import com.wsteam.wandscape.core.component.Inventory;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.types.NpcAttributes;
import com.wsteam.wandscape.core.types.ResourceStack;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.engine.service.SoundService;
import com.wsteam.wandscape.engine.sound.WandscapeSounds;
import com.wsteam.wandscape.magic.data.MagicCircleSpec;
import com.wsteam.wandscape.magic.data.MagicDef;
import com.wsteam.wandscape.magic.internal.MagicCircleLoader;
import com.wsteam.wandscape.magic.internal.SpellbookLoader;
import com.wsteam.wandscape.npc.data.DeathRecord;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.network.MagicCircleCastPacket;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 复活魔法：shift+右键 NPC 施放，目标 = 施法者附近最近的死亡留存记录（dead_ally）。
 * 门控（互斥锁 + revive 独立 CD + 魔力）走 {@code npc.tryCastSpell}；成功后死亡点生成
 * 法阵（时长即引导时长），引导结束生成新 WandscapeNpc 并恢复身份/外观/属性/装备/背包。
 *
 * <p>第一版为玩家指挥式（不自动进 CastBrain 战斗决策表——避免战斗中弃敌救人）；
 * 复活失败（生成位置无地可放等）保留死亡记录，可重试。
 */
public final class ReviveHandler {

    private static final String TAG = "Revive";

    /** revive 魔法 id（magic_spells/revive.json 的 key）。 */
    public static final String REVIVE_MAGIC_ID = "revive";
    /** revive.json 缺失或未配 range 时的搜索半径兜底（方块）。 */
    private static final double FALLBACK_RANGE = 32.0;
    /** 引导时长兜底（tick）：法阵 spec 缺失时用。 */
    private static final int FALLBACK_CHANNEL_TICKS = 100;

    private record PendingRevive(ServerLevel level, DeathRecord record, long fireTick) {}

    private static final List<PendingRevive> PENDING = new ArrayList<>();
    private static long lastPruneTick = 0;

    private ReviveHandler() {}

    /**
     * 玩家 shift+右键 NPC 时施放复活魔法。
     *
     * @return 是否成功施放（失败原因已发给玩家）
     */
    public static boolean castRevive(ServerLevel level, WandscapeNpc npc, ServerPlayer player) {
        MagicDef def = SpellbookLoader.getSpec(REVIVE_MAGIC_ID);
        if (def == null) {
            player.displayClientMessage(
                    Component.literal("[Wandscape] 复活魔法未配置（magic_spells/revive.json 缺失）"), false);
            return false;
        }

        double range = def.range() > 0 ? def.range() : FALLBACK_RANGE;
        DeathRecord rec = ColonyDeathRegistry.get(level).nearest(npc.blockPosition(), range);
        if (rec == null) {
            player.displayClientMessage(
                    Component.literal("[Wandscape] " + Math.round(range) + " 格内没有可复活的死者"), false);
            return false;
        }

        int channel = channelTicks(def);
        if (!npc.tryCastSpell(REVIVE_MAGIC_ID, def.baseCooldown(), def.manaCost(), channel)) {
            player.displayClientMessage(
                    Component.literal("[Wandscape] 施法被拒（施法锁/CD/魔力不足）"), false);
            return false;
        }

        BlockPos deathPos = new BlockPos(rec.x(), rec.y(), rec.z());
        npc.faceTarget(deathPos);
        npc.startManualCast(channel);
        sendReviveCircle(level, deathPos, def);
        PENDING.add(new PendingRevive(level, rec, level.getGameTime() + channel));
        SoundService.playAt(level, npc.getX(), npc.getY(), npc.getZ(),
                WandscapeSounds.MAGIC_CAST, SoundSource.NEUTRAL, 0.5f, 1.0f);
        Log.info(TAG, "castRevive npc={} target={} ({}) at {},{},{} channel={}",
                npc.getUUID().toString().substring(0, 8),
                rec.name(), rec.npcId().toString().substring(0, 8),
                rec.x(), rec.y(), rec.z(), channel);
        return true;
    }

    /** ServerTick 驱动：到期生成 NPC；每日清理过期记录。 */
    public static void tick(ServerLevel level) {
        long now = level.getGameTime();
        if (now - lastPruneTick >= 24000L) {
            lastPruneTick = now;
            ColonyDeathRegistry.get(level).prune(now);
        }

        if (PENDING.isEmpty()) return;
        Iterator<PendingRevive> it = PENDING.iterator();
        while (it.hasNext()) {
            PendingRevive p = it.next();
            if (now >= p.fireTick()) {
                spawnNpcFromRecord(p.level(), p.record());
                it.remove();
            }
        }
    }

    /** 引导时长 = 复活法阵 spec 时长（法阵完整展开后完成），spec 缺失回退常量。 */
    private static int channelTicks(MagicDef def) {
        String circleId = def.effectCircleId();
        if (circleId != null) {
            MagicCircleSpec spec = MagicCircleLoader.getSpec(circleId);
            if (spec != null) return spec.durationTicks;
        }
        return FALLBACK_CHANNEL_TICKS;
    }

    /** 死亡点生成地面法阵（MagicCircleCastPacket → 客户端渲染，与光束共用链路）。 */
    private static void sendReviveCircle(ServerLevel level, BlockPos deathPos, MagicDef def) {
        String circleId = def.effectCircleId() != null ? def.effectCircleId() : "arcane_hexagram";
        MagicCircleSpec spec = MagicCircleLoader.getSpec(circleId);
        if (spec == null) {
            Log.warn(TAG, "revive circle '{}' not found — no circle", circleId);
            return;
        }
        Vec3 axis = new Vec3(0, 1, 0);
        Vec3 origin = new Vec3(deathPos.getX() + 0.5, deathPos.getY() + 0.5 + spec.height, deathPos.getZ() + 0.5);
        PacketDistributor.sendToPlayersTrackingChunk(level, new ChunkPos(deathPos),
                new MagicCircleCastPacket(UUID.randomUUID(), origin, axis, circleId));
    }

    /** 引导完成：在死亡点附近生成新 NPC，恢复死亡快照，删除记录。 */
    private static void spawnNpcFromRecord(ServerLevel level, DeathRecord rec) {
        BlockPos spawnPos = findSpawnPos(level, new BlockPos(rec.x(), rec.y(), rec.z()));
        WandscapeNpc npc = Wandscape.WANDSCAPE_NPC.get().spawn(level, spawnPos, MobSpawnType.COMMAND);
        if (npc == null) {
            Log.warn(TAG, "复活失败：无法在 {} 生成 NPC（记录保留，可重试）", spawnPos.toShortString());
            return;
        }
        npc.setPersistenceRequired();
        npc.colonyId = rec.colonyId();
        npc.setCustomName(Component.literal(rec.name()));
        npc.setCustomNameVisible(true);
        npc.setSkinVariant(rec.skinVariant());
        npc.setHatColor(rec.hatColor());
        npc.maxHp = rec.maxHp();
        npc.moveSpeed = rec.moveSpeed();
        npc.spellPower = rec.spellPower();
        npc.workSpeed = rec.workSpeed();
        npc.spellSpeed = rec.spellSpeed();
        npc.armorValue = rec.armorValue();
        npc.maxMana = rec.maxMana();
        npc.magic.setMana(rec.maxMana()); // 满蓝复活
        npc.setHasDefaultWand(rec.hasDefaultWand());

        fixEcsAfterSpawn(npc, rec);
        ColonyDeathRegistry.get(level).remove(rec);

        spawnPortalBurst(level, spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5);
        Log.info(TAG, "NPC {} ({}) 已复活 at {}（恢复 {} 格背包）",
                npc.getUUID().toString().substring(0, 8), rec.name(),
                spawnPos.toShortString(), rec.inventory().size());
    }

    /** spawn() 已用默认属性注册 ECS——这里按死亡快照重新 seed 属性/殖民地/装备/背包（TavernRecruit 同款修正）。 */
    private static void fixEcsAfterSpawn(WandscapeNpc npc, DeathRecord rec) {
        World ecsWorld = WandscapeEngine.getWorld();
        if (ecsWorld == null) return;
        Long ecsId = EntityComponentBridge.INSTANCE.getEcsId(npc.getUUID());
        if (ecsId == null) return;

        EquipmentComponent eq = ecsWorld.get(ecsId, EquipmentComponent.class);
        if (eq != null) {
            eq.seedBaseValues(new NpcAttributes(npc.maxHp, npc.moveSpeed, npc.spellPower,
                    npc.workSpeed, npc.spellSpeed, npc.armorValue, npc.maxMana));
            if (rec.hasDefaultWand()) {
                eq.equipDefaultWand();
            }
        }

        var member = ecsWorld.get(ecsId, ColonyMember.class);
        if (member != null && !rec.colonyId().equals(member.colonyId())) {
            ecsWorld.addComponent(ecsId, new ColonyMember(rec.colonyId()));
        }

        Inventory inv = ecsWorld.get(ecsId, Inventory.class);
        if (inv != null) {
            for (ResourceStack s : rec.inventory()) {
                inv.add(s);
            }
        }
    }

    /** 死亡点附近找可站位置：优先死亡点上方的空气，其次周围方块。 */
    private static BlockPos findSpawnPos(ServerLevel level, BlockPos deathPos) {
        for (int dy = 0; dy < 4; dy++) {
            BlockPos check = deathPos.above(dy);
            if (level.isEmptyBlock(check) && !level.isEmptyBlock(check.below())) {
                return check;
            }
        }
        BlockPos[] candidates = {
                deathPos.offset(1, 0, 0), deathPos.offset(-1, 0, 0),
                deathPos.offset(0, 0, 1), deathPos.offset(0, 0, -1),
        };
        for (BlockPos pos : candidates) {
            if (level.isEmptyBlock(pos) && !level.isEmptyBlock(pos.below())) {
                return pos;
            }
        }
        return deathPos.above(2);
    }

    /** 末影人式 PORTAL 爆点（环绕身体，16 粒）。 */
    private static void spawnPortalBurst(ServerLevel level, double x, double y, double z) {
        for (int i = 0; i < 16; i++) {
            double ox = (level.random.nextDouble() - 0.5) * 1.0;
            double oy = level.random.nextDouble() * 2.0;
            double oz = (level.random.nextDouble() - 0.5) * 1.0;
            level.addParticle(ParticleTypes.PORTAL, x + ox, y + oy, z + oz, 0, 0, 0);
        }
    }
}
