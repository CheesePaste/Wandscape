package com.wsteam.wandscape.shared.data;

/**
 * Emotional outcome of a tourist visit, derived from satisfaction delta.
 * Used by {@code NarrativeGenerator} to select tone-appropriate templates.
 */
public enum Emotion {

    /** satisfactionDelta ≥ 20 */
    DELIGHTED,

    /** satisfactionDelta 10..19 */
    PLEASED,

    /** satisfactionDelta 1..9 */
    SATISFIED,

    /** satisfactionDelta == 0 */
    NEUTRAL,

    /** satisfactionDelta -1..-9 */
    DISAPPOINTED,

    /** satisfactionDelta ≤ -10 */
    UPSET;

    /**
     * Map a satisfaction delta to the corresponding emotion.
     */
    public static Emotion fromDelta(int delta) {
        if (delta >= 20) return DELIGHTED;
        if (delta >= 10) return PLEASED;
        if (delta >= 1)  return SATISFIED;
        if (delta == 0)  return NEUTRAL;
        if (delta >= -9) return DISAPPOINTED;
        return UPSET;
    }

    /**
     * Map overall satisfaction value (0-100) to a departure tone.
     */
    public static Emotion fromSatisfaction(int satisfaction) {
        if (satisfaction >= 90) return DELIGHTED;
        if (satisfaction >= 70) return PLEASED;
        if (satisfaction >= 40) return NEUTRAL;
        return DISAPPOINTED;
    }
}
