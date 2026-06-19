package org.magiccolony.core;

import org.magiccolony.core.boundary.*;
import org.magiccolony.core.system.SystemBlueprintRegistry;
import org.magiccolony.core.system.TaskSource;
import org.magiccolony.core.task.BlueprintRegistry;

import java.util.Collections;
import java.util.List;

/**
 * Configuration bundle for bootstrapping the engine.
 * All boundary services are injected here; the core owns no Minecraft code.
 */
public record EngineConfig(
        BlockOps blockOps,
        EntityOps entityOps,
        RitualOps ritualOps,
        ColonyResourceAccess colonyResources,
        List<TaskSource> taskSources,
        BlueprintRegistry blueprints,
        SystemBlueprintRegistry systemBlueprints
) {
    public EngineConfig {
        if (taskSources == null) taskSources = Collections.emptyList();
        if (blueprints == null) blueprints = new BlueprintRegistry();
        if (systemBlueprints == null) systemBlueprints = new SystemBlueprintRegistry();
    }
}
