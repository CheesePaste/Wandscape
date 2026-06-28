package com.wsteam.wandscape.core.task;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;
/**
 * Per-colony wand lifecycle tracker.
 * Ensures wands are never double-assigned and provides a single source of truth
 * for wand state transitions.
 *
 * <p>Pure logic — zero MC dependencies. The engine layer calls these methods
 * at the appropriate MC-level moments (item retrieved from warehouse, NPC
 * equip animation complete, etc.).
 */
public class WandLifecycle {

    /** colonyId → (wandItemId → state) */
    private final Map<UUID, Map<String, WandLifecycleState>> colonies = new HashMap<>();

    // ── Queries ──

    @Nullable
    public WandLifecycleState getState(UUID colonyId, String wandItemId) {
        Map<String, WandLifecycleState> wands = colonies.get(colonyId);
        return wands != null ? wands.get(wandItemId) : null;
    }

    /** True if the wand is available for reservation (in warehouse, not reserved). */
    public boolean isAvailable(UUID colonyId, String wandItemId) {
        WandLifecycleState state = getState(colonyId, wandItemId);
        return state == null || state == WandLifecycleState.IN_WAREHOUSE;
    }

    /** True if the wand is currently equipped on some NPC. */
    public boolean isEquipped(UUID colonyId, String wandItemId) {
        return getState(colonyId, wandItemId) == WandLifecycleState.EQUIPPED;
    }

    // ── Transitions ──

    /**
     * Reserve a wand for an NPC before it's physically retrieved.
     * Prevents double-assignment by marking the wand as RESERVED.
     *
     * @return true if the reservation succeeded, false if already reserved
     */
    public boolean reserve(UUID colonyId, String wandItemId) {
        WandLifecycleState current = getState(colonyId, wandItemId);
        if (current != null && current != WandLifecycleState.IN_WAREHOUSE) {
            return false; // already reserved or in use
        }
        getOrCreateColony(colonyId).put(wandItemId, WandLifecycleState.RESERVED);
        return true;
    }

    /** Mark a wand as in transit from warehouse to NPC. */
    public void startTransitToNpc(UUID colonyId, String wandItemId) {
        getOrCreateColony(colonyId).put(wandItemId, WandLifecycleState.IN_TRANSIT_TO_NPC);
    }

    /** Confirm the wand is now equipped on the NPC. */
    public void confirmEquip(UUID colonyId, String wandItemId) {
        getOrCreateColony(colonyId).put(wandItemId, WandLifecycleState.EQUIPPED);
    }

    /** Mark a wand as in transit back to warehouse. */
    public void startReturn(UUID colonyId, String wandItemId) {
        getOrCreateColony(colonyId).put(wandItemId, WandLifecycleState.IN_TRANSIT_TO_WAREHOUSE);
    }

    /** Confirm the wand has arrived back at the warehouse. */
    public void confirmReturn(UUID colonyId, String wandItemId) {
        getOrCreateColony(colonyId).put(wandItemId, WandLifecycleState.IN_WAREHOUSE);
    }

    /**
     * Release a reservation (e.g. NPC died before pickup, task cancelled).
     * Only valid from RESERVED state — no-op otherwise.
     */
    public void release(UUID colonyId, String wandItemId) {
        WandLifecycleState current = getState(colonyId, wandItemId);
        if (current == WandLifecycleState.RESERVED) {
            getOrCreateColony(colonyId).put(wandItemId, WandLifecycleState.IN_WAREHOUSE);
        }
    }

    /** Remove all state for a colony (colony deleted). */
    public void removeColony(UUID colonyId) {
        colonies.remove(colonyId);
    }

    /** Clear all state. */
    public void clear() {
        colonies.clear();
    }

    // ── Internal ──

    private Map<String, WandLifecycleState> getOrCreateColony(UUID colonyId) {
        return colonies.computeIfAbsent(colonyId, k -> new HashMap<>());
    }
}
