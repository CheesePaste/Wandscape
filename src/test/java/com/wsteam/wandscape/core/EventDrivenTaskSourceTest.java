package com.wsteam.wandscape.core;

import com.wsteam.wandscape.core.component.WandCarrier;
import com.wsteam.wandscape.core.task.*;
import com.wsteam.wandscape.core.types.*;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.wsteam.wandscape.core.ecs.World;
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
        new EventDrivenTaskSource(world.taskPool, world.eventBus);

        colonyId = UUID.randomUUID();
        CoreBootstrap.createColony(world, colonyCenter.x(), colonyCenter.y(), colonyCenter.z(), 50);

        Map<BehaviourTag, BehaviourLevel> caps = Map.of(
                BehaviourTag.BUILDING, new BehaviourLevel(2),
                BehaviourTag.RITUAL, new BehaviourLevel(1));
        WandCarrier wand = new WandCarrier(caps, 0.8f, 3);
        npc = CoreBootstrap.createNpc(world, 0, 64, 0, wand, colonyId, 100, 5);
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
