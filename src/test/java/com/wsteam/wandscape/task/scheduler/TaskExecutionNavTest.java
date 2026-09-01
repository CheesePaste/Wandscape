package com.wsteam.wandscape.task.engine.dsl.core;

import com.wsteam.wandscape.core.boundary.MockBoundary;
import com.wsteam.wandscape.core.CoreBootstrap;
import com.wsteam.wandscape.core.CoreBootstrapConfig;
import com.wsteam.wandscape.core.component.Position;
import com.wsteam.wandscape.core.component.TaskExecutor;
import com.wsteam.wandscape.content.task.engine.dsl.BlueprintRegistry;
import com.wsteam.wandscape.core.boundary.MovementOps;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.content.task.op.api.AtomicOp;
import com.wsteam.wandscape.content.task.op.executor.DefaultOpExecutors;
import com.wsteam.wandscape.content.task.scheduler.SystemBlueprintRegistry;
import com.wsteam.wandscape.content.task.scheduler.TaskExecutionSystem;
import com.wsteam.wandscape.core.types.BlockType;
import com.wsteam.wandscape.core.types.GridPos;
import com.wsteam.wandscape.core.types.NpcAttributes;

import com.wsteam.wandscape.content.task.engine.pool.TaskRequest;
import com.wsteam.wandscape.content.task.runtime.ExecutorState;
import com.wsteam.wandscape.content.task.runtime.NpcTaskPackage;
import com.wsteam.wandscape.content.task.runtime.TaskSequence;
import com.wsteam.wandscape.content.task.runtime.TaskState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for V2.6 navigation future vs op execution future isolation.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Nav future resolved → stepIndex does NOT advance (op execution advances it)</li>
 *   <li>Op future resolved → stepIndex advances (block placed in callback)</li>
 *   <li>Nav→op full cycle: nav → range check → execute → advance</li>
 *   <li>Per-NPC isolation: one NPC navigating doesn't block another's execution</li>
 *   <li>pendingFutureIsNav flag resets correctly on task release</li>
 *   <li>Same-target batching: consecutive ops at same pos skip re-navigation</li>
 * </ul>
 */
public class TaskExecutionNavTest {

    // ===================================================================
    // Test infrastructure
    // ===================================================================

    /**
     * MovementOps that returns controllable futures for deterministic testing.
     * On {@link #completeNav}, also updates the NPC's ECS Position to the target
     * (simulating what WandscapeMovementOps.tickAll does — NPC arrives at target).
     */
    static class ControllableMovementOps implements MovementOps {
        final Map<Long, CompletableFuture<Void>> pending = new HashMap<>();
        final Map<Long, GridPos> targets = new HashMap<>();
        final List<String> calls = new ArrayList<>();
        World world; // set after bootstrap

        @Override
        public CompletableFuture<Void> navigateTo(long npcId, int x, int y, int z) {
            calls.add("nav:" + npcId + "→" + x + "," + z);
            targets.put(npcId, new GridPos(x, y, z));
            CompletableFuture<Void> f = new CompletableFuture<>();
            pending.put(npcId, f);
            return f;
        }

        @Override
        public void cancelNavigation(long npcId) {
            calls.add("cancel:" + npcId);
            pending.remove(npcId);
            targets.remove(npcId);
        }

        /** Complete navigation and teleport NPC to target (simulating arrival). */
        void completeNav(long npcId) {
            GridPos target = targets.remove(npcId);
            // Update ECS position so range check passes
            if (target != null && world != null) {
                world.addComponent(npcId, Position.of(target.x(), target.y(), target.z()));
            }
            CompletableFuture<Void> f = pending.remove(npcId);
            if (f != null) f.complete(null);
        }

        boolean hasActiveNav(long npcId) {
            return pending.containsKey(npcId);
        }
    }

    /** Build a simple TaskSequence of TransformOp(place stone) at given positions. */
    private static TaskSequence buildSeq(String label, GridPos... targets) {
        List<AtomicOp> ops = new ArrayList<>();
        BlockType stone = new BlockType("minecraft:stone");
        for (GridPos t : targets) {
            ops.add(AtomicOp.TransformOp.place(t, stone));
        }
        return new TaskSequence(ops, label);
    }

    /** Register a lambda blueprint and add a task for it to the pool. */
    private static long addTaskFromSeq(BlueprintRegistry blueprints, String bpId,
                                        TaskSequence seq, World world) {
        blueprints.register(bpId, params -> seq);
        return world.taskPool.addTask(
                new TaskRequest(bpId, Map.of(), 10, null));
    }

    /** Create a standard NPC with full attributes. */
    private static long createNpc(World world, int x, int y, int z, UUID colonyId) {
        return CoreBootstrap.createNpc(world, x, y, z, colonyId, NpcAttributes.defaults());
    }

    /** Assign a task to an NPC using the Phase 6 package-driven model. */
    private static void assignTaskToNpc(long taskId, long npcId, TaskSequence seq, int priority, World world) {
        world.taskPool.assignLight(taskId, npcId, world);
        TaskExecutor exec = world.get(npcId, TaskExecutor.class);
        GridPos stance = TaskExecutionSystem.computeTaskStance(seq);
        exec.npcQueue.enqueueNormal(NpcTaskPackage.of("global:" + taskId, seq, stance, priority));
    }

    // ===================================================================
    // 1. Nav vs op future — step advancement
    // ===================================================================

    @Nested
    class NavFutureDoesNotAdvanceStep {
        private MockBoundary mock;
        private ControllableMovementOps movOps;
        private BlueprintRegistry blueprints;
        private World world;
        private long npc;
        private UUID colonyId;

        @BeforeEach
        void setUp() {
            mock = new MockBoundary();
            movOps = new ControllableMovementOps();
            blueprints = new BlueprintRegistry();

            CoreBootstrapConfig config = new CoreBootstrapConfig(
                    mock, mock, mock, movOps, mock, List.of(), blueprints,
                    new SystemBlueprintRegistry(), false);
            world = CoreBootstrap.bootstrap(config);
            movOps.world = world;
            DefaultOpExecutors.registerAll(world.opExecutors);

            GridPos center = new GridPos(0, 64, 0);
            colonyId = UUID.randomUUID();
            CoreBootstrap.createColony(world, center.x(), center.y(), center.z(), 50);
            npc = createNpc(world, 0, 64, 0, colonyId);
        }

        @Test
        void navFutureResolved_doesNotAdvanceStepIndex() {
            // NPC at (0,64,0), target at (10,64,0): dx²+dz²=100 > 6.25 → navigate
            TaskSequence seq = buildSeq("far", new GridPos(10, 64, 0));
            long taskId = addTaskFromSeq(blueprints, "test:far", seq, world);
            assignTaskToNpc(taskId, npc, seq, 10, world);

            TaskExecutor exec = world.get(npc, TaskExecutor.class);
            assertEquals(0, exec.stepIndex);

            // Tick: out of range → navigate (future stored in pendingFuture)
            world.tick(1.0f);
            assertTrue(movOps.hasActiveNav(npc), "NPC should be navigating");
            assertTrue(exec.pendingFutureIsNav, "flag should mark it as nav future");
            assertEquals(0, exec.stepIndex, "stepIndex stays 0 during navigation");

            // Nav resolves
            movOps.completeNav(npc);

            // Tick: nav resolved → range check passes → execute op → complete
            // (releaseGlobalTask resets stepIndex to 0, so check task state instead)
            world.tick(1.0f);
            assertEquals(TaskState.COMPLETED, world.taskPool.get(taskId).state,
                    "op executed after nav, task completed");
            assertNull(exec.globalTaskId, "NPC released after task complete");
        }

        @Test
        void opFutureResolved_advancesStepIndex() {
            // NPC at (0,64,0), target also at (0,64,0): in range → no nav
            TaskSequence seq = buildSeq("near", new GridPos(0, 64, 0));
            long taskId = addTaskFromSeq(blueprints, "test:near", seq, world);
            assignTaskToNpc(taskId, npc, seq, 10, world);

            TaskExecutor exec = world.get(npc, TaskExecutor.class);
            assertEquals(0, exec.stepIndex);

            // Tick: in range → sync execute
            world.tick(1.0f);
            assertEquals(TaskState.COMPLETED, world.taskPool.get(taskId).state,
                    "sync op executes and completes in one tick");
            assertFalse(exec.npcQueue.hasWork() || exec.globalTaskId != null, "executor idle after task complete");
        }

        @Test
        void fullNavThenOpCycle_twoPositions() {
            TaskSequence seq = buildSeq("two", new GridPos(10, 64, 0), new GridPos(20, 64, 0));
            long taskId = addTaskFromSeq(blueprints, "test:two", seq, world);
            assignTaskToNpc(taskId, npc, seq, 10, world);

            TaskExecutor exec = world.get(npc, TaskExecutor.class);

            // Stance: bbox (10..20, 0..0) → stance = (8, 64, 0)
            // NPC at (0,64,0), stance at (8,64,0): dx²=64 > 25 → navigate to stance
            world.tick(1.0f);
            assertTrue(movOps.hasActiveNav(npc), "navigating to stance");
            assertEquals(0, exec.stepIndex);
            assertNotNull(exec.stance, "stance computed from task targets");
            assertEquals(8, exec.stance.x());
            movOps.completeNav(npc); // NPC now at stance (8,64,0)

            // Nav resolved → op0 at (10,64,0): dx=2 → in range, execute → advance
            // Different target from op1 → break (one side-effect per tick)
            world.tick(1.0f);
            assertEquals(1, exec.stepIndex, "op0 done");
            assertEquals(TaskState.IN_PROGRESS, world.taskPool.get(taskId).state);

            // Op1 at (20,64,0): stance != null → skip per-op nav, execute directly from stance
            world.tick(1.0f);
            assertEquals(TaskState.COMPLETED, world.taskPool.get(taskId).state,
                    "op1 done from stance, sequence complete");
            assertEquals(1, movOps.calls.stream().filter(s -> s.startsWith("nav:")).count(),
                    "only one navigation (to stance), no per-op nav");
        }

        @Test
        void pendingFutureIsNav_clearedOnTaskRelease() {
            TaskSequence seq = buildSeq("far", new GridPos(10, 64, 0));
            long taskId = addTaskFromSeq(blueprints, "test:far2", seq, world);
            assignTaskToNpc(taskId, npc, seq, 10, world);

            TaskExecutor exec = world.get(npc, TaskExecutor.class);
            world.tick(1.0f);
            assertTrue(exec.pendingFutureIsNav);

            exec.releaseGlobalTask();
            assertNull(exec.pendingFuture);
            assertFalse(exec.pendingFutureIsNav,
                    "releaseGlobalTask must clear pendingFutureIsNav");
        }
    }

    // ===================================================================
    // 2. Per-NPC isolation
    // ===================================================================

    @Nested
    class PerNpcIsolation {
        private MockBoundary mock;
        private ControllableMovementOps movOps;
        private BlueprintRegistry blueprints;
        private World world;
        private long npcA, npcB;
        private UUID colonyId;

        @BeforeEach
        void setUp() {
            mock = new MockBoundary();
            movOps = new ControllableMovementOps();
            blueprints = new BlueprintRegistry();

            CoreBootstrapConfig config = new CoreBootstrapConfig(
                    mock, mock, mock, movOps, mock, List.of(), blueprints,
                    new SystemBlueprintRegistry(), false);
            world = CoreBootstrap.bootstrap(config);
            movOps.world = world;
            DefaultOpExecutors.registerAll(world.opExecutors);

            GridPos center = new GridPos(0, 64, 0);
            colonyId = UUID.randomUUID();
            CoreBootstrap.createColony(world, center.x(), center.y(), center.z(), 50);
            npcA = createNpc(world, 0, 64, 0, colonyId);
            npcB = createNpc(world, 0, 64, 0, colonyId);
        }

        @Test
        void npcA_navigating_npcB_executesOps() {
            TaskSequence farSeq = buildSeq("farA", new GridPos(50, 64, 0));
            TaskSequence nearSeq = buildSeq("nearB", new GridPos(0, 64, 0));
            long taskA = addTaskFromSeq(blueprints, "test:farA", farSeq, world);
            long taskB = addTaskFromSeq(blueprints, "test:nearB", nearSeq, world);
            assignTaskToNpc(taskA, npcA, farSeq, 10, world);
            assignTaskToNpc(taskB, npcB, nearSeq, 10, world);

            world.tick(1.0f);

            // A is navigating, task not yet complete
            assertTrue(movOps.hasActiveNav(npcA), "A should be navigating");
            assertEquals(TaskState.IN_PROGRESS, world.taskPool.get(taskA).state);

            // B executed its op (in range → sync → complete)
            assertEquals(TaskState.COMPLETED, world.taskPool.get(taskB).state,
                    "B executed and completed while A was navigating");
        }

        @Test
        void bothNavigating_bothProgressIndependently() {
            TaskSequence seqA = buildSeq("seqA", new GridPos(10, 64, 0));
            TaskSequence seqB = buildSeq("seqB", new GridPos(20, 64, 0));
            long taskA = addTaskFromSeq(blueprints, "test:a10", seqA, world);
            long taskB = addTaskFromSeq(blueprints, "test:b20", seqB, world);
            assignTaskToNpc(taskA, npcA, seqA, 10, world);
            assignTaskToNpc(taskB, npcB, seqB, 10, world);

            // Both navigate
            world.tick(1.0f);
            assertTrue(movOps.hasActiveNav(npcA));
            assertTrue(movOps.hasActiveNav(npcB));

            // Only A's nav completes → A executes and completes (single op)
            movOps.completeNav(npcA);
            world.tick(1.0f);
            assertEquals(TaskState.COMPLETED, world.taskPool.get(taskA).state,
                    "A done after nav — single op complete");
            assertEquals(TaskState.IN_PROGRESS, world.taskPool.get(taskB).state,
                    "B still navigating");

            // B's nav completes → B executes and completes
            movOps.completeNav(npcB);
            world.tick(1.0f);
            assertEquals(TaskState.COMPLETED, world.taskPool.get(taskB).state,
                    "B done after nav");
        }

        @Test
        void releaseGlobalTaskResetsExecutorState() {
            TaskSequence seq = buildSeq("idle", new GridPos(10, 64, 0));
            long taskId = addTaskFromSeq(blueprints, "test:idle", seq, world);
            assignTaskToNpc(taskId, npcA, seq, 10, world);

            world.tick(1.0f);
            TaskExecutor exec = world.get(npcA, TaskExecutor.class);
            assertNotNull(exec.stance, "stance computed for task");

            // Manually release task (simulating interruption)
            exec.releaseGlobalTask();

            assertNull(exec.globalTaskId);
            assertNull(exec.currentSequence);
            assertNull(exec.stance, "stance cleared on release");
            assertEquals(ExecutorState.IDLE, exec.state);
            // Navigation in MovementOps is NOT cancelled here —
            // it times out naturally in WandscapeMovementOps.tickAll (200 ticks).
            // The ControllableMovementOps mock doesn't auto-timeout, so nav stays active.
        }
    }

    // ===================================================================
    // 3. Same-target batching
    // ===================================================================

    @Nested
    class SameTargetBatching {
        private MockBoundary mock;
        private ControllableMovementOps movOps;
        private BlueprintRegistry blueprints;
        private World world;
        private long npc;
        private UUID colonyId;

        @BeforeEach
        void setUp() {
            mock = new MockBoundary();
            movOps = new ControllableMovementOps();
            blueprints = new BlueprintRegistry();

            CoreBootstrapConfig config = new CoreBootstrapConfig(
                    mock, mock, mock, movOps, mock, List.of(), blueprints,
                    new SystemBlueprintRegistry(), false);
            world = CoreBootstrap.bootstrap(config);
            movOps.world = world;
            DefaultOpExecutors.registerAll(world.opExecutors);

            GridPos center = new GridPos(0, 64, 0);
            colonyId = UUID.randomUUID();
            CoreBootstrap.createColony(world, center.x(), center.y(), center.z(), 50);
            npc = createNpc(world, 0, 64, 0, colonyId);
        }

        @Test
        void sameTarget_batchWithoutReNavigation() {
            // Target 10 blocks away → dx²=100 > 25 → nav fires
            GridPos shared = new GridPos(10, 64, 0);
            TaskSequence seq = buildSeq("batched", shared, shared);
            long taskId = addTaskFromSeq(blueprints, "test:batch", seq, world);
            assignTaskToNpc(taskId, npc, seq, 10, world);

            // Nav to (10,64,0)
            world.tick(1.0f);
            assertTrue(movOps.hasActiveNav(npc));
            long navCount = movOps.calls.stream().filter(s -> s.startsWith("nav:")).count();

            movOps.completeNav(npc);

            // Both ops batch-execute in one tick → task complete
            world.tick(1.0f);
            assertEquals(TaskState.COMPLETED, world.taskPool.get(taskId).state,
                    "both ops at same target batch-executed, task complete");
            assertEquals(navCount, movOps.calls.stream().filter(s -> s.startsWith("nav:")).count(),
                    "no extra navigation for second op");
        }

        @Test
        void differentTarget_noNavigateAgainWhenStanceSet() {
            TaskSequence seq = buildSeq("separate",
                    new GridPos(10, 64, 0), new GridPos(16, 64, 0));
            long taskId = addTaskFromSeq(blueprints, "test:sep", seq, world);
            assignTaskToNpc(taskId, npc, seq, 10, world);

            // Stance: bbox (10..16, 0..0) → stance = (8, 64, 0)
            // Nav to stance
            world.tick(1.0f);
            TaskExecutor exec = world.get(npc, TaskExecutor.class);
            assertNotNull(exec.stance, "stance computed");
            assertEquals(8, exec.stance.x());
            movOps.completeNav(npc); // NPC at stance (8,64,0)

            // Op0 at (10,64,0): dx=2 → in range, executes → advance → break (different target)
            world.tick(1.0f);
            assertEquals(1, exec.stepIndex, "op0 done");

            // Op1 at (16,64,0): stance != null → per-op nav skipped, execute directly
            world.tick(1.0f);
            assertFalse(movOps.hasActiveNav(npc),
                    "no per-op nav when stance is set");
            assertEquals(TaskState.COMPLETED, world.taskPool.get(taskId).state,
                    "op1 done from stance");
        }
    }
}
