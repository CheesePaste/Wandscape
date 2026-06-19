package org.magiccolony.core.system;

import org.magiccolony.core.Log;
import org.magiccolony.core.ecs.System;
import org.magiccolony.core.ecs.World;
import org.magiccolony.core.task.GlobalTaskPool;

import java.util.List;

/**
 * Polls all registered TaskSources on their configured intervals.
 * Runs second in the tick order (after ManaRegenSystem).
 */
public class TaskSourcePoller implements System {

    private static final String TAG = "TaskSrcPoller";

    private final List<TaskSource> sources;
    private int tickCounter = 0;

    public TaskSourcePoller(List<TaskSource> sources) {
        this.sources = sources;
    }

    @Override
    public void update(World world, float delta) {
        tickCounter++;

        for (TaskSource source : sources) {
            if (tickCounter % source.pollIntervalTicks() == 0) {
                Log.debug(TAG, "polling %s (tick %d)", source.getClass().getSimpleName(), tickCounter);
                source.poll(world.taskPool, world);
            }
        }
    }

    /** Reset the tick counter (useful for testing). */
    public void resetCounter() {
        tickCounter = 0;
    }
}
