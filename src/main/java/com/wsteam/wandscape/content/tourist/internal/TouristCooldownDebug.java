package com.wsteam.wandscape.content.tourist.internal;

import com.wsteam.wandscape.shared.log.Log;

/**
 * Debug flags to disable tourist mechanism layers independently.
 * All default to {@code false} (normal behavior). Toggle via
 * {@code /wandscape tourist cooldown <visited|all> <on|off>}.
 *
 * <p>服务冷却（cooldown）与偏好衰减（preference）已随游客经济改造删除（visitedBuildings 已防重逛）；
 * 仅保留 visited 开关用于调试「停留期一栋建筑只逛一次」。
 */
public final class TouristCooldownDebug {

    private static final String TAG = "TouristCooldownDebug";

    /** When true, visited-building tracking is ignored — tourists can re-visit. */
    public static volatile boolean skipVisitedBuildings = false;

    private TouristCooldownDebug() {}

    /** Disable all remaining mechanism layers at once. */
    public static void disableAll() {
        skipVisitedBuildings = true;
        Log.info(TAG, "[Debug] visited-buildings blocking DISABLED");
    }

    /** Re-enable all layers. */
    public static void enableAll() {
        skipVisitedBuildings = false;
        Log.info(TAG, "[Debug] visited-buildings blocking ENABLED (normal)");
    }

    /** Reset all flags to default (normal behavior). */
    public static void reset() {
        skipVisitedBuildings = false;
    }
}
