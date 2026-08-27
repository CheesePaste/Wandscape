package com.wsteam.wandscape.engine.system;

import java.util.List;
import java.util.Map;

import com.wsteam.wandscape.core.CoreBootstrap;
import com.wsteam.wandscape.core.CoreBootstrapConfig;
import com.wsteam.wandscape.core.boundary.MockBoundary;
import com.wsteam.wandscape.core.boundary.MovementOps;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.types.ResourceId;
import com.wsteam.wandscape.core.types.ResourceStack;
import com.wsteam.wandscape.task.engine.dsl.BlueprintRegistry;
import com.wsteam.wandscape.task.engine.pool.GlobalTask;
import com.wsteam.wandscape.task.engine.pool.TaskRequest;
import com.wsteam.wandscape.task.runtime.TaskSequence;
import com.wsteam.wandscape.task.runtime.TaskState;
import com.wsteam.wandscape.task.scheduler.SystemBlueprintRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class DecomposeNoAutoSupplyTest {

    private World world;
    private ResourceSupplySystem system;

    @BeforeEach
    void setUp() {
        MockBoundary mock = new MockBoundary();
        BlueprintRegistry blueprints = new BlueprintRegistry();
        blueprints.register("production:decompose", params -> new TaskSequence(List.of(), "decompose"));
        blueprints.register("production:synthesize", params -> new TaskSequence(List.of(), "synthesize"));

        MovementOps noopMov = new MovementOps() {
            @Override
            public java.util.concurrent.CompletableFuture<Void> navigateTo(long npcId, int x, int y, int z) {
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }

            @Override
            public void cancelNavigation(long npcId) {}
        };

        CoreBootstrapConfig config = new CoreBootstrapConfig(
                mock, mock, mock, noopMov, mock, List.of(), blueprints,
                new SystemBlueprintRegistry(), false);
        world = CoreBootstrap.bootstrap(config);
        system = new ResourceSupplySystem();
    }

    @Test
    void scanStuckTasks_ignoresDecomposeTasks_doesNotTriggerAutoSynthesize() {
        // Create a decompose task and manually set state to AWAITING_RESOURCES
        long taskId = world.taskPool.addTask(new TaskRequest("production:decompose", Map.of(), 50, null));
        GlobalTask task = world.taskPool.get(taskId);
        task.state = TaskState.AWAITING_RESOURCES;
        task.awaitingResource = List.of(new ResourceStack(new ResourceId("oak_log"), 64));

        assertEquals(TaskState.AWAITING_RESOURCES, task.state);

        // Run 40 ticks of ResourceSupplySystem
        for (int i = 0; i < 40; i++) {
            system.update(world, 0.05f);
        }

        // The decompose task should be woken up and NOT generate synthesize tasks
        GlobalTask after = world.taskPool.get(taskId);
        assertEquals(TaskState.PENDING_ASSIGN, after.state);

        // Ensure no new synthesize tasks were injected into task pool
        for (GlobalTask t : world.taskPool.all()) {
            assertNotEquals("production:synthesize", t.blueprintId);
        }
    }
}
