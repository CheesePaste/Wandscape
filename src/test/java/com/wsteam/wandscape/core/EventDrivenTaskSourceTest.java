package com.wsteam.wandscape.core;

import com.wsteam.wandscape.core.component.WandCarrier;
import com.wsteam.wandscape.core.task.*;
import com.wsteam.wandscape.core.types.*;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.event.MobNearby;
import com.wsteam.wandscape.core.event.ResourceFulfilled;
import com.wsteam.wandscape.core.event.ResourceLow;
import com.wsteam.wandscape.core.event.TaskAwaitingResources;
import com.wsteam.wandscape.core.op.AtomicOp;
import com.wsteam.wandscape.core.op.DefaultOpExecutors;
import com.wsteam.wandscape.core.system.EventDrivenTaskSource;
import com.wsteam.wandscape.core.system.SystemBlueprintRegistry;
import com.wsteam.wandscape.core.demo.MockBoundary;

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
        new EventDrivenTaskSource(world.taskPool, world.eventBus, () -> colonyCenter);

        colonyId = UUID.randomUUID();
        CoreBootstrap.createColony(world, colonyCenter.x(), colonyCenter.y(), colonyCenter.z(), 50);

        Map<BehaviourTag, BehaviourLevel> caps = Map.of(
                BehaviourTag.BUILDING, new BehaviourLevel(2),
                BehaviourTag.RITUAL, new BehaviourLevel(1));
        WandCarrier wand = new WandCarrier(caps, 0.8f, 3);
        npc = CoreBootstrap.createNpc(world, 0, 64, 0, wand, colonyId, 100, 5);
    }

    // ======================== ResourceLow → gather ========================

    @Test
    void resourceLow_createsGatherTask() {
        world.eventBus.emit(new ResourceLow(ResourceId.STONE, 30, 128));
        tickN(10);

        GlobalTask gatherTask = findGatherTask(ResourceId.STONE);
        assertNotNull(gatherTask, "EventDrivenTaskSource should create gather:stone on ResourceLow");
        assertTrue(
                gatherTask.state == TaskState.COMPLETED
                        || gatherTask.state == TaskState.PENDING_ASSIGN
                        || gatherTask.state == TaskState.IN_PROGRESS
                        || gatherTask.state == TaskState.AWAITING_RESOURCES,
                "Gather task exists, actual state=" + gatherTask.state);
    }

    @Test
    void resourceLow_suppressesDuplicateWithinCooldown() {
        world.eventBus.emit(new ResourceLow(ResourceId.STONE, 30, 128));
        tickN(1); // dispatch
        int before = countGatherTasks(ResourceId.STONE);
        assertTrue(before > 0, "First ResourceLow should create at least one gather task");

        // Second emit within cooldown → suppressed
        world.eventBus.emit(new ResourceLow(ResourceId.STONE, 30, 128));
        tickN(3);

        assertEquals(before, countGatherTasks(ResourceId.STONE),
                "Duplicate ResourceLow within cooldown should be suppressed");
    }

    // ======================== TaskAwaitingResources → high-prio gather ========================

    @Test
    void taskAwaitingResources_createsHighPriorityGather() {
        world.eventBus.emit(new TaskAwaitingResources(999,
                new ResourceStack(ResourceId.STONE_BRICKS, 64)));
        tickN(5);

        GlobalTask found = findGatherTask(ResourceId.STONE_BRICKS);
        assertNotNull(found, "Should create gather:stone_bricks on TaskAwaitingResources");
        assertEquals(40, found.priority,
                "TaskAwaitingResources should create priority=40 gather, was " + found.priority);
    }

    // ======================== MobNearby → ritual:defense ========================

    @Test
    void mobNearby_createsDefenseRitual() {
        world.eventBus.emit(new MobNearby(new GridPos(10, 64, 10), 3));
        tickN(5);

        GlobalTask ritualTask = findByLabel("Defense Warding");
        assertNotNull(ritualTask, "MobNearby should create ritual:defense");
        assertTrue(ritualTask.priority >= 53,
                "priority=" + ritualTask.priority + " expected >= 53 (50 + mobCount)");
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
                        new AtomicOp.ResourceRequestOp(new ResourceStack(ResourceId.STONE, 100)),
                        AtomicOp.TransformOp.place(parseLocation(p), BlockType.STONE_BRICKS)));

        long taskId = world.taskPool.addTask(
                new TaskRequest("test:heavy", taskParams, 10));
        tickN(6);

        assertEquals(TaskState.AWAITING_RESOURCES, world.taskPool.get(taskId).state,
                "Heavy task should AWAIT (30 < 100)");

        // Emit ResourceLow → EventDrivenTaskSource creates gather:stone
        world.eventBus.emit(new ResourceLow(ResourceId.STONE, 30, 128));
        tickN(5);

        // Simulate real gathering: seed + fulfill.
        mock.seedWarehouse(ResourceId.STONE, 200);
        world.eventBus.emit(new ResourceFulfilled(ResourceId.STONE, 200));
        tickN(12);

        assertEquals(TaskState.COMPLETED, world.taskPool.get(taskId).state,
                "Heavy task should complete after ResourceFulfilled");
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

    private int countGatherTasks(ResourceId resource) {
        String prefix = "Gather " + resource.id();
        int count = 0;
        for (GlobalTask t : world.taskPool.all()) {
            if (t.sequence.label().contains(prefix)) count++;
        }
        return count;
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
