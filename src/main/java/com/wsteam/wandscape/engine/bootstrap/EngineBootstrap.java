package com.wsteam.wandscape.engine.bootstrap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.wsteam.wandscape.core.CoreBootstrap;
import com.wsteam.wandscape.Wandscape;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
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
import com.wsteam.wandscape.core.system.WarehouseSource;
import com.wsteam.wandscape.core.system.WorkbenchSource;
import com.wsteam.wandscape.core.task.BlueprintInterpreter;
import com.wsteam.wandscape.core.task.BlueprintRegistry;
import com.wsteam.wandscape.core.task.BuildingTaskPool;
import com.wsteam.wandscape.core.task.TaskState;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.engine.boundary.AsyncTransformExecutor;
import com.wsteam.wandscape.engine.boundary.WandEquipExecutor;
import com.wsteam.wandscape.engine.boundary.WandReturnExecutor;
import com.wsteam.wandscape.engine.boundary.WandscapeBlockInteractExecutor;
import com.wsteam.wandscape.engine.boundary.WandscapeBlockOps;
import com.wsteam.wandscape.engine.boundary.WandscapeEntityOps;
import com.wsteam.wandscape.engine.boundary.WandscapeMovementOps;
import com.wsteam.wandscape.engine.boundary.ResourceRequestExecutor;
import com.wsteam.wandscape.engine.boundary.WandscapeRitualOps;
import com.wsteam.wandscape.engine.system.FailureAnalyzerSystem;
import com.wsteam.wandscape.engine.system.NavigationSystem;
import com.wsteam.wandscape.engine.system.WandProvisionSystem;
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
        taskSources.add(new WarehouseSource(() -> {
            // Only emit ResourceLow when at least one colony has a storage building.
            // Without storage, gathered resources have nowhere to go — skip entirely.
            BuildingApi buildingApi = WandscapeApis.getBuildingApi();
            if (buildingApi == null) return false;
            return !buildingApi.getBuildingsByCategory(null, "storage").isEmpty();
        }));
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
            LOGGER.info("  ColonyResourceAccess: WarehouseManager (live)");
        } else {
            colonyResources = new ColonyResourceAccess() {
                @Override public boolean hasEnough(com.wsteam.wandscape.core.types.ResourceId r, int a) { return true; }
                @Override public boolean reserve(com.wsteam.wandscape.core.types.ResourceId r, int a) { return true; }
                @Override public boolean commit(com.wsteam.wandscape.core.types.ResourceId r, int a) { return true; }
                @Override public void release(com.wsteam.wandscape.core.types.ResourceId r, int a) {}
                @Override public int available(com.wsteam.wandscape.core.types.ResourceId r) { return Integer.MAX_VALUE; }
                @Override public void addResource(com.wsteam.wandscape.core.types.ResourceId r, int a) {}
            };
            LOGGER.info("  ColonyResourceAccess: stub (warehouse not loaded)");
        }

        // 4a. Build wand provider (engine queries warehouse for wand items)
        WandProvisionSystem wandProvider = new WandProvisionSystem(
                Wandscape.WAND_PRESET_LOADER);

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
                wandProvider,
                new com.wsteam.wandscape.core.task.WandLifecycle(),
                new BuildingTaskPool()
        );

        // 6. Bootstrap engine
        World world = CoreBootstrap.bootstrap(config);

        // 6a. Inject core EventBus into WarehouseManager so addResource() emits ResourceFulfilled
        if (colonyResources instanceof com.wsteam.wandscape.warehouse.WarehouseManager wm) {
            wm.setEventBus(world.eventBus);
            LOGGER.info("  WarehouseManager EventBus injected");
        }

        // 6b. Wire ritualOps into the world (after bootstrap so world exists)
        world.ritualOps = ritualOps;

        // 6b. Create EventDrivenTaskSource (event → gather tasks) with synthesize handler
        EventDrivenTaskSource eventSource = new EventDrivenTaskSource(
                world.taskPool, world.eventBus, () -> GridPos.ORIGIN);
        eventSource.setResourceShortageHandler(createShortageHandler(world));
        eventSource.setGatherEnabled(false); // gather tasks disabled for early-access
        LOGGER.info("  EventDrivenTaskSource wired (gather=OFF)");

        // 6c. Load persisted thresholds from ColonyItemBank into WarehouseSource
        WarehouseSource warehouseSource = WarehouseSource.getActive();
        if (warehouseSource != null && colonyResources instanceof com.wsteam.wandscape.warehouse.WarehouseManager wm) {
            var server = ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                var bank = com.wsteam.wandscape.warehouse.ColonyItemBank.get(server.overworld());
                for (UUID colonyId : bank.getColonyIds()) {
                    var thresh = bank.getAllThresholds(colonyId);
                    for (var entry : thresh.entrySet()) {
                        warehouseSource.setThreshold(
                                new com.wsteam.wandscape.core.types.ResourceId(entry.getKey()),
                                entry.getValue().intValue());
                    }
                }
                LOGGER.info("  Loaded {} colony thresholds into WarehouseSource", bank.getColonyIds().size());
            }
        }

        // 7. Register default op executors
        DefaultOpExecutors.registerAll(world.opExecutors);

        // 8. Register NavigationSystem (drives all NPC movement via NavigationState)
        NavigationSystem navSystem = new NavigationSystem();
        world.addSystem(navSystem);

        // 8b. Register FailureAnalyzerSystem (monitors FAILED tasks, auto-recovers)
        FailureAnalyzerSystem failureAnalyzer = new FailureAnalyzerSystem(
                Wandscape.WAND_PRESET_LOADER);
        world.addSystem(failureAnalyzer);
        LOGGER.info("  FailureAnalyzerSystem registered");

        // 9. Override TransformOp executor with async version (V2.5 gating demo)
        //    Set to 0 for sync (no gating), >0 for N-tick delay per block.
        int asyncDelay = 1; // 5 MC tick delay per TransformOp
        if (asyncDelay > 0) {
            AsyncTransformExecutor asyncExec = new AsyncTransformExecutor(asyncDelay);
            world.opExecutors.register(asyncExec); // overwrites default TransformExecutor
            WandscapeEngine.setAsyncExecutor(asyncExec);
            LOGGER.info("  AsyncTransformExecutor active: {} tick delay per block", asyncDelay);
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
        LOGGER.info("  WandscapeBlockInteractExecutor active (sync + async actions + transport)");

        // 9d. Register wand equip/return executors
        //     NPCs fetch wands from warehouse before executing tasks
        //     that require specific wand capabilities, and return them after.
        //     WandEquipExecutor uses the transporter for visual wand delivery.
        world.opExecutors.register(new WandEquipExecutor(Wandscape.WAND_PRESET_LOADER, transporter));
        world.opExecutors.register(new WandReturnExecutor(Wandscape.WAND_PRESET_LOADER, transporter));
        LOGGER.info("  WandEquipExecutor + WandReturnExecutor registered");

        // 9e. Register resource request executor (replaces inline handling)
        //     Uses the transporter for visual item delivery from warehouse to NPC.
        ResourceRequestExecutor resourceReqExec = new ResourceRequestExecutor(transporter);
        world.opExecutors.register(resourceReqExec);
        WandscapeEngine.setResourceRequestExec(resourceReqExec);
        LOGGER.info("  ResourceRequestExecutor registered (visual transport, staggered)");

        // 10. Publish boundary services
        WandscapeEngine.setMovementOps(movementOps);

        // 11. Store world in singleton
        WandscapeEngine.setWorld(world);

        LOGGER.info("CoreBootstrap bootstrap complete — {} systems, {} task sources, {} blueprints",
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

            LOGGER.info("[EngineBootstrap] shortage {} x{} → enqueued synthesize:{} at station {}",
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
