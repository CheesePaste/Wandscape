package com.wsteam.wandscape.core.ecs;

import com.wsteam.wandscape.core.boundary.MockBoundary;
import com.wsteam.wandscape.core.CoreBootstrap;
import com.wsteam.wandscape.core.CoreBootstrapConfig;
import com.wsteam.wandscape.core.component.Position;
import com.wsteam.wandscape.core.component.TaskExecutor;
import com.wsteam.wandscape.content.task.runtime.ExecutorState;
import com.wsteam.wandscape.content.task.runtime.TaskSequence;
import com.wsteam.wandscape.core.types.BlockType;
import com.wsteam.wandscape.core.types.NpcAttributes;
import com.wsteam.wandscape.content.task.op.api.AtomicOp;
import com.wsteam.wandscape.content.task.scheduler.SystemBlueprintRegistry;
import com.wsteam.wandscape.content.task.engine.dsl.Blueprint;
import com.wsteam.wandscape.content.task.engine.dsl.BlueprintRegistry;
import com.wsteam.wandscape.content.task.engine.pool.GlobalTask;
import com.wsteam.wandscape.content.task.engine.pool.TaskRequest;
import com.wsteam.wandscape.content.task.runtime.TaskState;
import com.wsteam.wandscape.core.types.GridPos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

/**
 * Tests for World-level ECS operations added in V2.5:
 * {@link World#removeComponent(long, Class)}.
 *
 * <p>Note: EntityComponentBridge and NpcApiImpl are MC-adapter classes that
 * depend on {@code WandscapeNpc} ({@code PathfinderMob} subclass). They cannot
 * be unit-tested without Minecraft runtime; integration tests will use
 * {@code @GameTest} in a future stage.
 */
public class WorldEcsTest {

    @Nested
    class RemoveComponentTests {
        private World world;
        private long entity;

        @BeforeEach
        void setUp() {
            // Bootstrap a minimal world with Position store
            MockBoundary mock = new MockBoundary();
            CoreBootstrapConfig config = new CoreBootstrapConfig(mock, mock, mock, null, mock,
                    java.util.List.of(), new BlueprintRegistry(),
                    new SystemBlueprintRegistry(), false);
            world = CoreBootstrap.bootstrap(config);
            entity = world.createEntity();
        }

        @Test
        void removeComponent_removesFromStore() {
            world.addComponent(entity, Position.of(10, 64, 20));
            assertTrue(world.has(entity, Position.class));

            world.removeComponent(entity, Position.class);
            assertFalse(world.has(entity, Position.class),
                    "Position should be removed");
            assertNull(world.get(entity, Position.class),
                    "get should return null after remove");
        }

        @Test
        void removeComponent_noOpIfEntityDoesNotExist() {
            // Should not throw
            world.removeComponent(99999L, Position.class);
        }

        @Test
        void removeComponent_noOpIfStoreNotRegistered() {
            // GridPos class is not a component — no store for it
            assertDoesNotThrow(() ->
                    world.removeComponent(entity, GridPos.class));
        }

        @Test
        void removeComponent_onlyRemovesSpecifiedType() {
            world.addComponent(entity, Position.of(10, 64, 20));
            world.addComponent(entity, new TaskExecutor());

            world.removeComponent(entity, Position.class);

            assertFalse(world.has(entity, Position.class),
                    "Position should be removed");
            assertTrue(world.has(entity, TaskExecutor.class),
                    "TaskExecutor should still be present");
            assertNotNull(world.get(entity, TaskExecutor.class));
        }

        @Test
        void removeComponent_entityDisappearsFromQuery() {
            world.addComponent(entity, Position.of(10, 64, 20));
            world.addComponent(entity, new TaskExecutor());

            assertTrue(world.query(Position.class, TaskExecutor.class).contains(entity));

            world.removeComponent(entity, Position.class);
            assertFalse(world.query(Position.class, TaskExecutor.class).contains(entity),
                    "Entity should drop from intersection query after component removed");
        }
    }

    // ===================================================================
    // 2. World.clearAllTasks() — recovery/emergency reset
    // ===================================================================

    @Nested
    class ClearAllTasksTests {
        private MockBoundary mock;
        private World world;

        @BeforeEach
        void setUp() {
            mock = new MockBoundary();
            BlueprintRegistry blueprints = new BlueprintRegistry();
            CoreBootstrapConfig config = new CoreBootstrapConfig(mock, mock, mock, null, mock,
                    List.of(), blueprints, new SystemBlueprintRegistry(), false);
            world = CoreBootstrap.bootstrap(config);
        }

        @Test
        void clearAll_removesAllTasksAndResetsNpcs() {
            // Register a simple blueprint
            world.blueprintRegistry.register("test:place",
                    new Blueprint("test:place",
                            p -> new TaskSequence(
                                    List.of(AtomicOp.TransformOp.place(GridPos.ORIGIN,
                                            BlockType.STONE)),
                                    "test:place")));

            // Add 3 tasks
            long t1 = world.taskPool.addTask(new TaskRequest("test:place", Map.of(), 10, null));
            long t2 = world.taskPool.addTask(new TaskRequest("test:place", Map.of(), 20, null));
            long t3 = world.taskPool.addTask(new TaskRequest("test:place", Map.of(), 30, null));

            assertEquals(3, world.taskPool.size(), "Should have 3 active tasks");

            // Create 2 NPCs with TaskExecutors, assign one task
            long npc1 = CoreBootstrap.createNpc(world, 0, 64, 0,
                    java.util.UUID.randomUUID(), NpcAttributes.defaults());
            long npc2 = CoreBootstrap.createNpc(world, 5, 64, 0,
                    java.util.UUID.randomUUID(), NpcAttributes.defaults());

            world.taskPool.assignLight(t1, npc1, world);
            // Simulate what TaskExecutionSystem.startNextPending would do
            TaskExecutor exec1 = world.get(npc1, TaskExecutor.class);
            exec1.state = ExecutorState.ACTIVE;

            GlobalTask task1 = world.taskPool.get(t1);
            assertEquals(TaskState.IN_PROGRESS, task1.state);
            assertEquals(npc1, task1.assignedNpcId);

            assertEquals(ExecutorState.ACTIVE, exec1.state);

            // ── Act ──
            world.clearAllTasks();

            // ── Assert ──
            assertEquals(0, world.taskPool.size(), "Task pool should be empty");
            assertEquals(0, world.taskPool.assignableCount(), "Assignable set should be empty");
            assertNull(world.taskPool.get(t1), "Task t1 should be gone");
            assertNull(world.taskPool.get(t2), "Task t2 should be gone");
            assertNull(world.taskPool.get(t3), "Task t3 should be gone");

            // NPC executors reset
            TaskExecutor exec1After = world.get(npc1, TaskExecutor.class);
            assertNotNull(exec1After);
            assertEquals(ExecutorState.IDLE, exec1After.state);
            assertNull(exec1After.globalTaskId);
            assertNull(exec1After.currentSequence);

            TaskExecutor exec2After = world.get(npc2, TaskExecutor.class);
            assertNotNull(exec2After);
            assertEquals(ExecutorState.IDLE, exec2After.state);

            // Building pool cleared
            assertEquals(0, world.buildingTaskPool.totalBuildings());
        }

        @Test
        void clearAll_whenNoTasks_isNoOp() {
            assertEquals(0, world.taskPool.size());

            assertDoesNotThrow(() -> world.clearAllTasks());

            assertEquals(0, world.taskPool.size());
        }
    }
}
