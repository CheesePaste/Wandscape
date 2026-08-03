package com.wsteam.wandscape.raid;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import com.wsteam.wandscape.shared.event.ColonyRaidVictoryEvent;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.registry.WandscapeApis;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.raid.Raid;
import net.neoforged.neoforge.common.NeoForge;

/**
 * 殖民地↔袭击跟踪：轮询 {@link Raid#isVictory()}，胜利时广播
 * {@link ColonyRaidVictoryEvent}。
 *
 * <p>袭击本体持久化由原版 {@code Raids} SavedData 负责（存档/重进自动恢复）；
 * 这里只做事件侧跟踪。服务器重载后内存表为空，每 {@code RELINK_INTERVAL_TICKS}
 * 用 {@link #relink} 把进行中的袭击重新挂回，保证重载后仍能收到胜利事件。
 */
public final class ColonyRaidTracker {
    public static final ColonyRaidTracker INSTANCE = new ColonyRaidTracker();

    private static final String TAG = "ColonyRaidTracker";
    private static final int RELINK_INTERVAL_TICKS = 200;

    private static final class TrackedRaid {
        final int raidId;
        boolean victoryNotified;
        TrackedRaid(int raidId) { this.raidId = raidId; }
    }

    private final Map<UUID, TrackedRaid> tracked = new HashMap<>();
    private int tickCounter;

    private ColonyRaidTracker() {}

    public void track(UUID colonyId, Raid raid) {
        tracked.put(colonyId, new TrackedRaid(raid.getId()));
    }

    public void tick(ServerLevel level) {
        if (++tickCounter % RELINK_INTERVAL_TICKS == 0) relink(level);

        Iterator<Map.Entry<UUID, TrackedRaid>> it = tracked.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, TrackedRaid> entry = it.next();
            TrackedRaid tr = entry.getValue();
            Raid raid = level.getRaids().get(tr.raidId);
            if (raid == null || !raid.isActive()) {
                it.remove();
                continue;
            }
            if (raid.isVictory() && !tr.victoryNotified) {
                tr.victoryNotified = true;
                NeoForge.EVENT_BUS.post(new ColonyRaidVictoryEvent(
                        entry.getKey(), tr.raidId, raid.getCenter(),
                        raid.getRaidOmenLevel(), raid.getGroupsSpawned()));
                Log.info(TAG, "[Raid] Colony {} raid won (id={}, omen={}, waves={})",
                        entry.getKey().toString().substring(0, 8), tr.raidId,
                        raid.getRaidOmenLevel(), raid.getGroupsSpawned());
            }
            if (raid.isOver()) {
                it.remove();
            }
        }
    }

    /** 服务器重载后把进行中的袭击重新挂回跟踪。 */
    public void relink(ServerLevel level) {
        for (UUID colonyId : WandscapeApis.getColonyApi().getAllColonyIds()) {
            if (tracked.containsKey(colonyId)) continue;
            BlockPos townHall = RaidTownHall.findTownHall(colonyId);
            if (townHall == null) continue;
            Raid raid = level.getRaids().getNearbyRaid(townHall, Raid.RAID_REMOVAL_THRESHOLD_SQR);
            if (raid != null && raid.isActive()) {
                track(colonyId, raid);
                Log.info(TAG, "[Raid] Relinked colony {} to active raid #{}",
                        colonyId.toString().substring(0, 8), raid.getId());
            }
        }
    }
}
