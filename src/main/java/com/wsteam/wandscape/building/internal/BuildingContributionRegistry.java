package com.wsteam.wandscape.building.internal;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.shared.event.ColonyEvaluationChangedEvent;

import net.neoforged.bus.api.IEventBus;

/**
 * Tracks, per colony, how many <em>intact</em> buildings exist for each building type.
 *
 * <p>A building type contributes its {@link BuildingConfig} comfort / magic / wonder
 * values to the colony if and only if {@code intactCount > 0} for that type.
 * Multiple buildings of the same type do not stack — presence is binary.
 *
 * <p>Owned and called from {@link BuildingSavedData}.  All mutations must go through
 * {@link #recordIntactChange} so that the evaluation change event is fired exactly
 * once per transition.
 */
public final class BuildingContributionRegistry {
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * ColonyId → (buildingTypeId → number of currently intact buildings of this type).
     * Only buildings with {@code structureIntact = true} are counted here.
     */
    private final Map<UUID, Map<String, Integer>> intactCounts = new ConcurrentHashMap<>();

    @Nullable
    private final IEventBus eventBus;

    BuildingContributionRegistry(@Nullable IEventBus eventBus) {
        this.eventBus = eventBus;
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

        Map<String, Integer> typeMap = intactCounts.computeIfAbsent(colonyId, k -> new ConcurrentHashMap<>());
        int prev = typeMap.getOrDefault(buildingTypeId, 0);
        int next = nowIntact ? prev + 1 : Math.max(0, prev - 1);

        if (next == 0) {
            typeMap.remove(buildingTypeId);
        } else {
            typeMap.put(buildingTypeId, next);
        }

        // A change to evaluation values only happens at the 0↔1 boundary
        boolean crossedBoundary = (prev == 0 && next == 1) || (prev == 1 && next == 0);
        if (crossedBoundary && eventBus != null) {
            fireChangeEvent(colonyId, typeMap);
        }
        return crossedBoundary;
    }

    /**
     * Called when a building is fully unregistered (removed from the world, not just damaged).
     * Removes the entry for this type from the colony's map.
     *
     * @return {@code true} if the type count crossed the 0↔1 boundary.
     */
    boolean recordUnregistered(UUID colonyId, String buildingTypeId) {
        if (colonyId == null || buildingTypeId == null) return false;

        Map<String, Integer> typeMap = intactCounts.get(colonyId);
        if (typeMap == null) return false;

        int prev = typeMap.getOrDefault(buildingTypeId, 0);
        if (prev <= 1) {
            typeMap.remove(buildingTypeId);
        } else {
            typeMap.put(buildingTypeId, prev - 1);
        }

        boolean crossedBoundary = prev == 1;
        if (crossedBoundary && eventBus != null) {
            fireChangeEvent(colonyId, intactCounts.get(colonyId));
        }
        return crossedBoundary;
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
        LOGGER.info("BuildingContributionRegistry rebuilt — {} colonies tracked", intactCounts.size());
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
     * Computes the colony's three evaluation values from the current registry state.
     * Each building type contributes its config values at most once (binary presence).
     */
    public ColonySnapshot getSnapshot(UUID colonyId) {
        Map<String, Integer> typeMap = intactCounts.get(colonyId);
        if (typeMap == null || typeMap.isEmpty()) {
            return ColonySnapshot.EMPTY;
        }

        BuildingConfigLoader configLoader = BuildingConfigLoader.getInstance();
        int comfort = 0, magic = 0, wonder = 0;
        for (Map.Entry<String, Integer> entry : typeMap.entrySet()) {
            if (entry.getValue() <= 0) continue;
            BuildingConfig cfg = configLoader.get(entry.getKey());
            if (cfg != null) {
                comfort += cfg.comfort();
                magic   += cfg.magic();
                wonder  += cfg.wonder();
            }
        }
        return new ColonySnapshot(comfort, magic, wonder);
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void fireChangeEvent(UUID colonyId, @Nullable Map<String, Integer> typeMap) {
        ColonySnapshot snapshot = getSnapshot(colonyId);
        ColonyEvaluationChangedEvent event = new ColonyEvaluationChangedEvent(
                colonyId,
                snapshot.comfort() - 1,   // old = new - delta (at most 1 type contributes per call)
                snapshot.comfort(),
                snapshot.magic()   - 1,
                snapshot.magic(),
                snapshot.wonder()  - 1,
                snapshot.wonder()
        );
        // The delta fields above are upper-bounded estimates; the actual delta
        // may be less if a different type is affected.  Subscribers should read
        // the full before/after values, not the delta.
        eventBus.post(event);
        LOGGER.debug("ColonyEvaluationChangedEvent posted for colony={} → C={} M={} W={}",
                colonyId.toString().substring(0, 8),
                snapshot.comfort(), snapshot.magic(), snapshot.wonder());
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
