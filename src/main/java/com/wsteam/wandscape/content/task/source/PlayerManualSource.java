package com.wsteam.wandscape.task.source;

import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.task.engine.pool.GlobalTaskPool;
import com.wsteam.wandscape.task.engine.pool.TaskRequest;
/**
 * Adapter-facing API for player-created tasks.
 * Not a polling source - the adapter calls {@link #publish(TaskRequest)} directly.
 */
public class PlayerManualSource implements TaskSource {

    private static final int POLL_INTERVAL = 1; // irrelevant - poll is no-op
    private final GlobalTaskPool taskPool;

    public PlayerManualSource(GlobalTaskPool taskPool) {
        this.taskPool = taskPool;
    }

    @Override
    public int pollIntervalTicks() {
        return POLL_INTERVAL;
    }

    @Override
    public void poll(GlobalTaskPool pool, World world) {
        // No-op: tasks come from direct publish() calls
    }

    /** Called by the adapter layer when a player manually creates a task. */
    public long publish(TaskRequest request) {
        return taskPool.addTask(request);
    }
}
