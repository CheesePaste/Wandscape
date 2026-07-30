package com.wsteam.wandscape.task.source;

import com.wsteam.wandscape.core.boundary.MockBoundary;
import com.wsteam.wandscape.core.CoreBootstrap;
import com.wsteam.wandscape.core.CoreBootstrapConfig;
import com.wsteam.wandscape.task.engine.pool.GlobalTask;
import com.wsteam.wandscape.task.engine.pool.TaskRequest;
import com.wsteam.wandscape.task.runtime.TaskSequence;
import com.wsteam.wandscape.task.runtime.TaskState;
import com.wsteam.wandscape.core.types.BlockType;
import com.wsteam.wandscape.core.types.GridPos;
import com.wsteam.wandscape.core.types.ResourceId;
import com.wsteam.wandscape.core.types.ResourceStack;
import com.wsteam.wandscape.task.engine.dsl.BlueprintRegistry;
import com.wsteam.wandscape.task.engine.dsl.BlueprintSteps;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.op.api.AtomicOp;
import com.wsteam.wandscape.op.executor.DefaultOpExecutors;
import com.wsteam.wandscape.task.source.EventDrivenTaskSource;
import com.wsteam.wandscape.task.scheduler.SystemBlueprintRegistry;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests EventDrivenTaskSource: event → TaskRequest → addTask → scheduler → complete.
 */
public class EventDrivenTaskSourceTest {

    private MockBoundary mock;
    private World world;
    private UUID colonyId;
    private long npc;

    @BeforeEach
    void setUp() {
        mock = new MockBoundary();
        mock.seedWarehouse(ResourceId.STONE, 30);
        mock.seedWarehouse(ResourceId.WOOD, 200);
        mock.seedWarehouse(ResourceId.STONE_BRICKS, 200);

        BlueprintRegistry blueprints = new BlueprintRegistry();
        EventDrivenTaskSource.registerDefaultBlueprints(blueprints);

        CoreBootstrapConfig config = new CoreBootstrapConfig(mock, mock, mock, null, mock, List.of(), blueprints,
                new SystemBlueprintRegistry(), false);
        world = CoreBootstrap.bootstrap(config);
        DefaultOpExecutors.registerAll(world.opExecutors);

        GridPos colonyCenter = new GridPos(0, 64, 0);
        new EventDrivenTaskSource(world.taskPool, world.eventBus);

        colonyId = UUID.randomUUID();
        CoreBootstrap.createColony(world, colonyCenter.x(), colonyCenter.y(), colonyCenter.z(), 50);

        npc = CoreBootstrap.createNpc(world, 0, 64, 0, colonyId, 100, 5);
    }

    // ======================== Full restock chain ========================

    @Test
    void fullRestockChain_resolvesWaitingTask() {
        // Heavy task needs 100 stone; warehouse has only 30
        GridPos loc = new GridPos(5, 64, 5);
        Map<String, JsonElement> taskParams = new HashMap<>();
        taskParams.put("x", new JsonPrimitive(loc.x()));
        taskParams.put("y", new JsonPrimitive(loc.y()));
        taskParams.put("z", new JsonPrimitive(loc.z()));

        world.blueprintRegistry.register("test:heavy", (BlueprintSteps) p ->
                TaskSequence.of("Heavy Stone Task",
                        new AtomicOp.ResourceRequestOp(List.of(new ResourceStack(ResourceId.STONE, 100))),
                        AtomicOp.TransformOp.place(parseLocation(p), BlockType.STONE_BRICKS)));

        long taskId = world.taskPool.addTask(
                new TaskRequest("test:heavy", taskParams, 10));
        tickN(6);

        assertEquals(TaskState.AWAITING_RESOURCES, world.taskPool.get(taskId).state,
                "Heavy task should AWAIT (30 < 100)");

        // Simulate warehouse restock to wake the AWAITING_RESOURCES task
        mock.seedWarehouse(ResourceId.STONE, 200);
        world.taskPool.onResourceAdded(ResourceId.STONE, 200);
        tickN(12);

        assertEquals(TaskState.COMPLETED, world.taskPool.get(taskId).state,
                "Heavy task should complete after resource added");
    }

    // ---- helpers ----

    private void tickN(int n) {
        for (int i = 0; i < n; i++) world.tick(1.0f);
    }

    private GlobalTask findGatherTask(ResourceId resource) {
        String prefix = "Gather " + resource.id();
        return findByLabel(prefix);
    }

    private GlobalTask findByLabel(String substring) {
        for (GlobalTask t : world.taskPool.all()) {
            if (t.sequence.label().contains(substring)) return t;
        }
        return null;
    }

    private static GridPos parseLocation(Map<String, JsonElement> params) {
        try {
            int x = params.containsKey("x") ? params.get("x").getAsInt() : 0;
            int y = params.containsKey("y") ? params.get("y").getAsInt() : 0;
            int z = params.containsKey("z") ? params.get("z").getAsInt() : 0;
            return new GridPos(x, y, z);
        } catch (NumberFormatException | IllegalStateException e) {
            return GridPos.ORIGIN;
        }
    }
}
