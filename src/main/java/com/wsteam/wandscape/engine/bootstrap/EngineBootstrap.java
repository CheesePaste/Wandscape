package com.wsteam.wandscape.engine.bootstrap;

import java.util.ArrayList;
import java.util.List;

import com.wsteam.wandscape.core.CoreBootstrap;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.core.CoreBootstrapConfig;
import com.wsteam.wandscape.core.boundary.ColonyResourceAccess;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.op.DefaultOpExecutors;
import com.wsteam.wandscape.core.system.EventDrivenTaskSource;
import com.wsteam.wandscape.core.system.SystemBlueprintRegistry;
import com.wsteam.wandscape.core.system.TaskSource;
import com.wsteam.wandscape.core.system.WarehouseSource;
import com.wsteam.wandscape.core.system.WorkbenchSource;
import com.wsteam.wandscape.core.task.BlueprintInterpreter;
import com.wsteam.wandscape.core.task.BlueprintRegistry;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.engine.boundary.AsyncTransformExecutor;
import com.wsteam.wandscape.engine.boundary.WandscapeBlockOps;
import com.wsteam.wandscape.engine.boundary.WandscapeEntityOps;
import com.wsteam.wandscape.engine.boundary.WandscapeMovementOps;
import com.wsteam.wandscape.engine.boundary.WandscapeRitualOps;
import com.wsteam.wandscape.engine.system.NavigationSystem;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.engine.source.BuildingTaskSource;
import com.wsteam.wandscape.engine.source.blueprint.BlueprintConfigLoader;
import com.wsteam.wandscape.engine.source.blueprint.DataDrivenSteps;

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

        // 1. Build blueprint registry
        BlueprintRegistry blueprints = new BlueprintRegistry();

        // 1a. Register DSL blueprints from JSON (BlueprintConfigLoader)
        //     These are loaded by WandscapeDataLoader at startup / on /reload.
        BlueprintConfigLoader bpConfigLoader = WandscapeEngine.getBlueprintConfigLoader();
        if (bpConfigLoader != null) {
            BlueprintInterpreter interpreter = new BlueprintInterpreter(blueprints);
            bpConfigLoader.registerIn(blueprints, interpreter);
            LOGGER.info("  registered {} DSL blueprints from JSON",
                    bpConfigLoader.getAll().size());
        }

        // 1b. Legacy fallback: for buildings WITHOUT a blueprint ref,
        //     register the old DataDrivenSteps version under "build:<id>".
        BuildingConfigLoader buildingConfigs = BuildingConfigLoader.getInstance();
        int legacyCount = 0;
        for (BuildingConfig config : buildingConfigs.getAll().values()) {
            if (config.blueprint() == null) {
                // No blueprint ref → use legacy DataDrivenSteps
                blueprints.register("build:" + config.id(), DataDrivenSteps.fromConfig(config));
                legacyCount++;
            }
        }
        if (legacyCount > 0) {
            LOGGER.info("  registered {} legacy build blueprints from BuildingConfig JSON (no blueprint ref)",
                    legacyCount);
        }

        EventDrivenTaskSource.registerDefaultBlueprints(blueprints);

        // 2. Build system blueprint registry
        SystemBlueprintRegistry sysBlueprints = new SystemBlueprintRegistry();

        // 3. Build task sources
        List<TaskSource> taskSources = new ArrayList<>();
        taskSources.add(new BuildingTaskSource());
        taskSources.add(new WarehouseSource());
        taskSources.add(new WorkbenchSource());

        // 4. Build boundary implementations
        WandscapeBlockOps blockOps = new WandscapeBlockOps();
        WandscapeEntityOps entityOps = new WandscapeEntityOps();
        WandscapeRitualOps ritualOps = new WandscapeRitualOps();
        WandscapeMovementOps movementOps = new WandscapeMovementOps();

        // Use WarehouseManager (implements WarehouseApi + ColonyResourceAccess).
        // Falls back to stub if warehouse module not loaded.
        ColonyResourceAccess colonyResources;
        var warehouseApi = WandscapeApis.getWarehouseApiSilently();
        if (warehouseApi instanceof ColonyResourceAccess cra) {
            colonyResources = cra;
            LOGGER.info("  ColonyResourceAccess: WarehouseManager (live)");
        } else {
            colonyResources = new ColonyResourceAccess() {
                @Override public boolean hasEnough(com.wsteam.wandscape.core.types.ResourceId r, int a) { return true; }
                @Override public boolean reserve(com.wsteam.wandscape.core.types.ResourceId r, int a) { return true; }
                @Override public boolean commit(com.wsteam.wandscape.core.types.ResourceId r, int a) { return true; }
                @Override public void release(com.wsteam.wandscape.core.types.ResourceId r, int a) {}
                @Override public int available(com.wsteam.wandscape.core.types.ResourceId r) { return Integer.MAX_VALUE; }
            };
            LOGGER.info("  ColonyResourceAccess: stub (warehouse not loaded)");
        }

        // 5. Build CoreBootstrapConfig
        CoreBootstrapConfig config = new CoreBootstrapConfig(
                blockOps,
                entityOps,
                ritualOps,
                movementOps,
                colonyResources,
                taskSources,
                blueprints,
                sysBlueprints
        );

        // 6. Bootstrap engine
        World world = CoreBootstrap.bootstrap(config);

        // 7. Register default op executors
        DefaultOpExecutors.registerAll(world.opExecutors);

        // 8. Register NavigationSystem (drives all NPC movement via NavigationState)
        NavigationSystem navSystem = new NavigationSystem();
        world.addSystem(navSystem);

        // 9. Override TransformOp executor with async version (V2.5 gating demo)
        //    Set to 0 for sync (no gating), >0 for N-tick delay per block.
        int asyncDelay = 5; // 5 MC tick delay per TransformOp
        if (asyncDelay > 0) {
            AsyncTransformExecutor asyncExec = new AsyncTransformExecutor(asyncDelay);
            world.opExecutors.register(asyncExec); // overwrites default TransformExecutor
            WandscapeEngine.setAsyncExecutor(asyncExec);
            LOGGER.info("  AsyncTransformExecutor active: {} tick delay per block", asyncDelay);
        }

        // 10. Publish boundary services
        WandscapeEngine.setMovementOps(movementOps);

        // 11. Store world in singleton
        WandscapeEngine.setWorld(world);

        LOGGER.info("CoreBootstrap bootstrap complete — {} systems, {} task sources, {} blueprints",
                world.systemCount(), taskSources.size(), blueprints);
        return world;
    }
}
