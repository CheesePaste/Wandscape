package com.wsteam.wandscape.content.building.internal;
import com.wsteam.wandscape.content.colony.ColonyActivation;
import com.wsteam.wandscape.content.task.ecs.System;
import com.wsteam.wandscape.foundation.util.TickProfiler;

import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.content.colony.stats.internal.StatisticsCollector;
import com.wsteam.wandscape.content.tourist.event.DailySettlementEvent;
import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Daily settlement boundary. Fires once per Minecraft day at time-of-day 0
 * (sunrise), posting a {@link DailySettlementEvent} per colony.
 *
 * <p>Periodic systems subscribe to the event: shop daily restock
 * ({@link ShopStockManager}), colony statistics snapshots
 * ({@link StatisticsCollector}).
 */
public final class DailySettlementSystem {
    private static final String TAG = "DailySettlementSystem";

    private long settledDay = -1;

    private DailySettlementSystem() {}

    public static DailySettlementSystem register() {
        DailySettlementSystem system = new DailySettlementSystem();
        NeoForge.EVENT_BUS.register(system);
        Log.info(TAG, "DailySettlementSystem registered");
        return system;
    }

    // ── Tick handler ──

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        try (var span = com.wsteam.wandscape.foundation.util.TickProfiler.INSTANCE.start("building.settlement.on_server_tick")) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        ServerLevel level = server.overworld();
        if (level == null) return;

        long dayTime = level.getDayTime();
        long currentDay = dayTime / 24000;

        // Already settled this day
        if (currentDay == settledDay) return;

        // Wait until we're within the settlement window (time-of-day near 0)
        int tod = (int) (dayTime % 24000);
        int window = Config.SETTLEMENT_WINDOW_TICKS.get();
        if (tod > window) return;

        settledDay = currentDay;
        fireSettlement(level, currentDay);
        }
    }

    // ── Settlement fire ──

    private void fireSettlement(ServerLevel level, long day) {
        BuildingSavedData savedData = BuildingSavedData.get(level);
        if (savedData == null) return;

        // Distinct colonies with at least one building
        Set<UUID> colonyIds = new HashSet<>();
        for (BuildingState state : savedData.getAllBuildings()) {
            if (state.getColonyId() != null) colonyIds.add(state.getColonyId());
        }
        if (colonyIds.isEmpty()) return;

        int fired = 0;
        for (UUID colonyId : colonyIds) {
            // 创始人不在线且关闭离线运行 → 冻结小镇：跳过当日结算（商店补货/统计等）
            if (!com.wsteam.wandscape.content.colony.ColonyActivation.isColonyActive(colonyId)) {
                continue;
            }
            NeoForge.EVENT_BUS.post(new DailySettlementEvent(
                    new DailySettlementEvent.SettlementReport(colonyId, day)));
            fired++;
        }
        Log.info(TAG, "[Settlement] Day {} fired for {}/{} colony(ies)", day, fired, colonyIds.size());
    }
}
