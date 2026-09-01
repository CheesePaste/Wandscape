package com.wsteam.wandscape.impl;

import com.wsteam.wandscape.content.task.boundary.*;
import com.wsteam.wandscape.content.task.engine.dsl.BlueprintRegistry;
import com.wsteam.wandscape.content.task.engine.pool.BuildingTaskPool;
import com.wsteam.wandscape.content.task.scheduler.SystemBlueprintRegistry;
import com.wsteam.wandscape.content.task.source.TaskSource;

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
