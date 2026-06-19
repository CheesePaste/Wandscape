package org.magiccolony.core.task;

import java.util.Collections;
import java.util.List;

/**
 * A named blueprint that defines:
 * <ul>
 *   <li>{@link #steps} — the parameterized generator that produces a {@link TaskSequence}</li>
 *   <li>{@link #triggers} — downstream task rules that fire when events are emitted
 *       during or after execution of this blueprint's steps</li>
 * </ul>
 *
 * <p>Blueprints are registered in {@link BlueprintRegistry} and looked up by {@link #id}.
 */
public record Blueprint(
        String id,
        BlueprintSteps steps,
        List<TriggerDeclaration> triggers
) {
    public Blueprint {
        if (triggers == null) triggers = Collections.emptyList();
    }

    /** Convenience: blueprint with no triggers. */
    public Blueprint(String id, BlueprintSteps steps) {
        this(id, steps, Collections.emptyList());
    }
}
