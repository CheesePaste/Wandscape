package com.wsteam.wandscape.content.task.event;

import java.util.Collections;
import java.util.Map;
/**
 * Single custom event type for all blueprint-emitted events.
 * Distinguished by {@link #name}; carries a string-to-string payload.
 */
public record CustomEvent(String name, Map<String, String> params) {
    public CustomEvent {
        if (params == null) params = Collections.emptyMap();
    }

    @Override
    public String toString() {
        return "CustomEvent[" + name + " params=" + params + "]";
    }
}
