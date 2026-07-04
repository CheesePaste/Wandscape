package com.wsteam.wandscape.engine.system;

import java.util.*;

import com.wsteam.wandscape.core.ecs.System;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.task.engine.pool.GlobalTask;
import com.wsteam.wandscape.task.runtime.TaskState;
import com.wsteam.wandscape.core.types.ResourceStack;

import com.wsteam.wandscape.shared.log.Log;

/**
 * Monitors {@link TaskState#FAILED} tasks and attempts automated recovery.
 * Currently handles AWAITING_RESOURCES wake-up.
 *
 * <p>Runs on a 20-tick heartbeat to avoid overhead.
 */
public class FailureAnalyzerSystem implements System {

    private static final String TAG = "FailureAnalyzerSystem";
    private static final int HEARTBEAT = 20;

    private int tickCounter = 0;

    public FailureAnalyzerSystem() {
    }

    @Override
    public void update(World world, float delta) {
        tickCounter++;
        if (tickCounter % HEARTBEAT != 0) return;

        checkAwaitingResources(world);
    }

    /**
     * Poll all AWAITING_RESOURCES tasks and transition back to PENDING_ASSIGN
     * when the warehouse has enough of the needed resource.
     */
    private void checkAwaitingResources(World world) {
        List<GlobalTask> waiting = world.taskPool.getByState(TaskState.AWAITING_RESOURCES);
        if (waiting.isEmpty()) return;

        int awakened = 0;
        for (GlobalTask task : waiting) {
            if (task.awaitingResource == null || task.awaitingResource.isEmpty()) continue;
            // All-or-nothing: ALL needed resources must be available
            boolean allAvailable = true;
            for (ResourceStack need : task.awaitingResource) {
                if (world.colonyResources.available(need.resource()) < need.amount()) {
                    allAvailable = false;
                    break;
                }
            }
            if (allAvailable) {
                task.state = TaskState.PENDING_ASSIGN;
                task.awaitingResource = null;
                awakened++;
            }
        }
        if (awakened > 0) {
            Log.info(TAG, "[FailureAnalyzer] awakened {} AWAITING_RESOURCES tasks", awakened);
        }
    }
}
