package com.wsteam.wandscape.building.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.engine.service.SoundService;
import com.wsteam.wandscape.engine.sound.WandscapeSounds;
import com.wsteam.wandscape.shared.data.WonderConfig;
import com.wsteam.wandscape.shared.data.WonderEffect;
import com.wsteam.wandscape.shared.event.BuildingRestartedEvent;
import com.wsteam.wandscape.shared.event.BuildingShutdownEvent;
import com.wsteam.wandscape.shared.event.ColonyEvaluationChangedEvent;
import com.wsteam.wandscape.shared.event.WonderEffectChangedEvent;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Collects and applies wonder building global effects.
 *
 * <p>Wonder effects are active when the building is intact and not shutdown.
 * Effects are aggregated across all wonders and cached for fast querying.
 *
 * <p>Supports three effect types:
 * <ul>
 *   <li>{@link WonderEffect.StatMod} — modifies a global numeric stat</li>
 *   <li>{@link WonderEffect.PriceMod} — modifies all shop prices</li>
 *   <li>{@link WonderEffect.RuleUnlock} — unlocks rule-level capabilities</li>
 * </ul>
 */
public final class WonderEffectApplier {
    private static final String TAG = "WonderEffectApplier";

    // Active effects indexed by buildingId
    private final Map<UUID, List<WonderEffect>> activeEffectsByBuilding = new ConcurrentHashMap<>();
    // Aggregated stat modifiers: target → total value
    private final Map<String, Integer> statCache = new ConcurrentHashMap<>();
    // Aggregated price modifiers: target → total percentage
    private final Map<String, Double> priceCache = new ConcurrentHashMap<>();
    // Unlocked rules
    private final Set<String> unlockedRules = ConcurrentHashMap.newKeySet();

    private WonderEffectApplier() {}

    /** Register with the NeoForge event bus. Returns the instance for querying. */
    public static WonderEffectApplier register() {
        var instance = new WonderEffectApplier();
        NeoForge.EVENT_BUS.register(instance);
        return instance;
    }

    // ── Event handlers ──

    @SubscribeEvent
    public void onBuildingShutdown(BuildingShutdownEvent event) {
        removeEffects(event.getBuildingId());
    }

    @SubscribeEvent
    public void onBuildingRestarted(BuildingRestartedEvent event) {
        recalculateForBuilding(event.getBuildingId());
    }

    @SubscribeEvent
    public void onColonyEvaluationChanged(ColonyEvaluationChangedEvent event) {
        // Recalculate all wonders in the affected colony
        recalculateAll();
    }

    // ── Public query API ──

    /** Returns the total stat modifier for a given target across all active wonders. */
    public int getStatMod(String target) {
        return statCache.getOrDefault(target, 0);
    }

    /** Returns the total price modifier percentage for a given target. */
    public double getPriceMod(String target) {
        return priceCache.getOrDefault(target, 0.0);
    }

    /** Returns whether a rule is currently unlocked by any active wonder. */
    public boolean isRuleUnlocked(String ruleId) {
        return unlockedRules.contains(ruleId);
    }

    /** Returns an immutable copy of all unlocked rule IDs. */
    public Set<String> getUnlockedRules() {
        return Set.copyOf(unlockedRules);
    }

    /** Returns active effects for a specific wonder building. */
    public List<WonderEffect> getActiveEffects(UUID buildingId) {
        return activeEffectsByBuilding.getOrDefault(buildingId, List.of());
    }

    // ── Internal ──

    private void recalculateAll() {
        ServerLevel level = getServerLevel();
        if (level == null) return;

        BuildingSavedData savedData = BuildingSavedData.get(level);
        BuildingConfigLoader configLoader = BuildingConfigLoader.getInstance();

        statCache.clear();
        priceCache.clear();
        unlockedRules.clear();
        activeEffectsByBuilding.clear();

        for (BuildingState state : savedData.getAllBuildings()) {
            if (!"wonder".equals(state.getCategory())) continue;
            if (state.isShutdown() || !state.isStructureIntact()) continue;

            BuildingConfig config = configLoader.get(state.getBuildingTypeId());
            if (config == null || config.wonderConfig() == null) continue;

            WonderConfig wonderConfig = config.wonderConfig();
            if (wonderConfig.effects().isEmpty()) continue;

            activeEffectsByBuilding.put(state.getBuildingId(),
                    List.copyOf(wonderConfig.effects()));
            applyEffects(wonderConfig.effects(), state.getBuildingId(), state.getColonyId());
        }

        Log.info(TAG, "[Wonder] Recalculated: {} wonders active, stats={} prices={} rules={}",
                activeEffectsByBuilding.size(), statCache, priceCache, unlockedRules);
    }

    private void recalculateForBuilding(UUID buildingId) {
        // Full recalc is simpler and wonder count is small
        recalculateAll();
    }

    private void removeEffects(UUID buildingId) {
        List<WonderEffect> removed = activeEffectsByBuilding.remove(buildingId);
        if (removed != null) {
            // Fire event then full recalc to rebuild caches
            ServerLevel level = getServerLevel();
            UUID colonyId = null;
            if (level != null) {
                BuildingSavedData sd = BuildingSavedData.get(level);
                if (sd != null) {
                    BuildingState state = sd.getBuilding(buildingId);
                    if (state != null) colonyId = state.getColonyId();
                }
            }
            NeoForge.EVENT_BUS.post(new WonderEffectChangedEvent(
                    buildingId, colonyId, List.of(), false));
            playWonderSound(buildingId, false);
            recalculateAll();
            Log.info(TAG, "[Wonder] Effects removed for building={}",
                    buildingId.toString().substring(0, 8));
        }
    }

    private void applyEffects(List<WonderEffect> effects, UUID buildingId, UUID colonyId) {
        boolean anyApplied = false;
        for (WonderEffect effect : effects) {
            switch (effect) {
                case WonderEffect.StatMod statMod -> {
                    statCache.merge(statMod.target(), statMod.value(), Integer::sum);
                    anyApplied = true;
                }
                case WonderEffect.PriceMod priceMod -> {
                    priceCache.merge(priceMod.target(), priceMod.percentage(), Double::sum);
                    anyApplied = true;
                }
                case WonderEffect.RuleUnlock ruleUnlock -> {
                    unlockedRules.add(ruleUnlock.ruleId());
                    anyApplied = true;
                }
            }
        }
        if (anyApplied) {
            NeoForge.EVENT_BUS.post(new WonderEffectChangedEvent(
                    buildingId, colonyId, effects, true));
            playWonderSound(buildingId, true);
        }
    }

    /** 奇观生效/移除音：按建筑锚点播放。 */
    private static void playWonderSound(UUID buildingId, boolean active) {
        ServerLevel level = getServerLevel();
        if (level == null) return;
        BuildingSavedData sd = BuildingSavedData.get(level);
        if (sd == null) return;
        BuildingState state = sd.getBuilding(buildingId);
        if (state == null) return;
        SoundService.playAt(level, state.getAnchor(), WandscapeSounds.WONDER_EFFECT,
                SoundSource.BLOCKS, active ? 0.7f : 0.5f, 1.0f);
    }

    private static ServerLevel getServerLevel() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server != null ? server.overworld() : null;
    }
}
