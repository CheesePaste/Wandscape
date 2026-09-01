package com.wsteam.wandscape.task.scheduler;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.wsteam.wandscape.content.task.scheduler.SystemBlueprintRegistry;
import com.wsteam.wandscape.core.CoreBootstrap;
import com.wsteam.wandscape.core.CoreBootstrapConfig;
import com.wsteam.wandscape.core.boundary.MockBoundary;
import com.wsteam.wandscape.core.boundary.MovementOps;
import com.wsteam.wandscape.core.component.TaskExecutor;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.types.GridPos;
import com.wsteam.wandscape.core.types.InteractAction;
import com.wsteam.wandscape.core.types.NpcAttributes;
import com.wsteam.wandscape.core.types.ResourceId;
import com.wsteam.wandscape.core.types.ResourceStack;
import com.wsteam.wandscape.content.task.op.api.AtomicOp;
import com.wsteam.wandscape.content.task.op.executor.OpExecutor;
import com.wsteam.wandscape.content.task.op.executor.ResourceShortageException;
import com.wsteam.wandscape.content.task.engine.dsl.BlueprintRegistry;
import com.wsteam.wandscape.content.task.engine.pool.GlobalTask;
import com.wsteam.wandscape.content.task.engine.pool.TaskRequest;
import com.wsteam.wandscape.content.task.runtime.ExecutorState;
import com.wsteam.wandscape.content.task.runtime.NpcTaskPackage;
import com.wsteam.wandscape.content.task.runtime.TaskSequence;
import com.wsteam.wandscape.content.task.runtime.TaskState;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class TaskExecutionResourceShortageTest {

    private World world;
    private BlueprintRegistry blueprints;
    private long npcId;

    @BeforeEach
    void setUp() {
        MockBoundary mock = new MockBoundary();
        blueprints = new BlueprintRegistry();
        MovementOps noopMov = new MovementOps() {
            @Override
            public CompletableFuture<Void> navigateTo(long npcId, int x, int y, int z) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public void cancelNavigation(long npcId) {
            }
        };

        CoreBootstrapConfig config = new CoreBootstrapConfig(
                mock, mock, mock, noopMov, mock, List.of(), blueprints,
                new SystemBlueprintRegistry(), false);
        world = CoreBootstrap.bootstrap(config);
        npcId = CoreBootstrap.createNpc(world, 0, 64, 0, UUID.randomUUID(), NpcAttributes.defaults());
    }

    private GlobalTask bindTaskWithOp(AtomicOp op) {
        String bpId = "test:synth_" + UUID.randomUUID();
        TaskSequence seq = new TaskSequence(List.of(op), "synth");
        blueprints.register(bpId, params -> seq);

        long taskId = world.taskPool.addTask(new TaskRequest(bpId, Map.of(), 10, null));
        GlobalTask task = world.taskPool.get(taskId);
        world.taskPool.assignLight(taskId, npcId, world);

        TaskExecutor exec = world.get(npcId, TaskExecutor.class);
        exec.npcQueue.enqueueNormal(NpcTaskPackage.of("global:" + taskId, seq, new GridPos(0, 64, 0), 10));
        return task;
    }

    @Test
    void execute_catchesSynchronousResourceShortageException() {
        AtomicOp.BlockInteractOp op = new AtomicOp.BlockInteractOp(
                new GridPos(0, 64, 0), new InteractAction("synthesize"), Map.of(), 0);

        // Register executor that throws ResourceShortageException directly
        world.opExecutors.register(new OpExecutor<AtomicOp.BlockInteractOp>() {
            @Override
            public Class<AtomicOp.BlockInteractOp> opType() {
                return AtomicOp.BlockInteractOp.class;
            }

            @Override
            public CompletableFuture<Void> execute(AtomicOp.BlockInteractOp op, World world, long npcId) {
                throw new ResourceShortageException(List.of(new ResourceStack(new ResourceId("wood"), 128)));
            }
        });

        GlobalTask task = bindTaskWithOp(op);

        assertDoesNotThrow(() -> world.tick(1.0f));

        // Task should be marked AWAITING_RESOURCES, NPC should be IDLE and released
        assertEquals(TaskState.AWAITING_RESOURCES, task.state);
        assertEquals(1, task.awaitingResource.size());
        assertEquals("wood", task.awaitingResource.get(0).resource().id());
        assertEquals(128, task.awaitingResource.get(0).amount());

        TaskExecutor exec = world.get(npcId, TaskExecutor.class);
        assertEquals(ExecutorState.IDLE, exec.state);
        assertNull(exec.globalTaskId);
        assertFalse(exec.npcQueue.hasWork());
    }

    @Test
    void execute_catchesFailedFutureResourceShortageException() {
        AtomicOp.BlockInteractOp op = new AtomicOp.BlockInteractOp(
                new GridPos(0, 64, 0), new InteractAction("synthesize"), Map.of(), 0);

        // Register executor that returns failedFuture with ResourceShortageException
        world.opExecutors.register(new OpExecutor<AtomicOp.BlockInteractOp>() {
            @Override
            public Class<AtomicOp.BlockInteractOp> opType() {
                return AtomicOp.BlockInteractOp.class;
            }

            @Override
            public CompletableFuture<Void> execute(AtomicOp.BlockInteractOp op, World world, long npcId) {
                return CompletableFuture.failedFuture(
                        new ResourceShortageException(List.of(new ResourceStack(new ResourceId("wood"), 64))));
            }
        });

        GlobalTask task = bindTaskWithOp(op);

        assertDoesNotThrow(() -> world.tick(1.0f));

        assertEquals(TaskState.AWAITING_RESOURCES, task.state);
        assertEquals(1, task.awaitingResource.size());
        assertEquals("wood", task.awaitingResource.get(0).resource().id());
        assertEquals(64, task.awaitingResource.get(0).amount());

        TaskExecutor exec = world.get(npcId, TaskExecutor.class);
        assertEquals(ExecutorState.IDLE, exec.state);
        assertNull(exec.globalTaskId);
        assertFalse(exec.npcQueue.hasWork());
    }

    @Test
    void execute_catchesAsyncPendingFutureExceptionOnNextTick() {
        AtomicOp.BlockInteractOp op = new AtomicOp.BlockInteractOp(
                new GridPos(0, 64, 0), new InteractAction("synthesize"), Map.of(), 10);

        CompletableFuture<Void> future = new CompletableFuture<>();
        world.opExecutors.register(new OpExecutor<AtomicOp.BlockInteractOp>() {
            @Override
            public Class<AtomicOp.BlockInteractOp> opType() {
                return AtomicOp.BlockInteractOp.class;
            }

            @Override
            public CompletableFuture<Void> execute(AtomicOp.BlockInteractOp op, World world, long npcId) {
                return future;
            }
        });

        GlobalTask task = bindTaskWithOp(op);

        // Tick 1: op starts async, pending future stored
        world.tick(1.0f);
        TaskExecutor exec = world.get(npcId, TaskExecutor.class);
        assertEquals(ExecutorState.ACTIVE, exec.state);

        // Future completes exceptionally
        future.completeExceptionally(new ResourceShortageException(List.of(new ResourceStack(new ResourceId("fire"), 10))));

        // Tick 2: pending future resolved with exception
        assertDoesNotThrow(() -> world.tick(1.0f));

        assertEquals(TaskState.AWAITING_RESOURCES, task.state);
        assertEquals(1, task.awaitingResource.size());
        assertEquals("fire", task.awaitingResource.get(0).resource().id());

        assertEquals(ExecutorState.IDLE, exec.state);
        assertNull(exec.globalTaskId);
        assertFalse(exec.npcQueue.hasWork());
    }

    @Test
    void execute_catchesUnexpectedRuntimeExceptionWithoutCrashing() {
        AtomicOp.BlockInteractOp op = new AtomicOp.BlockInteractOp(
                new GridPos(0, 64, 0), new InteractAction("toggle"), Map.of(), 0);

        world.opExecutors.register(new OpExecutor<AtomicOp.BlockInteractOp>() {
            @Override
            public Class<AtomicOp.BlockInteractOp> opType() {
                return AtomicOp.BlockInteractOp.class;
            }

            @Override
            public CompletableFuture<Void> execute(AtomicOp.BlockInteractOp op, World world, long npcId) {
                throw new IllegalStateException("Simulated unexpected crash");
            }
        });

        GlobalTask task = bindTaskWithOp(op);

        assertDoesNotThrow(() -> world.tick(1.0f));

        // Task released back to pool
        assertEquals(TaskState.PENDING_ASSIGN, task.state);
        TaskExecutor exec = world.get(npcId, TaskExecutor.class);
        assertEquals(ExecutorState.IDLE, exec.state);
        assertNull(exec.globalTaskId);
    }
}
