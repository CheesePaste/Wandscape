package com.wsteam.wandscape.core.task;

import com.wsteam.wandscape.core.ecs.World;

import java.util.HashMap;
import java.util.Map;

/**
 * Registry of task blueprints. Acts as the TaskCompiler for the engine.
 */
public class BlueprintRegistry implements TaskCompiler {

    private final Map<String, Blueprint> blueprints = new HashMap<>();

    /** Register a blueprint by its id. */
    public void register(String id, Blueprint blueprint) {
        blueprints.put(id, blueprint);
    }

    /** Convenience: register a blueprint with no triggers. */
    public void register(String id, BlueprintSteps steps) {
        blueprints.put(id, new Blueprint(id, steps));
    }

    public Blueprint get(String id) {
        return blueprints.get(id);
    }

    public boolean has(String id) {
        return blueprints.containsKey(id);
    }

    @Override
    public CompiledBlueprint compile(TaskRequest request, World world) {
        Blueprint bp = blueprints.get(request.blueprintId());
        if (bp == null) {
            throw new IllegalArgumentException("Unknown blueprint: " + request.blueprintId());
        }
        TaskSequence seq = bp.steps().generate(request.params());
        return new CompiledBlueprint(seq, bp.triggers());
    }
}
