package com.wsteam.wandscape.content.task.engine.dsl;

import com.google.gson.JsonElement;
import com.wsteam.wandscape.content.task.runtime.TaskSequence;

import java.util.List;
import java.util.Map;
/**
 * The parsed AST of a blueprint JSON file.
 * This is the "blueprint as data" — a {@link BlueprintInterpreter} turns it into
 * a {@link TaskSequence} at runtime given a concrete {@code params} map.
 *
 * @param id          unique blueprint identifier (e.g. "build:place_structure")
 * @param params      declared parameter names → their types
 * @param steps       the root step list
 * @param displayName optional human-readable name
 * @param description optional description text
 * @param defaults    default values for optional params (applied when a caller omits them)
 */
public record BlueprintDefinition(
        String id,
        Map<String, ParamType> params,
        List<StepNode> steps,
        String displayName,
        String description,
        Map<String, JsonElement> defaults
) {
    /** Convenience: blueprint with no description/displayName/defaults. */
    public BlueprintDefinition(String id, Map<String, ParamType> params, List<StepNode> steps) {
        this(id, params, steps, "", "", Map.of());
    }

    /** Convenience: blueprint with displayName/description but no defaults. */
    public BlueprintDefinition(String id, Map<String, ParamType> params, List<StepNode> steps,
                              String displayName, String description) {
        this(id, params, steps, displayName, description, Map.of());
    }
}
