package com.wsteam.wandscape.projection.client;

import com.wsteam.wandscape.projection.network.BuildingDebugResponsePacket;
import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Standalone debug-inspect mode (G key) — completely independent of
 * soul projection (V key).
 *
 * <p>When active, continuously raycasts from the camera to find
 * buildings and displays basic info in a small translucent HUD overlay.
 */
public final class BuildingDebugClientState {

    private static volatile boolean active = false;

    /** Cached building data from the last server response. Null when looking at non-building. */
    @Nullable
    private static volatile BuildingDebugResponsePacket cachedData = null;

    /** The BlockPos we last sent a request for. Used to dedupe and detect look-away. */
    @Nullable
    private static volatile BlockPos lastRequestedPos = null;

    /** Timestamp of the last request, for timeout. */
    private static volatile long lastRequestTime = 0;

    /** The building UUID we last sent a request for — used to avoid re-requesting the same building. */
    @Nullable
    private static volatile UUID lastRequestedBuildingId = null;

    /** Timestamp (ms) of the last confirmed building detection. 0 = never. */
    private static volatile long lastBuildingDetectedMs = 0;

    /**
     * Display debounce window: a building keeps showing for this long after the
     * last detection, so a few miss frames during camera movement don't make the
     * top bar flicker. 5 ticks @ 20 TPS.
     */
    private static final long SHOW_GRACE_MS = 250;

    private BuildingDebugClientState() {}

    // ── Active toggle ──

    public static boolean isActive() {
        return active;
    }

    public static void setActive(boolean v) {
        active = v;
        if (!v) {
            cachedData = null;
            lastRequestedPos = null;
            lastRequestTime = 0;
            lastRequestedBuildingId = null;
            lastBuildingDetectedMs = 0;
        }
    }

    // ── Cached response data ──

    public static void setCachedData(@Nullable BuildingDebugResponsePacket data) {
        cachedData = data;
        if (data != null) {
            lastBuildingDetectedMs = System.currentTimeMillis();
        }
    }

    public static void clearCachedData() {
        cachedData = null;
    }

    /** Mark that a building was just confirmed under the crosshair — refreshes the debounce window. */
    public static void markBuildingDetected() {
        lastBuildingDetectedMs = System.currentTimeMillis();
    }

    /** True while inside the debounce window (a building was detected recently). */
    public static boolean isWithinGrace() {
        return lastBuildingDetectedMs != 0
                && System.currentTimeMillis() - lastBuildingDetectedMs < SHOW_GRACE_MS;
    }

    /**
     * Clear the cached data, but skip while inside the debounce window so the
     * previous building info stays visible through transient miss frames.
     */
    public static void debouncedClear() {
        if (isWithinGrace()) return;
        clearCachedData();
    }

    /**
     * The building info to display right now: the cached response while within
     * the debounce window, otherwise {@code null} (fall back to the top bar).
     */
    @Nullable
    public static BuildingDebugResponsePacket getDisplayData() {
        if (!isWithinGrace()) return null;
        return cachedData;
    }

    // ── Request tracking ──

    @Nullable
    public static BlockPos getLastRequestedPos() {
        return lastRequestedPos;
    }

    public static void setLastRequestedPos(@Nullable BlockPos pos) {
        lastRequestedPos = pos;
    }

    public static long getLastRequestTime() {
        return lastRequestTime;
    }

    public static void setLastRequestTime(long time) {
        lastRequestTime = time;
    }

    @Nullable
    public static UUID getLastRequestedBuildingId() {
        return lastRequestedBuildingId;
    }

    public static void setLastRequestedBuildingId(@Nullable UUID buildingId) {
        lastRequestedBuildingId = buildingId;
    }
}
