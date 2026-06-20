package com.wsteam.wandscape.core.component;

import com.wsteam.wandscape.core.types.GridPos;

import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

/**
 * Single source of truth for NPC movement.
 *
 * <p>Owned by {@code NavigationSystem} (engine layer). Other systems
 * (e.g. {@code TaskExecutionSystem}) set the target and mode; the
 * NavigationSystem drives the actual movement each tick.
 */
public class NavigationState {

    public enum Mode {
        /** No movement in progress. */
        IDLE,
        /** Walking via vanilla {@code PathNavigation}. */
        PATHFINDING,
        /** Waiting for mana to regen before ritual teleport. */
        TELEPORT_WAITING
    }

    public Mode mode = Mode.IDLE;

    /** Target block position, or null when idle. */
    @Nullable
    public GridPos target;

    /**
     * Future completed when movement finishes (arrived, teleported, or cancelled).
     * Set by NavigationSystem when mode transitions away from IDLE.
     */
    @Nullable
    public CompletableFuture<Void> future;

    // ---- Tracking (managed by NavigationSystem) ----

    public int startTick;
    public int stuckChecks;
    public int repathCount;
    public int lastCheckTick;
    public double lastCheckX, lastCheckZ;

    /** Reset to idle, clearing all state. */
    public void reset() {
        if (future != null && !future.isDone()) {
            future.complete(null);
        }
        mode = Mode.IDLE;
        target = null;
        future = null;
        startTick = 0;
        stuckChecks = 0;
        repathCount = 0;
        lastCheckTick = 0;
        lastCheckX = 0;
        lastCheckZ = 0;
    }
}
