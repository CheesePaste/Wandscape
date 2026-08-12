package com.wsteam.wandscape.engine.bootstrap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.wsteam.wandscape.core.types.ResourceId;
import com.wsteam.wandscape.core.CoreBootstrap;
import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.core.CoreBootstrapConfig;
import com.wsteam.wandscape.core.boundary.ColonyResourceAccess;
import com.wsteam.wandscape.core.boundary.ResourceShortageHandler;
import com.wsteam.wandscape.engine.service.AchievementService;
import com.wsteam.wandscape.engine.service.StatsService;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.op.executor.DefaultOpExecutors;
import com.wsteam.wandscape.task.source.EventDrivenTaskSource;
import com.wsteam.wandscape.task.scheduler.SystemBlueprintRegistry;
import com.wsteam.wandscape.task.source.TaskSource;
import com.wsteam.wandscape.task.source.WorkbenchSource;
import com.wsteam.wandscape.task.engine.dsl.BlueprintInterpreter;
import com.wsteam.wandscape.task.engine.dsl.BlueprintRegistry;
import com.wsteam.wandscape.task.engine.pool.BuildingTaskPool;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.engine.boundary.AsyncTransformExecutor;
import com.wsteam.wandscape.engine.boundary.WandscapeBlockInteractExecutor;
import com.wsteam.wandscape.engine.boundary.WandscapeBlockOps;
import com.wsteam.wandscape.engine.boundary.WandscapeEntityOps;
import com.wsteam.wandscape.engine.boundary.WandscapeMovementOps;
import com.wsteam.wandscape.engine.boundary.ResourceRequestExecutor;
import com.wsteam.wandscape.engine.boundary.WandscapeRitualOps;
import com.wsteam.wandscape.engine.system.ResourceSupplySystem;
import com.wsteam.wandscape.engine.system.NavigationSystem;
import com.wsteam.wandscape.engine.transport.ItemTransportManager;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.engine.source.BuildingTaskSource;
import com.wsteam.wandscape.engine.source.blueprint.BlueprintConfigLoader;
import com.wsteam.wandscape.guard.GuardBlueprints;
import com.wsteam.wandscape.guard.GuardTaskSource;
import com.wsteam.wandscape.guard.executor.GuardAttackExecutor;
import com.wsteam.wandscape.guard.executor.SelfDefenseExecutor;
import com.wsteam.wandscape.building.executor.AltarCastExecutor;

import net.neoforged.neoforge.event.server.ServerStartingEvent;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Bootstraps the engine integration layer.
 * Call from {@link ServerStartingEvent} handler.
 */
public final class EngineBootstrap {
    private static final String TAG = "EngineBootstrap";

    private EngineBootstrap() {}

    /**
     * Bootstrap the engine with MC boundary implementations.
     * Must be called once on server start, after all modules are initialized.
     */
    public static World bootstrap() {
        Log.info(TAG, "CoreBootstrap bootstrap starting...");

        // 1. Build blueprint registry
        BlueprintRegistry blueprints = new BlueprintRegistry();

        // 1a. Register DSL blueprints from JSON (BlueprintConfigLoader)
        //     These are loaded by WandscapeDataLoader at startup / on /reload.
        BlueprintConfigLoader bpConfigLoader = WandscapeEngine.getBlueprintConfigLoader();
        if (bpConfigLoader != null) {
            BlueprintInterpreter interpreter = new BlueprintInterpreter(blueprints);
            bpConfigLoader.registerIn(blueprints, interpreter);
            Log.info(TAG, "  registered {} DSL blueprints from JSON",
                    bpConfigLoader.getAll().size());
        }

        EventDrivenTaskSource.registerDefaultBlueprints(blueprints);
        GuardBlueprints.registerDefault(blueprints);

        // 2. Build system blueprint registry
        SystemBlueprintRegistry sysBlueprints = new SystemBlueprintRegistry();

        // 3. Build task sources
        List<TaskSource> taskSources = new ArrayList<>();
        taskSources.add(new BuildingTaskSource());
        taskSources.add(new WorkbenchSource());
        taskSources.add(new GuardTaskSource());

        // 4. Build boundary implementations
        WandscapeBlockOps blockOps = new WandscapeBlockOps();
        WandscapeEntityOps entityOps = new WandscapeEntityOps();
        WandscapeRitualOps ritualOps = new WandscapeRitualOps();
        WandscapeMovementOps movementOps = new WandscapeMovementOps();
        WandscapeEngine.setRitualOps(ritualOps);

        // Use WarehouseManager (implements WarehouseApi + ColonyResourceAccess).
        // Falls back to stub if warehouse module not loaded.
        ColonyResourceAccess colonyResources;
        var warehouseApi = WandscapeApis.getWarehouseApiSilently();
        if (warehouseApi instanceof ColonyResourceAccess cra) {
            colonyResources = cra;
            Log.info(TAG, "  ColonyResourceAccess: WarehouseManager (live)");
        } else {
            colonyResources = new ColonyResourceAccess() {
                @Override public boolean hasEnough(ResourceId r, int a) { return true; }
                @Override public boolean reserve(ResourceId r, int a) { return true; }
                @Override public boolean commit(ResourceId r, int a) { return true; }
                @Override public void release(ResourceId r, int a) {}
                @Override public int available(ResourceId r) { return Integer.MAX_VALUE; }
                @Override public void addResource(ResourceId r, int a) {}
            };
            Log.info(TAG, "  ColonyResourceAccess: stub (warehouse not loaded)");
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
                sysBlueprints,
                com.wsteam.wandscape.Config.AUTO_APPROVE_TASKS.get(),
                com.wsteam.wandscape.Config.SCHEDULER_HEARTBEAT_TICKS.get(),
                new BuildingTaskPool()
        );

        // 6. Bootstrap engine
        World world = CoreBootstrap.bootstrap(config);

        // 6a. Wire resource-added callback so warehouse additions wake AWAITING_RESOURCES tasks
        if (colonyResources instanceof com.wsteam.wandscape.warehouse.WarehouseManager wm) {
            wm.setResourceAddedListener(world.taskPool::onResourceAdded);
            Log.info(TAG, "  WarehouseManager ResourceAddedListener wired");
        }

        // 6b. Wire ritualOps into the world (after bootstrap so world exists)
        world.ritualOps = ritualOps;

        // 6b. Wire resource shortage handler directly into task pool
        world.taskPool.setResourceShortageHandler(createShortageHandler(world));
        Log.info(TAG, "  ResourceShortageHandler → GlobalTaskPool wired");

        // 7. Register default op executors
        DefaultOpExecutors.registerAll(world.opExecutors);

        // 7b. Register guard combat executor (sustained cast loop, driven by onServerTick tickAll)
        GuardAttackExecutor guardExec = new GuardAttackExecutor();
        world.opExecutors.register(guardExec);
        WandscapeEngine.setGuardExecutor(guardExec);
        Log.info(TAG, "  GuardAttackExecutor registered");

        // 7b2. Register NPC self-defense executor (proactive aggro + retaliation, preempts tasks)
        SelfDefenseExecutor selfDefenseExec = new SelfDefenseExecutor();
        world.opExecutors.register(selfDefenseExec);
        WandscapeEngine.setSelfDefenseExecutor(selfDefenseExec);
        Log.info(TAG, "  SelfDefenseExecutor registered");

        // 7c. Register altar cast executor (NPC casts an altar-only magic at the altar;
        //     channeling countdown driven by onServerTick tickAll)
        AltarCastExecutor altarCastExec = new AltarCastExecutor();
        world.opExecutors.register(altarCastExec);
        WandscapeEngine.setAltarCastExecutor(altarCastExec);
        Log.info(TAG, "  AltarCastExecutor registered");

        // 8. Register NavigationSystem (drives all NPC movement via NavigationState)
        NavigationSystem navSystem = new NavigationSystem();
        world.addSystem(navSystem);

        // 8b. Register ResourceSupplySystem (scans AWAITING_RESOURCES, orchestrates supply)
        ResourceSupplySystem supplySystem = new ResourceSupplySystem();
        world.addSystem(supplySystem);
        Log.info(TAG, "  ResourceSupplySystem registered");

        // 9. Override TransformOp executor with async version (V2.5 gating demo)
        //    Set to 0 for sync (no gating), >0 for N-tick delay per block.
        //    保持 1 tick：建造即时感更好（WORK_SPEED 作用于采集/合成的大 channelTicks）。
        int asyncDelay = 1;
        if (asyncDelay > 0) {
            AsyncTransformExecutor asyncExec = new AsyncTransformExecutor(asyncDelay);
            world.opExecutors.register(asyncExec); // overwrites default TransformExecutor
            WandscapeEngine.setAsyncExecutor(asyncExec);
            Log.info(TAG, "  AsyncTransformExecutor active: {} tick delay per block", asyncDelay);
        }

        // 9b. Create shared item transport manager (visual item flight)
        ItemTransportManager transporter = new ItemTransportManager();
        WandscapeEngine.setTransporter(transporter);

        // 9c. Override BlockInteractOp executor with async version.
        //     Handles both sync actions (toggle/activate/open_gui) and
        //     async actions (gather/decompose/synthesize) with configurable timing/mana.
        //     Receives transporter for visual NPC→warehouse transport on production complete.
        WandscapeBlockInteractExecutor blockInteractExec = new WandscapeBlockInteractExecutor(transporter);
        world.opExecutors.register(blockInteractExec); // overwrites default BlockInteractExecutor
        WandscapeEngine.setBlockInteractExec(blockInteractExec);
        Log.info(TAG, "  WandscapeBlockInteractExecutor active (sync + async actions + transport)");

        // 9d. Register resource request executor (replaces inline handling)
        //     Uses the transporter for visual item delivery from warehouse to NPC.
        ResourceRequestExecutor resourceReqExec = new ResourceRequestExecutor(transporter);
        world.opExecutors.register(resourceReqExec);
        WandscapeEngine.setResourceRequestExec(resourceReqExec);
        Log.info(TAG, "  ResourceRequestExecutor registered (visual transport, staggered)");

        // 10. Publish boundary services
        WandscapeEngine.setMovementOps(movementOps);

        // 11. Store world in singleton (must precede service registration)
        WandscapeEngine.setWorld(world);

        // 12. Register narrative event subscribers (stats, achievements)
        StatsService.register();
        AchievementService.register();
        Log.info(TAG, "  StatsService / AchievementService registered");

        Log.info(TAG, "CoreBootstrap bootstrap complete — {} systems, {} task sources, {} blueprints",
                world.systemCount(), taskSources.size(), blueprints);
        return world;
    }

    /**
     * Build the engine-layer {@link ResourceShortageHandler}. Delegates to
     * {@link ResourceSupplySystem#enqueueSynthesize} — enqueues a synthesize
     * work item at a workstation when a recipe exists, else falls through to
     * the default gather behavior (driven by {@link ResourceSupplySystem}).
     */
    private static ResourceShortageHandler createShortageHandler(World world) {
        return (resource, amount, location) ->
                ResourceSupplySystem.enqueueSynthesize(resource.id(), amount, world);
    }
}
