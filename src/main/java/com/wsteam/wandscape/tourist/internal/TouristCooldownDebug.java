package com.wsteam.wandscape.tourist.internal;

import com.wsteam.wandscape.shared.log.Log;

/**
 * Debug flags to disable tourist cooldown mechanisms independently.
 * All default to {@code false} (normal behavior). Toggle via
 * {@code /wandscape tourist cooldown <service|visited|preference|all> <on|off>}.
 *
 * <p>Three layers:
 * <ul>
 *   <li>{@code skipServiceCooldown} — disables per-building + global service cooldowns</li>
 *   <li>{@code skipVisitedBuildings} — disables once-per-trip visited-building blocking</li>
 *   <li>{@code skipPreferenceDecay} — disables type-preference decay after visits</li>
 * </ul>
 */
public final class TouristCooldownDebug {

    private static final String TAG = "TouristCooldownDebug";

    /** When true, service cooldowns are never applied and never block selection. */
    public static volatile boolean skipServiceCooldown = false;

    /** When true, visited-building tracking is ignored — tourists can re-visit. */
    public static volatile boolean skipVisitedBuildings = false;

    /** When true, type-preference decay is skipped after each visit. */
    public static volatile boolean skipPreferenceDecay = false;

    private TouristCooldownDebug() {}

    /** Disable all three cooldown layers at once. */
    public static void disableAll() {
        skipServiceCooldown = true;
        skipVisitedBuildings = true;
        skipPreferenceDecay = true;
        Log.info(TAG, "[Debug] ALL cooldowns DISABLED");
    }

    /** Re-enable all three cooldown layers. */
    public static void enableAll() {
        skipServiceCooldown = false;
        skipVisitedBuildings = false;
        skipPreferenceDecay = false;
        Log.info(TAG, "[Debug] ALL cooldowns ENABLED (normal)");
    }

    /** Reset all flags to default (normal behavior). */
    public static void reset() {
        skipServiceCooldown = false;
        skipVisitedBuildings = false;
        skipPreferenceDecay = false;
    }
}
