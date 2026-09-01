package com.wsteam.wandscape.content.task.source;

import com.wsteam.wandscape.core.ecs.System;
import com.wsteam.wandscape.core.ecs.World;

import java.util.List;

/**
 * Polls all registered TaskSources on their configured intervals.
 * Runs second in the tick order.
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
                source.poll(world.taskPool, world);
            }
        }
    }

    /** Reset the tick counter (useful for testing). */
    public void resetCounter() {
        tickCounter = 0;
    }
}
