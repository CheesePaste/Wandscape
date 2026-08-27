package com.wsteam.wandscape.task.engine.pool;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.wsteam.wandscape.core.CoreBootstrap;
import com.wsteam.wandscape.core.CoreBootstrapConfig;
import com.wsteam.wandscape.core.boundary.MockBoundary;
import com.wsteam.wandscape.core.boundary.MovementOps;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.task.engine.dsl.BlueprintRegistry;
import com.wsteam.wandscape.task.runtime.TaskSequence;
import com.wsteam.wandscape.task.runtime.TaskState;
import com.wsteam.wandscape.task.scheduler.SystemBlueprintRegistry;

import static org.junit.jupiter.api.Assertions.*;

class GlobalTaskPriorityUpdateTest {

    private World world;

    @BeforeEach
    void setUp() {
        MockBoundary mock = new MockBoundary();
        BlueprintRegistry blueprints = new BlueprintRegistry();
        blueprints.register("test:bp", params -> new TaskSequence(List.of(), "test_task"));
        MovementOps noopMov = new MovementOps() {
            @Override
            public CompletableFuture<Void> navigateTo(long npcId, int x, int y, int z) {
                return CompletableFuture.completedFuture(null);
            }
            @Override
            public void cancelNavigation(long npcId) {}
        };
        CoreBootstrapConfig config = new CoreBootstrapConfig(
                mock, mock, mock, noopMov, mock, List.of(), blueprints,
                new SystemBlueprintRegistry(), true);
        world = CoreBootstrap.bootstrap(config);
    }

    @Test
    void testUpdatePriorityReordersAssignableTasks() {
        TaskRequest lowReq = new TaskRequest("test:bp", Map.of(), 10, null);
        TaskRequest midReq = new TaskRequest("test:bp", Map.of(), 50, null);

        long lowId = world.taskPool.addTask(lowReq);
        long midId = world.taskPool.addTask(midReq);

        // Before update: midId comes first
        List<GlobalTask> assignable = world.taskPool.getAssignableTasks();
        assertEquals(2, assignable.size());
        assertEquals(midId, assignable.get(0).id);
        assertEquals(lowId, assignable.get(1).id);

        // Rush low priority task to 100
        boolean updated = world.taskPool.updatePriority(lowId, 100);
        assertTrue(updated);

        // After update: lowId (now p100) comes first
        assignable = world.taskPool.getAssignableTasks();
        assertEquals(lowId, assignable.get(0).id);
        assertEquals(100, assignable.get(0).priority);
        assertEquals(midId, assignable.get(1).id);
    }
}
