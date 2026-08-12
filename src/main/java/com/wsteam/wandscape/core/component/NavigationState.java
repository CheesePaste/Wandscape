package com.wsteam.wandscape.core.component;

import com.wsteam.wandscape.core.types.GridPos;

import java.util.List;
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
        TELEPORT_WAITING,
        /** Self-teleport ritual pushed to private queue; waiting for TaskExec to consume it. */
        TELEPORT_RITUAL
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

    /**
     * Road-network waypoints for the current PATHFINDING request.
     * Empty = direct vanilla navigation to {@link #target}.
     */
    public List<GridPos> waypoints = List.of();

    /** Index of the next waypoint in {@link #waypoints} to walk to. */
    public int waypointIndex;

    /**
     * 短距离战术导航标记（战斗走位/交战接近用）：只允许走路，禁止回退传送。
     * 寻路失败/卡住/超时时放弃导航站定，而不是浪费一次 6~10 格的 self_teleport。
     * 长距离任务导航不置此位，仍可传送兜底。
     */
    public boolean walkOnly;

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
        waypoints = List.of();
        waypointIndex = 0;
        walkOnly = false;
    }
}
