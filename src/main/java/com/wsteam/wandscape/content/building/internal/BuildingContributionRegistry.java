package com.wsteam.wandscape.content.building.internal;

import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.content.building.data.BuildingConfig;
import com.wsteam.wandscape.content.colony.event.ColonyEvaluationChangedEvent;
import com.wsteam.wandscape.foundation.log.Log;
import net.neoforged.bus.api.IEventBus;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks, per colony, how many <em>intact</em> buildings exist for each building type.
 *
 * <p>Each intact building instance contributes its {@link BuildingConfig} comfort / magic / wonder
 * values to the colony. Multiple buildings of the same type stack.
 *
 * <p>Owned and called from {@link BuildingSavedData}.  All mutations must go through
 * {@link #recordIntactChange} so that the evaluation change event is fired exactly
 * once per transition.
 */
public final class BuildingContributionRegistry {
    private static final String TAG = "BuildingContributionRegistry";

    /**
     * ColonyId → (buildingTypeId → number of currently intact buildings of this type).
     * Only buildings with {@code structureIntact = true} are counted here.
     */
    private final Map<UUID, Map<String, Integer>> intactCounts = new ConcurrentHashMap<>();

    @Nullable
    private final IEventBus eventBus;

    @Nullable
    private volatile DecorationBonusCache decorationBonusCache;

    @Nullable
    private volatile BuildSource buildSource;

    BuildingContributionRegistry(@Nullable IEventBus eventBus) {
        this.eventBus = eventBus;
    }

    /** Set the decoration bonus cache for merging radiation into colony totals. */
    public void setDecorationBonusCache(@Nullable DecorationBonusCache cache) {
        this.decorationBonusCache = cache;
    }

    /** Store the build source for per-building bonus queries in getSnapshot. */
    public void setBuildSource(@Nullable BuildSource source) {
        this.buildSource = source;
    }

    /** Per-buildingId stock state. */
    private final Map<UUID, Boolean> shopHasStock = new ConcurrentHashMap<>();
    /** buildingTypeId → count of shop buildings of this type that currently have stock. */
    private final Map<String, Integer> shopTypesWithStock = new ConcurrentHashMap<>();

    /**
     * Called by {@link ShopStockManager} when a shop's stock state changes.
     * Shop types with at least one stocked building contribute normally;
     * types with no stocked buildings have zero contribution.
     */
    public void setShopHasStock(UUID buildingId, String buildingTypeId,
                                UUID colonyId, boolean hasStock) {
        Boolean prev = shopHasStock.put(buildingId, hasStock);
        boolean wasStocked = Boolean.TRUE.equals(prev);
        if (wasStocked != hasStock) {
            if (hasStock) {
                shopTypesWithStock.merge(buildingTypeId, 1, Integer::sum);
            } else {
                shopTypesWithStock.compute(buildingTypeId,
                        (k, v) -> v == null || v <= 1 ? null : v - 1);
            }
        }
    }

    // ── Mutation (called from BuildingSavedData) ──────────────────────────────

    /**
     * Called when a building transitions <em>to</em> intact (e.g. build complete or repair finished).
     *
     * @return {@code true} if this transition caused the type's count to go from 0 → 1
     *         (i.e. the colony's evaluation values actually changed).
     */
    boolean recordIntactChange(UUID colonyId, String buildingTypeId, boolean nowIntact) {
        if (colonyId == null || buildingTypeId == null) return false;

        ColonySnapshot before = getSnapshot(colonyId);

        Map<String, Integer> typeMap = intactCounts.computeIfAbsent(colonyId, k -> new ConcurrentHashMap<>());
        int prev = typeMap.getOrDefault(buildingTypeId, 0);
        int next = nowIntact ? prev + 1 : Math.max(0, prev - 1);

        if (next == 0) {
            typeMap.remove(buildingTypeId);
        } else {
            typeMap.put(buildingTypeId, next);
        }

        ColonySnapshot after = getSnapshot(colonyId);
        if (!before.equals(after) && eventBus != null) {
            eventBus.post(new ColonyEvaluationChangedEvent(
                    colonyId,
                    before.comfort(), after.comfort(),
                    before.magic(), after.magic(),
                    before.wonder(), after.wonder()));
        }
        return !before.equals(after);
    }

    /**
     * Called when a building is fully unregistered (removed from the world, not just damaged).
     * Removes the entry for this type from the colony's map.
     *
     * @return {@code true} if the type count crossed the 0↔1 boundary.
     */
    boolean recordUnregistered(UUID colonyId, String buildingTypeId) {
        if (colonyId == null || buildingTypeId == null) return false;

        ColonySnapshot before = getSnapshot(colonyId);

        Map<String, Integer> typeMap = intactCounts.get(colonyId);
        if (typeMap == null) return false;

        int prev = typeMap.getOrDefault(buildingTypeId, 0);
        if (prev <= 1) {
            typeMap.remove(buildingTypeId);
        } else {
            typeMap.put(buildingTypeId, prev - 1);
        }

        ColonySnapshot after = getSnapshot(colonyId);
        if (!before.equals(after) && eventBus != null) {
            eventBus.post(new ColonyEvaluationChangedEvent(
                    colonyId,
                    before.comfort(), after.comfort(),
                    before.magic(), after.magic(),
                    before.wonder(), after.wonder()));
        }
        return !before.equals(after);
    }

    /**
     * Rebuild the entire registry from scratch.
     * Called once after world load so that the registry reflects the current
     * world state even for buildings that were placed before this module was added.
     */
    void rebuildFrom(BuildSource source) {
        intactCounts.clear();
        for (var state : source.allBuildings()) {
            if (state.getColonyId() == null) continue;
            if (!state.isStructureIntact()) continue;
            Map<String, Integer> typeMap = intactCounts
                    .computeIfAbsent(state.getColonyId(), k -> new ConcurrentHashMap<>());
            typeMap.merge(state.getBuildingTypeId(), 1, Integer::sum);
        }
        Log.info(TAG, "BuildingContributionRegistry rebuilt — {} colonies tracked", intactCounts.size());
    }

    // ── Query ─────────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} if at least one intact building of this type exists
     * in the given colony.
     */
    public boolean isTypeContributing(@Nullable UUID colonyId, String buildingTypeId) {
        if (colonyId == null) return false;
        Map<String, Integer> typeMap = intactCounts.get(colonyId);
        return typeMap != null && typeMap.getOrDefault(buildingTypeId, 0) > 0;
    }

    /**
     * Returns the current intact count for a specific type in a colony.
     */
    public int getIntactCount(@Nullable UUID colonyId, String buildingTypeId) {
        if (colonyId == null) return 0;
        Map<String, Integer> typeMap = intactCounts.get(colonyId);
        return typeMap != null ? typeMap.getOrDefault(buildingTypeId, 0) : 0;
    }

    /**
     * Computes the colony's three evaluation values by iterating every building
     * instance. Each intact building contributes individually —
     * same-type buildings stack. Decoration buildings radiate instead of
     * contributing directly. Shops only contribute if the individual shop has stock.
     */
    public ColonySnapshot getSnapshot(UUID colonyId) {
        BuildingConfigLoader configLoader = BuildingConfigLoader.getInstance();
        int comfort = 0, magic = 0, wonder = 0;

        BuildSource source = this.buildSource;
        if (source != null) {
            // Per-building-instance contribution
            for (BuildingState state : source.allBuildings()) {
                if (!colonyId.equals(state.getColonyId())) continue;
                if (!state.isStructureIntact()) continue;

                BuildingConfig cfg = configLoader.get(state.getBuildingTypeId());
                if (cfg == null) continue;

                String cat = cfg.category();
                if ("decoration".equals(cat)) continue; // radiate, no direct contribution

                if ("shop".equals(cat)) {
                    // Individual shop must have stock to contribute
                    if (!Boolean.TRUE.equals(shopHasStock.get(state.getBuildingId()))) continue;

                    comfort += cfg.comfort();
                    magic   += cfg.magic();
                    wonder  += cfg.wonder();

                    // Add three-values from in-stock goods
                    ShopStockManager stockMgr = ShopStockManager.getActive();
                    if (stockMgr != null) {
                        comfort += stockMgr.getGoodsBonusComfort(state.getBuildingId());
                        magic   += stockMgr.getGoodsBonusMagic(state.getBuildingId());
                        wonder  += stockMgr.getGoodsBonusWonder(state.getBuildingId());
                    }
                } else {
                    comfort += cfg.comfort();
                    magic   += cfg.magic();
                    wonder  += cfg.wonder();
                }
            }
        } else {
            // Fallback: type-count based (no BuildSource available)
            Map<String, Integer> typeMap = intactCounts.get(colonyId);
            if (typeMap != null && !typeMap.isEmpty()) {
                for (Map.Entry<String, Integer> entry : typeMap.entrySet()) {
                    if (entry.getValue() <= 0) continue;
                    BuildingConfig cfg = configLoader.get(entry.getKey());
                    if (cfg == null) continue;
                    if ("decoration".equals(cfg.category())) continue;

                    int contributing;
                    if ("shop".equals(cfg.category())) {
                        contributing = shopTypesWithStock.getOrDefault(entry.getKey(), 0);
                        if (contributing <= 0) continue;
                    } else {
                        contributing = entry.getValue();
                    }
                    comfort += cfg.comfort() * contributing;
                    magic   += cfg.magic()   * contributing;
                    wonder  += cfg.wonder()  * contributing;
                }
            }
        }

        // Merge decoration radiation bonuses for individual functional buildings
        DecorationBonusCache cache = this.decorationBonusCache;
        if (cache != null && source != null) {
            double cap = Config.DECORATION_BONUS_CAP.get();
            for (BuildingState state : source.allBuildings()) {
                if (!colonyId.equals(state.getColonyId())) continue;
                if (!state.isStructureIntact()) continue;
                String cat = state.getCategory();
                if ("decoration".equals(cat) || "wonder".equals(cat)) continue;

                int[] bonus = cache.get(state.getBuildingId());
                if (bonus == null) continue;

                BuildingConfig cfg = configLoader.get(state.getBuildingTypeId());
                if (cfg != null) {
                    comfort += (int) Math.min(bonus[0], cfg.comfort() * cap);
                    magic   += (int) Math.min(bonus[1], cfg.magic() * cap);
                    wonder  += (int) Math.min(bonus[2], cfg.wonder() * cap);
                }
            }
        }

        return new ColonySnapshot(comfort, magic, wonder);
    }

    // ── Simple snapshot record ────────────────────────────────────────────────

    public record ColonySnapshot(int comfort, int magic, int wonder) {
        public static final ColonySnapshot EMPTY = new ColonySnapshot(0, 0, 0);
    }

    // ── Source interface for rebuild ─────────────────────────────────────────

    /**
     * Abstraction over {@link BuildingSavedData} so the registry does not need
     * to import MC classes directly.
     */
    public interface BuildSource {
        Iterable<BuildingState> allBuildings();
    }
}
