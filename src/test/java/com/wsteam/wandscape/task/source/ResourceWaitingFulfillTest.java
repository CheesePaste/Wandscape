package com.wsteam.wandscape.task.source;

import com.wsteam.wandscape.core.boundary.MockBoundary;
import com.wsteam.wandscape.core.CoreBootstrap;
import com.wsteam.wandscape.core.CoreBootstrapConfig;
import com.wsteam.wandscape.core.types.BlockType;
import com.wsteam.wandscape.core.types.GridPos;
import com.wsteam.wandscape.core.types.ResourceId;
import com.wsteam.wandscape.core.types.ResourceStack;
import com.wsteam.wandscape.core.types.NpcAttributes;
import com.wsteam.wandscape.content.task.engine.dsl.BlueprintRegistry;
import com.wsteam.wandscape.content.task.engine.dsl.BlueprintSteps;
import com.wsteam.wandscape.core.component.Inventory;
import com.wsteam.wandscape.core.component.TaskExecutor;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.wsteam.wandscape.content.task.engine.pool.GlobalTask;
import com.wsteam.wandscape.content.task.engine.pool.TaskRequest;
import com.wsteam.wandscape.content.task.runtime.ExecutorState;
import com.wsteam.wandscape.content.task.runtime.TaskSequence;
import com.wsteam.wandscape.content.task.runtime.TaskState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.content.task.op.api.AtomicOp;
import com.wsteam.wandscape.content.task.op.executor.DefaultOpExecutors;
import com.wsteam.wandscape.content.task.source.PlayerManualSource;
import com.wsteam.wandscape.content.task.scheduler.SchedulerSystem;
import com.wsteam.wandscape.content.task.scheduler.SystemBlueprintRegistry;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the full "resource shortage → AWAITING_RESOURCES →
 * resource added wake-up → resume and complete" cycle.
 */
public class ResourceWaitingFulfillTest {

    private MockBoundary mock;
    private World world;
    private PlayerManualSource manualSource;
    private SchedulerSystem scheduler;
    private UUID colonyId;
    private long builderNpc;

    @BeforeEach
    void setUp() {
        mock = new MockBoundary();
        mock.seedWarehouse(ResourceId.STONE_BRICKS, 5);
        mock.seedWarehouse(ResourceId.WOOD, 50);

        BlueprintRegistry blueprints = new BlueprintRegistry();
        registerBlueprints(blueprints);

        CoreBootstrapConfig config = new CoreBootstrapConfig(
                mock, mock, mock, null, mock,
                List.of(), blueprints,
                new SystemBlueprintRegistry(),
                false
        );

        world = CoreBootstrap.bootstrap(config);
        DefaultOpExecutors.registerAll(world.opExecutors);
        manualSource = new PlayerManualSource(world.taskPool);

        scheduler = new SchedulerSystem(2);

        colonyId = UUID.randomUUID();
        GridPos center = new GridPos(0, 64, 0);
        CoreBootstrap.createColony(world, center.x(), center.y(), center.z(), 50);

        builderNpc = CoreBootstrap.createNpc(world, 0, 64, 0, colonyId, NpcAttributes.defaults());
    }

    @Test
    void resourceShortage_pausesTaskAndReleasesNpc() {
        GridPos loc = new GridPos(10, 64, 10);
        TaskRequest req = makeRequest("build:resource_heavy", loc, 10);
        long taskId = manualSource.publish(req);

        tickN(3);

        GlobalTask task = world.taskPool.get(taskId);
        assertNotNull(task);
        assertEquals(TaskState.AWAITING_RESOURCES, task.state,
                "Task should enter AWAITING_RESOURCES when warehouse has insufficient stock");
        assertNotNull(task.awaitingResource, "Task should record which resource it needs");
        assertFalse(task.awaitingResource.isEmpty());
        assertEquals(ResourceId.STONE_BRICKS, task.awaitingResource.get(0).resource());
        assertEquals(10, task.awaitingResource.get(0).amount());
        assertEquals(0, task.stepIndex,
                "stepIndex preserved at ResourceRequestOp position (step 0)");

        assertNull(task.assignedNpcId, "NPC should be released from waiting task");

        TaskExecutor exec = world.get(builderNpc, TaskExecutor.class);
        assertNotNull(exec);
        assertNull(exec.globalTaskId, "NPC global task should be cleared");
        assertEquals(ExecutorState.IDLE, exec.state, "NPC should return to idle");

        Inventory inv = world.get(builderNpc, Inventory.class);
        assertNotNull(inv);
        assertEquals(0, inv.count(ResourceId.STONE_BRICKS));
    }

    @Test
    void resourceFulfilled_wakesTask_andCompletesIt() {
        GridPos loc = new GridPos(10, 64, 10);
        TaskRequest req = makeRequest("build:resource_heavy", loc, 10);
        long taskId = manualSource.publish(req);

        tickN(3);

        GlobalTask task = world.taskPool.get(taskId);
        assertEquals(TaskState.AWAITING_RESOURCES, task.state);

        mock.seedWarehouse(ResourceId.STONE_BRICKS, 50);
        world.taskPool.onResourceAdded(ResourceId.STONE_BRICKS, 50);

        // Wake-up is synchronous — no tick needed
        assertEquals(TaskState.PENDING_ASSIGN, task.state,
                "Task should wake to PENDING_ASSIGN after resource added");
        assertNull(task.awaitingResource, "awaitingResource should be cleared on wake");

        tickN(10);

        assertEquals(TaskState.COMPLETED, task.state,
                "Task should complete after resources are fulfilled");
        assertEquals(2, task.stepIndex,
                "Should have executed all 2 steps (ResourceRequestOp + 1 TransformOp)");

        Inventory inv = world.get(builderNpc, Inventory.class);
        assertNotNull(inv);
        assertEquals(0, inv.count(ResourceId.STONE_BRICKS),
                "Materials are charged at construction start; they never enter the NPC backpack");

        assertEquals(45, mock.available(ResourceId.STONE_BRICKS));
    }

    @Test
    void taskWithNoResourceShortage_completesDirectly() {
        GridPos loc = new GridPos(20, 64, 20);
        TaskRequest req = makeRequest("build:simple_tower", loc, 10);
        long taskId = manualSource.publish(req);

        tickN(10);

        GlobalTask task = world.taskPool.get(taskId);
        assertEquals(TaskState.COMPLETED, task.state,
                "Task without resource requirements should complete directly");
        assertEquals(3, task.stepIndex, "3 TransformOp steps");
    }

    @Test
    void resourcePartlyAvailable_fulfilledLater() {
        GridPos loc = new GridPos(10, 64, 10);
        TaskRequest req = makeRequest("build:resource_heavy", loc, 10);
        long taskId = manualSource.publish(req);

        tickN(3);
        GlobalTask task = world.taskPool.get(taskId);
        assertEquals(TaskState.AWAITING_RESOURCES, task.state);

        // Partial fulfillment: +3 → total 8, still below 10
        mock.seedWarehouse(ResourceId.STONE_BRICKS, 3);
        world.taskPool.onResourceAdded(ResourceId.STONE_BRICKS, 3);

        assertEquals(TaskState.AWAITING_RESOURCES, task.state,
                "Task should stay AWAITING_RESOURCES when partial fill is insufficient");

        // Full fulfillment: +20 → total 28, well above 10
        mock.seedWarehouse(ResourceId.STONE_BRICKS, 20);
        world.taskPool.onResourceAdded(ResourceId.STONE_BRICKS, 20);

        assertEquals(TaskState.PENDING_ASSIGN, task.state,
                "Task should wake once sufficient resources available");

        tickN(10);
        assertEquals(TaskState.COMPLETED, task.state);
    }

    // ---- Helpers ----

    private void tickN(int n) {
        for (int i = 0; i < n; i++) {
            world.tick(1.0f);
        }
    }

    private static TaskRequest makeRequest(String blueprintId, GridPos pos, int priority) {
        Map<String, JsonElement> params = new HashMap<>();
        params.put("x", new JsonPrimitive(pos.x()));
        params.put("y", new JsonPrimitive(pos.y()));
        params.put("z", new JsonPrimitive(pos.z()));
        return new TaskRequest(blueprintId, params, priority, null);
    }

    private void registerBlueprints(BlueprintRegistry registry) {
        registry.register("build:resource_heavy", (BlueprintSteps) p -> {
            GridPos loc = parseLocation(p);
            return TaskSequence.of("Resource Heavy Build",
                    new AtomicOp.ResourceRequestOp(List.of(
                            new ResourceStack(ResourceId.STONE_BRICKS, 10))),
                    AtomicOp.TransformOp.place(loc, BlockType.STONE_BRICKS)
            );
        });

        registry.register("build:simple_tower", (BlueprintSteps) p -> {
            GridPos loc = parseLocation(p);
            return TaskSequence.of("Simple Tower",
                    AtomicOp.TransformOp.place(loc, BlockType.STONE),
                    AtomicOp.TransformOp.place(loc.add(0, 1, 0), BlockType.STONE),
                    AtomicOp.TransformOp.place(loc.add(0, 2, 0), BlockType.STONE)
            );
        });
    }

    private static GridPos parseLocation(Map<String, JsonElement> params) {
        try {
            int x = params.containsKey("x") ? params.get("x").getAsInt() : 0;
            int y = params.containsKey("y") ? params.get("y").getAsInt() : 0;
            int z = params.containsKey("z") ? params.get("z").getAsInt() : 0;
            return new GridPos(x, y, z);
        } catch (NumberFormatException e) {
            return GridPos.ORIGIN;
        }
    }
}
