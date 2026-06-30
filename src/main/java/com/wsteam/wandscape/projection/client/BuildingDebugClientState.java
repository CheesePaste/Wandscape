package com.wsteam.wandscape.projection.client;

import com.wsteam.wandscape.projection.network.BuildingDebugResponsePacket;

import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;

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
        }
    }

    // ── Cached response data ──

    /** The cached building data to display in the overlay. Null = nothing to show. */
    @Nullable
    public static BuildingDebugResponsePacket getCachedData() {
        return cachedData;
    }

    public static void setCachedData(@Nullable BuildingDebugResponsePacket data) {
        cachedData = data;
    }

    public static void clearCachedData() {
        cachedData = null;
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
}
