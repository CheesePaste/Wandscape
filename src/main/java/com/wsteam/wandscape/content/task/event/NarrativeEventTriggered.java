package com.wsteam.wandscape.content.task.event;

import com.wsteam.wandscape.content.colony.data.NarrativeEvent;

/** Emitted when a narrative event is generated (visit, arrival, departure, milestone, etc.). */
public record NarrativeEventTriggered(NarrativeEvent event) {
    @Override public String toString() { return "NarrativeEventTriggered[" + event.type() + "@" + event.gameTime() + "]"; }
}
