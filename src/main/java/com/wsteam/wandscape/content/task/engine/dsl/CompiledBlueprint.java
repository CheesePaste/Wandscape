package com.wsteam.wandscape.task.engine.dsl;

import com.wsteam.wandscape.task.runtime.TaskSequence;

import java.util.Collections;
import java.util.List;
/**
 * Compilation product of a {@link Blueprint}.
 * Holds the executable sequence and the trigger declarations that
 * produce downstream tasks when events fire.
 */
public record CompiledBlueprint(
        TaskSequence sequence,
        List<TriggerDeclaration> triggers
) {
    public CompiledBlueprint {
        if (triggers == null) triggers = Collections.emptyList();
    }
}
