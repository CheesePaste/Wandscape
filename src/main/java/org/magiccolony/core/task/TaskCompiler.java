package org.magiccolony.core.task;

import org.magiccolony.core.ecs.World;

/**
 * Compiles a TaskRequest into a {@link CompiledBlueprint} (sequence + triggers).
 */
@FunctionalInterface
public interface TaskCompiler {
    CompiledBlueprint compile(TaskRequest request, World world);
}
