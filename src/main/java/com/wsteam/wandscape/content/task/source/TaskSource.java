package com.wsteam.wandscape.content.task.source;

import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.content.task.engine.pool.GlobalTaskPool;
/**
 * A source of tasks that periodically polls the world state
 * and pushes new tasks into the global pool.
 */
public interface TaskSource {

    /** How many ticks between polls. */
    int pollIntervalTicks();

    /** Generate tasks based on current world state. */
    void poll(GlobalTaskPool pool, World world);
}
