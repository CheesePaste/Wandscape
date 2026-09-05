package com.wsteam.wandscape.content.task.component;
import com.wsteam.wandscape.content.npc.system.NavigationSystem;

import com.wsteam.wandscape.content.task.types.GridPos;

import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;
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

    // ---- 水中脱困探测（NavigationSystem 维护）----
    // 位移卡死判据只看“每区间动了多少”，对高岸水池这类能四处游但永远爬不上去的困局
    // 判定失灵（每区间位移都够大，却始终逼近不了目标）。此处在位移判据之外补“净逼近”
    // 判据：记录到目标到达中心的历史最低 3D 距离（含垂直，兼容潜水下潜），逼近停滞连续
    // 达限即认为被困、切自传送脱困。渡河/水下工作全程净逼近（每区间都创新低），不受影响。

    /** 本段 PATHFINDING 中到目标到达中心的历史最低 3D 距离（格）；-1 = 尚未取样（首区间惰性初始化）。 */
    public double waterBestDist = -1.0;
    /** 水中逼近停滞的连续区间计数（每 STUCK_CHECK_INTERVAL_TICKS 判一次），达上限切传送。 */
    public int waterStallCount;

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
        waterBestDist = -1.0;
        waterStallCount = 0;
    }
}
