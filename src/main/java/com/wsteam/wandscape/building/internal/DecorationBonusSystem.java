package com.wsteam.wandscape.building.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.building.data.BuildingConfig;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Periodically computes decoration radiation: decoration buildings radiate
 * their comfort/magic/wonder stats to nearby functional buildings within
 * Manhattan distance.
 *
 * <p>Bonus values are capped per building: at most
 * {@code building_base × Config.DECORATION_BONUS_CAP}.
 * Decoration buildings' own stats do not directly count toward colony totals.
 */
public final class DecorationBonusSystem {
    private static final String TAG = "DecorationBonusSystem";

    private int tickCounter;
    private final DecorationBonusCache cache = new DecorationBonusCache();

    private DecorationBonusSystem() {}

    /** Register with the NeoForge event bus. Returns the instance for cache access. */
    public static DecorationBonusSystem register() {
        var instance = new DecorationBonusSystem();
        NeoForge.EVENT_BUS.register(instance);
        return instance;
    }

    public DecorationBonusCache getCache() {
        return cache;
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        ServerLevel level = server.overworld();
        if (level == null) return;

        tickCounter++;
        int interval = Config.DECORATION_SCAN_INTERVAL_TICKS.get();
        if (tickCounter % interval != 0) return;

        BuildingSavedData savedData = BuildingSavedData.get(level);
        BuildingConfigLoader configLoader = BuildingConfigLoader.getInstance();

        // Separate buildings into sources (decoration) and targets (functional)
        List<BuildingState> sources = new ArrayList<>();
        List<BuildingState> targets = new ArrayList<>();

        for (BuildingState state : savedData.getAllBuildings()) {
            if (state.isShutdown()) continue;
            String category = state.getCategory();
            if ("decoration".equals(category)) {
                sources.add(state);
            } else if (!"wonder".equals(category)) {
                targets.add(state);
            }
        }

        if (sources.isEmpty()) {
            cache.clear();
            return;
        }

        // For each target, accumulate bonuses from all sources in range
        for (BuildingState target : targets) {
            BlockPos targetAnchor = target.getAnchor();
            BuildingConfig targetCfg = configLoader.get(target.getBuildingTypeId());
            if (targetCfg == null) continue;

            int accComfort = 0, accMagic = 0, accWonder = 0;

            for (BuildingState source : sources) {
                BlockPos sourceAnchor = source.getAnchor();
                BuildingConfig sourceCfg = configLoader.get(source.getBuildingTypeId());
                if (sourceCfg == null || sourceCfg.decoration() == null) continue;

                int radius = sourceCfg.decoration().radius();
                int dist = Math.abs(targetAnchor.getX() - sourceAnchor.getX())
                         + Math.abs(targetAnchor.getY() - sourceAnchor.getY())
                         + Math.abs(targetAnchor.getZ() - sourceAnchor.getZ());

                if (dist <= radius) {
                    accComfort += sourceCfg.comfort();
                    accMagic   += sourceCfg.magic();
                    accWonder  += sourceCfg.wonder();
                }
            }

            // Cap per stat: min(accumulated, base × cap)
            double cap = Config.DECORATION_BONUS_CAP.get();
            int bonusComfort = (int) Math.min(accComfort, targetCfg.comfort() * cap);
            int bonusMagic   = (int) Math.min(accMagic,   targetCfg.magic() * cap);
            int bonusWonder  = (int) Math.min(accWonder,  targetCfg.wonder() * cap);

            cache.update(target.getBuildingId(), bonusComfort, bonusMagic, bonusWonder);
        }

        Log.debug(TAG, "[Decoration] Scan complete: {} sources → {} targets",
                sources.size(), targets.size());
    }
}
