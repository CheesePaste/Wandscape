package com.wsteam.wandscape.core.task;

import com.wsteam.wandscape.core.ecs.World;
/**
 * Compiles a TaskRequest into a {@link CompiledBlueprint} (sequence + triggers).
 */
@FunctionalInterface
public interface TaskCompiler {
    CompiledBlueprint compile(TaskRequest request, World world);
}
