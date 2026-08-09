package com.wsteam.wandscape.shared.data;

/**
 * Emotional outcome of a tourist visit, derived from bar fill delta.
 * Used by {@code NarrativeGenerator} to select tone-appropriate templates.
 */
public enum Emotion {

    /** barDelta ≥ 20 */
    DELIGHTED,

    /** barDelta 10..19 */
    PLEASED,

    /** barDelta 1..9 */
    SATISFIED,

    /** barDelta == 0 */
    NEUTRAL,

    /** barDelta -1..-9 */
    DISAPPOINTED,

    /** barDelta ≤ -10 */
    UPSET;

    /**
     * Map a bar delta to the corresponding emotion.
     * barDelta = 三条需求条填充率增量之和（一次交互后 Comfort/Magic/Wonder ratio 增量的和）。
     */
    public static Emotion fromDelta(int barDelta) {
        if (barDelta >= 20) return DELIGHTED;
        if (barDelta >= 10) return PLEASED;
        if (barDelta >= 1)  return SATISFIED;
        if (barDelta == 0)  return NEUTRAL;
        if (barDelta >= -9) return DISAPPOINTED;
        return UPSET;
    }

    /**
     * Map overall bar fill (min-ratio×100, 0-100) to a departure tone.
     */
    public static Emotion fromBarRatio(int minRatioPct) {
        if (minRatioPct >= 90) return DELIGHTED;
        if (minRatioPct >= 70) return PLEASED;
        if (minRatioPct >= 40) return NEUTRAL;
        return DISAPPOINTED;
    }
}
