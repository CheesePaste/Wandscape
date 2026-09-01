package com.wsteam.wandscape.content.colony.data;
import com.wsteam.wandscape.content.tourist.data.Emotion;

/**
 * A generated narrative event produced by {@code NarrativeGenerator}.
 * Tagged with type, timestamp, and text ready for display.
 */
public record NarrativeEvent(
        NarrativeEventType type,
        long gameTime,
        Emotion emotion,
        String text
) {

    public static NarrativeEvent of(NarrativeEventType type, long gameTime,
                                     Emotion emotion, String text) {
        return new NarrativeEvent(type, gameTime, emotion, text);
    }

    /** Whether this event qualifies for colony chronicle storage. */
    public boolean isChronicleWorthy() {
        return type.isChronicleWorthy();
    }
}
