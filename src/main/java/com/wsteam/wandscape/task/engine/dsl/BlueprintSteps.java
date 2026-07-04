package com.wsteam.wandscape.task.engine.dsl;

import java.util.Map;

import com.google.gson.JsonElement;
import com.wsteam.wandscape.task.runtime.TaskSequence;

/**
 * Functional interface that generates a {@link TaskSequence} from task parameters.
 * This is the "generator" half of a {@link Blueprint} — the other half is the
 * list of {@link TriggerDeclaration}s produced at compile time.
 *
 * <p>Parameters are typed {@link JsonElement} values (string, int, pos array, list, map).
 * Legacy consumers call {@code .getAsString()} on simple string/int params.
 */
@FunctionalInterface
public interface BlueprintSteps {
    TaskSequence generate(Map<String, JsonElement> params);
}
