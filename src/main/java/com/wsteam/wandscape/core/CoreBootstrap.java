package com.wsteam.wandscape.core;

import com.wsteam.wandscape.core.component.NavigationState;
import com.wsteam.wandscape.core.component.*;
import com.wsteam.wandscape.core.ecs.HashMapComponentStore;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.event.SimpleEventBus;
import com.wsteam.wandscape.core.op.OpExecutorRegistry;
import com.wsteam.wandscape.core.system.*;
import com.wsteam.wandscape.core.task.BuildingTaskPool;
import com.wsteam.wandscape.core.task.GlobalTaskPool;
import com.wsteam.wandscape.core.task.WandLifecycle;
import com.wsteam.wandscape.core.types.GridPos;

import java.util.UUID;

/**
 * CoreBootstrap bootstrap.
 * Wires up the ECS world with all component stores, systems, and boundary services.
 *
 * Usage:
 * <pre>
 *   World world = CoreBootstrap.bootstrap(config);
 *   // Create colony, NPCs, publish tasks...
 *   world.tick(delta); // call each frame
 * </pre>
 */
public final class CoreBootstrap {

    private CoreBootstrap() {}

    private static final String TAG = "CoreBootstrap";

    /**
     * Bootstrap a fresh World from the given config.
     */
    public static World bootstrap(CoreBootstrapConfig config) {
        Log.info(TAG, "bootstrap begin");
        World world = new World();

        // 1. Inject boundary services
        world.blockOps = config.blockOps();
        world.entityOps = config.entityOps();
        world.ritualOps = config.ritualOps();
        world.movementOps = config.movementOps();
        world.colonyResources = config.colonyResources();
        world.eventBus = new SimpleEventBus();
        Log.debug(TAG, "boundary services injected");

        // 2. Register component stores
        world.registerComponent(Position.class, new HashMapComponentStore<>());
        world.registerComponent(ManaPool.class, new HashMapComponentStore<>());
        world.registerComponent(WandCarrier.class, new HashMapComponentStore<>());
        world.registerComponent(TaskExecutor.class, new HashMapComponentStore<>());
        world.registerComponent(Inventory.class, new HashMapComponentStore<>());
        world.registerComponent(ColonyMember.class, new HashMapComponentStore<>());
        world.registerComponent(ColonyMetadata.class, new HashMapComponentStore<>());
        world.registerComponent(NavigationState.class, new HashMapComponentStore<>());

        // 3. Set up task compiler
        world.blueprintRegistry = config.blueprints();

        // 4. Create global task pool
        world.taskPool = new GlobalTaskPool(world.eventBus, world.blueprintRegistry, world.colonyResources,
                config.autoApproveTasks());

        // 4.5 Wand lifecycle tracker
        world.wandLifecycle = config.wandLifecycle() != null ? config.wandLifecycle() : new WandLifecycle();

        // 4.6 Building task pool (per-building head tracking)
        world.buildingTaskPool = config.buildingTaskPool() != null ? config.buildingTaskPool() : new BuildingTaskPool();

        // 5. Register op executors
        world.opExecutors = new OpExecutorRegistry();

        // 6. Register system blueprints and wire permanent triggers
        SystemBlueprintRegistry sysBp = config.systemBlueprints();
        sysBp.subscribePermanentTriggers(world.eventBus, world.taskPool);

        // 7. Register systems (in order)
        world.addSystem(new ManaRegenSystem());
        world.addSystem(new SystemBlueprintSystem(sysBp));
        world.addSystem(new TaskSourcePoller(config.taskSources()));
        world.addSystem(new SchedulerSystem(config.wandProvider()));
        world.addSystem(new TaskExecutionSystem(world.taskPool));
        Log.debug(TAG, "%d systems registered", world.systemCount());

        Log.info(TAG, "bootstrap complete - %d component stores, %d systems, %d task sources",
                world.stores().size(), world.systemCount(), config.taskSources().size());
        return world;
    }

    // ---- Convenience: create a fully set up NPC entity ----

    /**
     * Create an NPC entity with all required components for task execution.
     */
    public static long createNpc(World world, int x, int y, int z,
                                  WandCarrier wand, UUID colonyId,
                                  int manaMax, int manaRegen) {
        long entity = world.createEntity();
        world.addComponent(entity, new Position(new GridPos(x, y, z)));
        world.addComponent(entity, new ManaPool(manaMax, manaMax, manaRegen));
        world.addComponent(entity, wand != null ? wand : WandCarrier.EMPTY);
        world.addComponent(entity, new TaskExecutor());
        world.addComponent(entity, new Inventory(27)); // standard 27-slot inventory
        world.addComponent(entity, new ColonyMember(colonyId));
        Log.info(TAG, "createNpc #%d pos=(%d,%d,%d) mana=%d caps=%s colony=%s",
                entity, x, y, z, manaMax, wand != null ? wand.capabilities().keySet() : "none",
                colonyId.toString().substring(0, 8));
        return entity;
    }

    /**
     * Create a colony entity.
     */
    public static long createColony(World world, int centerX, int centerY, int centerZ, int radius) {
        ColonyMetadata meta = ColonyMetadata.create(new GridPos(centerX, centerY, centerZ), radius);
        long entity = world.createEntity();
        world.addComponent(entity, meta);
        Log.info(TAG, "createColony #%d center=(%d,%d,%d) radius=%d id=%s",
                entity, centerX, centerY, centerZ, radius,
                meta.colonyId().toString().substring(0, 8));
        return entity;
    }
}
