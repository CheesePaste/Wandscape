package com.wsteam.wandscape.core.system;

import com.wsteam.wandscape.core.Log;
import com.wsteam.wandscape.core.ecs.System;
import com.wsteam.wandscape.core.ecs.World;

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
