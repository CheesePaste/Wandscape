package com.wsteam.wandscape.task.engine.dsl;

import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.task.engine.pool.TaskRequest;

/**
 * Compiles a TaskRequest into a {@link CompiledBlueprint} (sequence + triggers).
 */
@FunctionalInterface
public interface TaskCompiler {
    CompiledBlueprint compile(TaskRequest request, World world);
}
