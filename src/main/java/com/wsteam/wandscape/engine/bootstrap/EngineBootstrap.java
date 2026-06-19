package com.wsteam.wandscape.engine.bootstrap;

import java.util.ArrayList;
import java.util.List;

import com.wsteam.wandscape.core.CoreBootstrap;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.core.CoreBootstrapConfig;
import com.wsteam.wandscape.core.boundary.ColonyResourceAccess;
import com.wsteam.wandscape.core.boundary.RitualOps;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.op.DefaultOpExecutors;
import com.wsteam.wandscape.core.system.EventDrivenTaskSource;
import com.wsteam.wandscape.core.system.SystemBlueprintRegistry;
import com.wsteam.wandscape.core.system.TaskSource;
import com.wsteam.wandscape.core.system.WarehouseSource;
import com.wsteam.wandscape.core.system.WorkbenchSource;
import com.wsteam.wandscape.core.task.BlueprintRegistry;
import com.wsteam.wandscape.core.types.GridPos;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.engine.boundary.WandscapeBlockOps;
import com.wsteam.wandscape.engine.source.BuildingTaskSource;
import com.wsteam.wandscape.engine.source.blueprint.BuildingBlueprints;

import net.neoforged.neoforge.event.server.ServerStartingEvent;

/**
 * Bootstraps the engine integration layer.
 * Call from {@link ServerStartingEvent} handler.
 */
public final class EngineBootstrap {
    private static final Logger LOGGER = LogUtils.getLogger();

    private EngineBootstrap() {}

    /**
     * Bootstrap the engine with MC boundary implementations.
     * Must be called once on server start, after all modules are initialized.
     */
    public static World bootstrap() {
        LOGGER.info("CoreBootstrap bootstrap starting...");

        // 1. Build blueprint registry and register build blueprints
        BlueprintRegistry blueprints = new BlueprintRegistry();
        BuildingBlueprints.registerAll(blueprints);
        EventDrivenTaskSource.registerDefaultBlueprints(blueprints);

        // 2. Build system blueprint registry
        SystemBlueprintRegistry sysBlueprints = new SystemBlueprintRegistry();

        // 3. Build task sources
        List<TaskSource> taskSources = new ArrayList<>();
        taskSources.add(new BuildingTaskSource());
        taskSources.add(new WarehouseSource(null)); // eventBus wired by engine
        taskSources.add(new WorkbenchSource());

        // 4. Build boundary implementations
        WandscapeBlockOps blockOps = new WandscapeBlockOps();

        // Stub boundary implementations for not-yet-implemented interfaces
        com.wsteam.wandscape.core.boundary.EntityOps entityOps = new com.wsteam.wandscape.core.boundary.EntityOps() {
            @Override
            public void applyEffect(com.wsteam.wandscape.core.types.EntityId target,
                                     com.wsteam.wandscape.core.types.EffectId effect,
                                     int strength, int duration) {
                // Stage 2: integrate with MC LivingEntity
            }

            @Override
            public GridPos getPosition(com.wsteam.wandscape.core.types.EntityId entity) {
                return GridPos.ORIGIN;
            }
        };

        RitualOps ritualOps = new RitualOps() {
            @Override
            public void beginRitual(com.wsteam.wandscape.core.types.RitualId ritual,
                                     GridPos target, World world, long casterId) {
                // Stage 2: integrate with MC ritual execution
            }

            @Override
            public com.wsteam.wandscape.core.op.OpResult pollRitual(
                    com.wsteam.wandscape.core.types.RitualId ritual,
                    GridPos target, World world, long casterId) {
                return com.wsteam.wandscape.core.op.OpResult.DONE;
            }
        };

        ColonyResourceAccess colonyResources = new ColonyResourceAccess() {
            @Override
            public boolean hasEnough(com.wsteam.wandscape.core.types.ResourceId resource, int amount) {
                return true; // Stage 3: integrate with warehouse BE
            }

            @Override
            public boolean reserve(com.wsteam.wandscape.core.types.ResourceId resource, int amount) {
                return true;
            }

            @Override
            public boolean commit(com.wsteam.wandscape.core.types.ResourceId resource, int amount) {
                return true;
            }

            @Override
            public void release(com.wsteam.wandscape.core.types.ResourceId resource, int amount) {}

            @Override
            public int available(com.wsteam.wandscape.core.types.ResourceId resource) {
                return Integer.MAX_VALUE; // Infinite resources for stage 1
            }
        };

        // 5. Build CoreBootstrapConfig
        CoreBootstrapConfig config = new CoreBootstrapConfig(
                blockOps,
                entityOps,
                ritualOps,
                colonyResources,
                taskSources,
                blueprints,
                sysBlueprints
        );

        // 6. Bootstrap engine
        World world = CoreBootstrap.bootstrap(config);

        // 7. Register default op executors
        DefaultOpExecutors.registerAll(world.opExecutors);

        // 8. Store world in singleton
        WandscapeEngine.setWorld(world);

        LOGGER.info("CoreBootstrap bootstrap complete — {} systems, {} task sources, {} blueprints",
                world.systemCount(), taskSources.size(), blueprints);
        return world;
    }
}
