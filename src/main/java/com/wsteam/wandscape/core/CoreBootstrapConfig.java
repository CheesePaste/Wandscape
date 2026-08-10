package com.wsteam.wandscape.core;

import com.wsteam.wandscape.core.boundary.BlockOps;
import com.wsteam.wandscape.core.boundary.ColonyResourceAccess;
import com.wsteam.wandscape.core.boundary.EntityOps;
import com.wsteam.wandscape.core.boundary.MovementOps;
import com.wsteam.wandscape.core.boundary.RitualOps;
import com.wsteam.wandscape.task.scheduler.SystemBlueprintRegistry;
import com.wsteam.wandscape.task.source.TaskSource;
import com.wsteam.wandscape.task.engine.dsl.BlueprintRegistry;
import com.wsteam.wandscape.task.engine.pool.BuildingTaskPool;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
/**
 * Configuration bundle for bootstrapping the engine.
 * All boundary services are injected here; the core owns no Minecraft code.
 */
public record CoreBootstrapConfig(
        BlockOps blockOps,
        EntityOps entityOps,
        RitualOps ritualOps,
        MovementOps movementOps,
        ColonyResourceAccess colonyResources,
        List<TaskSource> taskSources,
        BlueprintRegistry blueprints,
        SystemBlueprintRegistry systemBlueprints,
        boolean autoApproveTasks,
        int schedulerHeartbeatTicks,
        @Nullable BuildingTaskPool buildingTaskPool
) {
    public CoreBootstrapConfig {
        if (taskSources == null) taskSources = Collections.emptyList();
        if (blueprints == null) blueprints = new BlueprintRegistry();
        if (systemBlueprints == null) systemBlueprints = new SystemBlueprintRegistry();
        if (schedulerHeartbeatTicks <= 0) schedulerHeartbeatTicks = 2;
    }

    /** Convenience constructor without BuildingTaskPool (scheduler heartbeat defaults to 2). */
    public CoreBootstrapConfig(
            BlockOps blockOps,
            EntityOps entityOps,
            RitualOps ritualOps,
            MovementOps movementOps,
            ColonyResourceAccess colonyResources,
            List<TaskSource> taskSources,
            BlueprintRegistry blueprints,
            SystemBlueprintRegistry systemBlueprints,
            boolean autoApproveTasks) {
        this(blockOps, entityOps, ritualOps, movementOps, colonyResources,
                taskSources, blueprints, systemBlueprints, autoApproveTasks, 2, null);
    }

    /** Convenience constructor with BuildingTaskPool (scheduler heartbeat defaults to 2). */
    public CoreBootstrapConfig(
            BlockOps blockOps,
            EntityOps entityOps,
            RitualOps ritualOps,
            MovementOps movementOps,
            ColonyResourceAccess colonyResources,
            List<TaskSource> taskSources,
            BlueprintRegistry blueprints,
            SystemBlueprintRegistry systemBlueprints,
            boolean autoApproveTasks,
            @Nullable BuildingTaskPool buildingTaskPool) {
        this(blockOps, entityOps, ritualOps, movementOps, colonyResources,
                taskSources, blueprints, systemBlueprints, autoApproveTasks, 2, buildingTaskPool);
    }
}
