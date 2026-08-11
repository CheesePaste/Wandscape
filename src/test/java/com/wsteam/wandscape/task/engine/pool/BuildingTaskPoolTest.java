package com.wsteam.wandscape.task.engine.pool;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.wsteam.wandscape.core.CoreBootstrap;
import com.wsteam.wandscape.core.CoreBootstrapConfig;
import com.wsteam.wandscape.core.boundary.MockBoundary;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.shared.data.WorkItem;
import com.wsteam.wandscape.task.engine.dsl.BlueprintRegistry;
import com.wsteam.wandscape.task.runtime.TaskSequence;
import com.wsteam.wandscape.task.runtime.TaskState;
import com.wsteam.wandscape.task.scheduler.SystemBlueprintRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BuildingTaskPool parked-head handling — a head that goes AWAITING_RESOURCES
 * (element shortage during synthesis / craft) must not block the building's
 * queue: the next WorkItem is promoted, and the parked task is pruned once it
 * completes.
 */
class BuildingTaskPoolTest {

    private static final String SYNTH = "production:synthesize";

    private World world;

    @BeforeEach
    void setUp() {
        MockBoundary mock = new MockBoundary();
        BlueprintRegistry blueprints = new BlueprintRegistry();
        blueprints.register(SYNTH, params -> new TaskSequence(List.of(), "synthesize"));
        CoreBootstrapConfig config = new CoreBootstrapConfig(
                mock, mock, mock, null, mock, List.of(), blueprints,
                new SystemBlueprintRegistry(), false);
        world = CoreBootstrap.bootstrap(config);
    }

    private static WorkItem synthWork(String recipeId) {
        Map<String, JsonElement> params = new HashMap<>();
        params.put("recipe_id", new JsonPrimitive(recipeId));
        params.put("count", new JsonPrimitive(1));
        return new WorkItem(SYNTH, params, 10);
    }

    @Test
    void parkedHead_freesHeadSlotAndLetsNextWorkItemPublish() {
        BuildingTaskPool btp = world.buildingTaskPool;
        GlobalTaskPool pool = world.taskPool;
        UUID buildingId = UUID.randomUUID();

        long headId = btp.enqueue(buildingId, synthWork("recipeA"), pool);
        assertTrue(headId >= 0, "first WorkItem becomes the head");
        assertTrue(btp.hasHead(buildingId));
        assertEquals(0, btp.getPendingCount(buildingId));

        // Head hits an element shortage → AWAITING_RESOURCES (as markAwaitingResources does)
        pool.get(headId).state = TaskState.AWAITING_RESOURCES;

        btp.parkHead(buildingId, headId);
        assertFalse(btp.hasHead(buildingId), "parked head must stop blocking the queue");
        assertTrue(btp.hasParked(buildingId), "parked task should be tracked");

        // Next WorkItem (craftable) can now be published as the new head
        long nextId = btp.enqueue(buildingId, synthWork("recipeB"), pool);
        assertTrue(nextId >= 0, "second WorkItem should publish while the first is parked");
        assertNotEquals(headId, nextId);
        assertTrue(btp.hasHead(buildingId));

        // Parked task stays in the pool (resumes on its own) alongside the new head
        assertEquals(TaskState.AWAITING_RESOURCES, pool.get(headId).state);
        assertEquals(TaskState.PENDING_ASSIGN, pool.get(nextId).state);
    }

    @Test
    void pruneParked_removesOnlyCompletedParkedTasks() {
        BuildingTaskPool btp = world.buildingTaskPool;
        GlobalTaskPool pool = world.taskPool;
        UUID buildingId = UUID.randomUUID();

        long a = btp.enqueue(buildingId, synthWork("recipeA"), pool);
        pool.get(a).state = TaskState.AWAITING_RESOURCES;
        btp.parkHead(buildingId, a);

        long b = btp.enqueue(buildingId, synthWork("recipeB"), pool);
        pool.get(b).state = TaskState.AWAITING_RESOURCES;
        btp.parkHead(buildingId, b);

        assertEquals(2, btp.getParkedTaskIds(buildingId).size());

        // Task A resumed and completed; B is still waiting for elements
        pool.get(a).state = TaskState.COMPLETED;
        btp.pruneParked(buildingId, pool);

        assertTrue(btp.hasParked(buildingId), "waiting task must remain parked");
        assertFalse(btp.getParkedTaskIds(buildingId).contains(a),
                "completed parked task should be pruned");
        assertTrue(btp.getParkedTaskIds(buildingId).contains(b),
                "still-waiting parked task must be kept");
    }

    @Test
    void parkedHead_completingViaWakeup_keepsOtherWorkFlow() {
        BuildingTaskPool btp = world.buildingTaskPool;
        GlobalTaskPool pool = world.taskPool;
        UUID buildingId = UUID.randomUUID();

        long a = btp.enqueue(buildingId, synthWork("recipeA"), pool);
        pool.get(a).state = TaskState.AWAITING_RESOURCES;
        btp.parkHead(buildingId, a);

        // Elements arrive → wake-up returns the parked task to PENDING_ASSIGN.
        // It is no longer parked but must not be pruned while it still runs.
        pool.get(a).state = TaskState.PENDING_ASSIGN;
        btp.pruneParked(buildingId, pool);

        assertTrue(btp.getParkedTaskIds(buildingId).contains(a),
                "woken-but-unfinished parked task must keep holding the lease");
        assertFalse(btp.hasHead(buildingId));

        // It finishes → now pruned
        pool.get(a).state = TaskState.COMPLETED;
        btp.pruneParked(buildingId, pool);
        assertFalse(btp.hasParked(buildingId));
    }
}
