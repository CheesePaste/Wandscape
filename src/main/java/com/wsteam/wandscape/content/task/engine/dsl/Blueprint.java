package com.wsteam.wandscape.task.engine.dsl;

import com.wsteam.wandscape.task.runtime.TaskSequence;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
/**
 * A named blueprint that defines:
 * <ul>
 *   <li>{@link #steps} — the parameterized generator that produces a {@link TaskSequence}</li>
 *   <li>{@link #triggers} — downstream task rules that fire when events are emitted
 *       during or after execution of this blueprint's steps</li>
 *   <li>{@link #definition} — optional DSL AST. When present, {@code call} steps can
 *       macro-expand this blueprint's steps inline. When null, the blueprint is a
 *       legacy Java lambda and cannot be macro-expanded.</li>
 * </ul>
 *
 * <p>Blueprints are registered in {@link BlueprintRegistry} and looked up by {@link #id}.
 */
public record Blueprint(
        String id,
        BlueprintSteps steps,
        List<TriggerDeclaration> triggers,
        @Nullable BlueprintDefinition definition
) {
    public Blueprint {
        if (triggers == null) triggers = Collections.emptyList();
    }

    /** Convenience: blueprint with no triggers and no definition (legacy lambda). */
    public Blueprint(String id, BlueprintSteps steps) {
        this(id, steps, Collections.emptyList(), null);
    }

    /** Convenience: blueprint with triggers but no definition (legacy lambda). */
    public Blueprint(String id, BlueprintSteps steps, List<TriggerDeclaration> triggers) {
        this(id, steps, triggers, null);
    }

    /** DSL blueprint: steps lambda + AST definition for call macro expansion. */
    public Blueprint(String id, BlueprintSteps steps, BlueprintDefinition definition) {
        this(id, steps, Collections.emptyList(), definition);
    }

    /** Whether this blueprint has a DSL definition (can be macro-expanded via call). */
    public boolean isDsl() {
        return definition != null;
    }
}
