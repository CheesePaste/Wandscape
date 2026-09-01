package com.wsteam.wandscape.content.task.engine.dsl;

import com.wsteam.wandscape.content.task.ecs.World;
import com.wsteam.wandscape.content.task.engine.pool.TaskRequest;

/**
 * Compiles a TaskRequest into a {@link CompiledBlueprint} (sequence + triggers).
 */
@FunctionalInterface
public interface TaskCompiler {
    CompiledBlueprint compile(TaskRequest request, World world);
}
