package org.magiccolony.core.system;

import org.magiccolony.core.ecs.World;
import org.magiccolony.core.task.GlobalTaskPool;

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
