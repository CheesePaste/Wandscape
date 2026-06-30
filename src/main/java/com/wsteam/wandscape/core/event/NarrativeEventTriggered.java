package com.wsteam.wandscape.core.event;

import com.wsteam.wandscape.shared.data.NarrativeEvent;

/** Emitted when a narrative event is generated (visit, arrival, departure, milestone, etc.). */
public record NarrativeEventTriggered(NarrativeEvent event) {
    @Override public String toString() { return "NarrativeEventTriggered[" + event.type() + "@" + event.gameTime() + "]"; }
}
