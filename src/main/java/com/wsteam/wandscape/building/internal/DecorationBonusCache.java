package com.wsteam.wandscape.building.internal;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;
/**
 * Cache for decoration radiation bonuses applied to functional buildings.
 *
 * <p>Keyed by target building UUID. Values are [bonusComfort, bonusMagic, bonusWonder].
 * Rebuilt each decoration scan interval by {@link DecorationBonusSystem}.
 */
public final class DecorationBonusCache {

    private final Map<UUID, int[]> cache = new ConcurrentHashMap<>();

    /** Store a bonus snapshot for a target building. */
    public void update(UUID buildingId, int comfort, int magic, int wonder) {
        cache.put(buildingId, new int[]{comfort, magic, wonder});
    }

    /** Get the cached bonus for a building, or null if none. */
    @Nullable
    public int[] get(UUID buildingId) {
        return cache.get(buildingId);
    }

    /** Remove a single building's cached bonus. */
    public void invalidate(UUID buildingId) {
        cache.remove(buildingId);
    }

    /** Clear all cached bonuses. Called on full rescan or building topology changes. */
    public void clear() {
        cache.clear();
    }
}
