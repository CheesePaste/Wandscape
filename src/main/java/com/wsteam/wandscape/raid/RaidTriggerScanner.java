package com.wsteam.wandscape.raid;

import java.util.UUID;

import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.guard.GuardZone;
import com.wsteam.wandscape.shared.data.BuildingData;
import com.wsteam.wandscape.shared.event.ColonyRaidStartedEvent;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.registry.WandscapeApis;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.dimension.DimensionType;
import net.neoforged.neoforge.common.NeoForge;

/**
 * 袭击触发扫描器：玩家携带不祥之兆（RAID_OMEN/BAD_OMEN）靠近任意非停摆建筑
 * {@code raid.triggerRange}(10) 格内 → 在市政厅中心创建原版袭击。
 *
 * <p>复用原版 {@link Raid} 全链路（波次/袭击者/Boss 条/号角/村庄英雄/持久化），
 * 这里只写触发：绕过原版 BadOmen→village 链路的村庄判定（见 MixinServerLevel），
 * 以任意建筑为触发源、市政厅为袭击中心。中心放在市政厅是刻意设计——触发点可能
 * 是殖民地边缘的建筑，但袭击围绕核心展开。
 */
public final class RaidTriggerScanner {
    public static final RaidTriggerScanner INSTANCE = new RaidTriggerScanner();

    private static final String TAG = "RaidTriggerScanner";
    private int tickCounter;

    public void tick(ServerLevel level) {
        if (++tickCounter < Config.RAID_CHECK_INTERVAL.get()) return;
        tickCounter = 0;
        scan(level);
    }

    private void scan(ServerLevel level) {
        if (level.getGameRules().getBoolean(GameRules.RULE_DISABLE_RAIDS)) return;
        DimensionType dimensionType = level.dimensionType();
        if (!dimensionType.hasRaids()) return;

        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) continue;
            if (!hasBadOmen(player)) continue;
            if (triggerForPlayer(level, player)) return;
        }
    }

    private boolean triggerForPlayer(ServerLevel level, ServerPlayer player) {
        BlockPos playerPos = player.blockPosition();
        for (UUID colonyId : WandscapeApis.getColonyApi().getAllColonyIds()) {
            BlockPos townHall = RaidTownHall.findTownHall(colonyId);
            if (townHall == null) continue;
            if (!isNearBuilding(colonyId, playerPos)) continue;

            int nearbyRadiusSq = Config.RAID_NEARBY_RADIUS.get() * Config.RAID_NEARBY_RADIUS.get();
            if (level.getRaids().getNearbyRaid(townHall, nearbyRadiusSq) != null) continue;

            ensureRaidOmen(player);
            Raid raid = level.getRaids().createOrExtendRaid(player, townHall);
            if (raid == null) continue;

            ColonyRaidTracker.INSTANCE.track(colonyId, raid);
            NeoForge.EVENT_BUS.post(new ColonyRaidStartedEvent(
                    colonyId, raid.getId(), townHall,
                    raid.getRaidOmenLevel(), raid.getNumGroups(level.getDifficulty())));
            Log.info(TAG, "[Raid] Colony {} raid started (id={}, omen={}, waves={}) at {}",
                    colonyId.toString().substring(0, 8), raid.getId(),
                    raid.getRaidOmenLevel(), raid.getNumGroups(level.getDifficulty()),
                    townHall.toShortString());
            return true;
        }
        return false;
    }

    /** 玩家是否位于殖民地任一非停摆、未损坏建筑 AABB 水平 ±triggerRange（Y 不扩展）内。 */
    private boolean isNearBuilding(UUID colonyId, BlockPos playerPos) {
        var buildingApi = WandscapeApis.getBuildingApi();
        for (BuildingData b : buildingApi.getColonyBuildings(colonyId)) {
            if (b.isShutdown() || !b.isStructureIntact()) continue;
            var bounds = buildingApi.getBuildingBounds(b.getBuildingId());
            if (GuardZone.of(bounds.minX(), bounds.minY(), bounds.minZ(),
                    bounds.maxX(), bounds.maxY(), bounds.maxZ(),
                    Config.RAID_TRIGGER_RANGE.get())
                    .contains(playerPos.getX(), playerPos.getY(), playerPos.getZ())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasBadOmen(ServerPlayer player) {
        return player.hasEffect(MobEffects.BAD_OMEN) || player.hasEffect(MobEffects.RAID_OMEN);
    }

    /** 玩家只有 BAD_OMEN 时补 RAID_OMEN，使 createOrExtendRaid 能吸收不祥之兆等级。 */
    private static void ensureRaidOmen(ServerPlayer player) {
        if (player.hasEffect(MobEffects.RAID_OMEN)) return;
        int amplifier = 0;
        MobEffectInstance bad = player.getEffect(MobEffects.BAD_OMEN);
        if (bad != null) amplifier = bad.getAmplifier();
        player.addEffect(new MobEffectInstance(MobEffects.RAID_OMEN, 600, amplifier));
    }
}
