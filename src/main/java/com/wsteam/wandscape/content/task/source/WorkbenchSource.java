package com.wsteam.wandscape.content.task.source;

import com.wsteam.wandscape.content.task.ecs.World;
import com.wsteam.wandscape.content.task.engine.pool.GlobalTaskPool;
/**
 * V1 stub: monitors arcane workbench production queues and creates crafting tasks.
 * Full implementation requires workbench component integration.
 */
public class WorkbenchSource implements TaskSource {

    private static final int POLL_INTERVAL = 30;

    @Override
    public int pollIntervalTicks() {
        return POLL_INTERVAL;
    }

    @Override
    public void poll(GlobalTaskPool pool, World world) {
        // V1: Stub - in production, queries ArcaneWorkbench components
        // and creates crafting tasks when queues are below capacity.
    }
}
