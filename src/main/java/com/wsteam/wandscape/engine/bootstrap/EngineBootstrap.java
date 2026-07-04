package com.wsteam.wandscape.engine.bootstrap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.wsteam.wandscape.core.CoreBootstrap;
import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.core.CoreBootstrapConfig;
import com.wsteam.wandscape.core.boundary.ColonyResourceAccess;
import com.wsteam.wandscape.core.boundary.ResourceShortageHandler;
import com.wsteam.wandscape.core.types.GridPos;
import com.wsteam.wandscape.core.types.ResourceId;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.op.DefaultOpExecutors;
import com.wsteam.wandscape.core.system.EventDrivenTaskSource;
import com.wsteam.wandscape.core.system.SystemBlueprintRegistry;
import com.wsteam.wandscape.core.system.TaskSource;
import com.wsteam.wandscape.core.system.WorkbenchSource;
import com.wsteam.wandscape.core.task.BlueprintInterpreter;
import com.wsteam.wandscape.core.task.BlueprintRegistry;
import com.wsteam.wandscape.core.task.BuildingTaskPool;
import com.wsteam.wandscape.core.task.TaskState;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.engine.boundary.AsyncTransformExecutor;
import com.wsteam.wandscape.engine.boundary.WandscapeBlockInteractExecutor;
import com.wsteam.wandscape.engine.boundary.WandscapeBlockOps;
import com.wsteam.wandscape.engine.boundary.WandscapeEntityOps;
import com.wsteam.wandscape.engine.boundary.WandscapeMovementOps;
import com.wsteam.wandscape.engine.boundary.ResourceRequestExecutor;
import com.wsteam.wandscape.engine.boundary.WandscapeRitualOps;
import com.wsteam.wandscape.engine.system.FailureAnalyzerSystem;
import com.wsteam.wandscape.engine.system.NavigationSystem;
import com.wsteam.wandscape.engine.transport.ItemTransportManager;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.engine.road.RoadTaskSource;
import com.wsteam.wandscape.engine.source.BuildingTaskSource;
import com.wsteam.wandscape.engine.source.blueprint.BlueprintConfigLoader;
import com.wsteam.wandscape.engine.source.blueprint.DataDrivenSteps;
import com.wsteam.wandscape.shared.api.BuildingApi;
import com.wsteam.wandscape.shared.data.WorkItem;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
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
            Log.info(TAG, "  registered {} legacy build blueprints from BuildingConfig JSON (no blueprint ref)",
                    legacyCount);
        }

        EventDrivenTaskSource.registerDefaultBlueprints(blueprints);

        // 2. Build system blueprint registry
        SystemBlueprintRegistry sysBlueprints = new SystemBlueprintRegistry();

        // 3. Build task sources
        List<TaskSource> taskSources = new ArrayList<>();
        taskSources.add(new BuildingTaskSource());
        taskSources.add(new WorkbenchSource());
        taskSources.add(new RoadTaskSource());

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
                @Override public boolean hasEnough(com.wsteam.wandscape.core.types.ResourceId r, int a) { return true; }
                @Override public boolean reserve(com.wsteam.wandscape.core.types.ResourceId r, int a) { return true; }
                @Override public boolean commit(com.wsteam.wandscape.core.types.ResourceId r, int a) { return true; }
                @Override public void release(com.wsteam.wandscape.core.types.ResourceId r, int a) {}
                @Override public int available(com.wsteam.wandscape.core.types.ResourceId r) { return Integer.MAX_VALUE; }
                @Override public void addResource(com.wsteam.wandscape.core.types.ResourceId r, int a) {}
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

        // 8. Register NavigationSystem (drives all NPC movement via NavigationState)
        NavigationSystem navSystem = new NavigationSystem();
        world.addSystem(navSystem);

        // 8b. Register FailureAnalyzerSystem (monitors FAILED tasks, auto-recovers)
        FailureAnalyzerSystem failureAnalyzer = new FailureAnalyzerSystem();
        world.addSystem(failureAnalyzer);
        Log.info(TAG, "  FailureAnalyzerSystem registered");

        // 9. Override TransformOp executor with async version (V2.5 gating demo)
        //    Set to 0 for sync (no gating), >0 for N-tick delay per block.
        int asyncDelay = 1; // 5 MC tick delay per TransformOp
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

        // 9f. Register narrative event subscribers (stats, achievements)
        com.wsteam.wandscape.engine.system.StatsSystem.register();
        com.wsteam.wandscape.engine.system.AchievementSystem.register();
        Log.info(TAG, "  StatsSystem / AchievementSystem registered");

        // 10. Publish boundary services
        WandscapeEngine.setMovementOps(movementOps);

        // 11. Store world in singleton
        WandscapeEngine.setWorld(world);

        Log.info(TAG, "CoreBootstrap bootstrap complete — {} systems, {} task sources, {} blueprints",
                world.systemCount(), taskSources.size(), blueprints);
        return world;
    }

    /**
     * Build the engine-layer {@link ResourceShortageHandler} that checks
     * synthesize recipes before falling back to gather tasks.
     */
    private static ResourceShortageHandler createShortageHandler(World world) {
        return (resource, amount, location) -> {
            // 1. Check if a synthesize recipe exists for this resource
            var recipes = Wandscape.PRODUCTION_RECIPE_LOADER;
            if (recipes == null) return false;
            String recipeKey = stripMcPrefix(resource.id());
            var recipe = recipes.getSynthesizeRecipe(recipeKey);
            if (recipe == null) return false;

            // 2. Check if a synthesize task for this recipe is already in-flight
            if (isSynthesizeInFlight(recipeKey, world)) return false;

            // 3. Find a crafting station
            BuildingApi api;
            try {
                api = WandscapeApis.getBuildingApi();
            } catch (IllegalStateException e) {
                return false;
            }
            List<UUID> stations = api.getBuildingsByCategory(null, "crafting_station");
            if (stations.isEmpty()) return false;

            UUID stationId = stations.get(0);
            var building = api.getBuilding(stationId);
            if (building == null) return false;
            BlockPos stationPos = building.getPosition();

            // 4. Enqueue synthesize work
            Map<String, JsonElement> params = new LinkedHashMap<>();
            params.put("anchor", posToJsonArray(stationPos));
            params.put("recipe_id", new JsonPrimitive(recipeKey));
            params.put("count", new JsonPrimitive(amount));
            params.put("channel_ticks", new JsonPrimitive(200));
            params.put("mana_cost", new JsonPrimitive(5));

            WorkItem work = new WorkItem("production:synthesize", params, 40);
            api.enqueueWork(stationId, work);

            Log.info(TAG, "[EngineBootstrap] shortage {} x{} → enqueued synthesize:{} at station {}",
                    resource, amount, recipeKey, stationId.toString().substring(0, 8));
            return true;
        };
    }

    /** Check if a synthesize task for the given recipe is already active in the pool. */
    private static boolean isSynthesizeInFlight(String recipeId, World world) {
        for (var t : world.taskPool.all()) {
            if (t.state == TaskState.COMPLETED || t.state == TaskState.FAILED) continue;
            if (!"production:synthesize".equals(t.blueprintId)) continue;
            var recipeParam = t.taskParams.get("recipe_id");
            if (recipeParam != null && recipeParam.isJsonPrimitive()
                    && recipeId.equals(recipeParam.getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static JsonArray posToJsonArray(BlockPos pos) {
        JsonArray arr = new JsonArray();
        arr.add(pos.getX());
        arr.add(pos.getY());
        arr.add(pos.getZ());
        return arr;
    }

    /** Strip "minecraft:" prefix from a resource ID for recipe key matching. */
    private static String stripMcPrefix(String id) {
        if (id.startsWith("minecraft:")) {
            return id.substring("minecraft:".length());
        }
        return id;
    }
}
