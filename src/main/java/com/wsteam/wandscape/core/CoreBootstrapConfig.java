package com.wsteam.wandscape.core;

import com.wsteam.wandscape.core.boundary.BlockOps;
import com.wsteam.wandscape.core.boundary.ColonyResourceAccess;
import com.wsteam.wandscape.core.boundary.EntityOps;
import com.wsteam.wandscape.core.boundary.MovementOps;
import com.wsteam.wandscape.core.boundary.RitualOps;
import com.wsteam.wandscape.core.system.SystemBlueprintRegistry;
import com.wsteam.wandscape.core.system.TaskSource;
import com.wsteam.wandscape.core.task.BlueprintRegistry;

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
        boolean autoApproveTasks
) {
    public CoreBootstrapConfig {
        if (taskSources == null) taskSources = Collections.emptyList();
        if (blueprints == null) blueprints = new BlueprintRegistry();
        if (systemBlueprints == null) systemBlueprints = new SystemBlueprintRegistry();
    }
}
