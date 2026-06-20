package com.wsteam.wandscape.core;

import com.wsteam.wandscape.core.boundary.MovementOps;
import com.wsteam.wandscape.core.component.*;
import com.wsteam.wandscape.core.demo.MockBoundary;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.op.AtomicOp;
import com.wsteam.wandscape.core.op.DefaultOpExecutors;
import com.wsteam.wandscape.core.system.SystemBlueprintRegistry;
import com.wsteam.wandscape.core.task.*;
import com.wsteam.wandscape.core.types.BlockType;
import com.wsteam.wandscape.core.types.GridPos;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

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
                new TaskRequest(bpId, Map.of(), 10));
    }

    /** Create a standard NPC with full mana and a basic wand. */
    private static long createNpc(World world, int x, int y, int z, UUID colonyId) {
        WandCarrier wand = new WandCarrier(Map.of(), 1.0f, 5);
        return CoreBootstrap.createNpc(world, x, y, z, wand, colonyId, 200, 10);
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
                    new SystemBlueprintRegistry());
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
            world.taskPool.assign(taskId, npc, world);

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
            world.taskPool.assign(taskId, npc, world);

            TaskExecutor exec = world.get(npc, TaskExecutor.class);
            assertEquals(0, exec.stepIndex);

            // Tick: in range → sync execute
            world.tick(1.0f);
            assertEquals(TaskState.COMPLETED, world.taskPool.get(taskId).state,
                    "sync op executes and completes in one tick");
            assertFalse(exec.hasWork(), "executor idle after task complete");
        }

        @Test
        void fullNavThenOpCycle_twoPositions() {
            TaskSequence seq = buildSeq("two", new GridPos(10, 64, 0), new GridPos(20, 64, 0));
            long taskId = addTaskFromSeq(blueprints, "test:two", seq, world);
            world.taskPool.assign(taskId, npc, world);

            TaskExecutor exec = world.get(npc, TaskExecutor.class);

            // Op0: out of range → navigate
            world.tick(1.0f);
            assertTrue(movOps.hasActiveNav(npc));
            assertEquals(0, exec.stepIndex);
            movOps.completeNav(npc);

            // Nav resolved → execute op0 → advance (but side-effect breaks after 1 op)
            world.tick(1.0f);
            assertEquals(1, exec.stepIndex, "op0 done (not complete yet — has op1)");
            assertEquals(TaskState.IN_PROGRESS, world.taskPool.get(taskId).state);

            // Op1: out of range → navigate again
            world.tick(1.0f);
            assertTrue(movOps.hasActiveNav(npc), "navigating to op1");
            assertEquals(1, exec.stepIndex, "step stays 1 during nav");
            movOps.completeNav(npc);

            // Nav resolved → execute op1 → sequence complete → release
            world.tick(1.0f);
            assertEquals(TaskState.COMPLETED, world.taskPool.get(taskId).state,
                    "op1 done, sequence complete");
        }

        @Test
        void pendingFutureIsNav_clearedOnTaskRelease() {
            TaskSequence seq = buildSeq("far", new GridPos(10, 64, 0));
            long taskId = addTaskFromSeq(blueprints, "test:far2", seq, world);
            world.taskPool.assign(taskId, npc, world);

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
                    new SystemBlueprintRegistry());
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
            world.taskPool.assign(taskA, npcA, world);
            world.taskPool.assign(taskB, npcB, world);

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
            world.taskPool.assign(taskA, npcA, world);
            world.taskPool.assign(taskB, npcB, world);

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
        void cancelNavigationWhenNpcGoesIdle() {
            TaskSequence seq = buildSeq("idle", new GridPos(10, 64, 0));
            long taskId = addTaskFromSeq(blueprints, "test:idle", seq, world);
            world.taskPool.assign(taskId, npcA, world);

            world.tick(1.0f);
            assertTrue(movOps.hasActiveNav(npcA));

            // Manually release task (simulating interruption)
            TaskExecutor exec = world.get(npcA, TaskExecutor.class);
            exec.releaseGlobalTask();

            world.tick(1.0f);
            assertFalse(movOps.hasActiveNav(npcA),
                    "nav cancelled when NPC becomes idle");
            assertTrue(movOps.calls.stream().anyMatch(s -> s.startsWith("cancel:")),
                    "cancelNavigation should have been called");
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
                    new SystemBlueprintRegistry());
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
            world.taskPool.assign(taskId, npc, world);

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
        void differentTarget_navigateAgain() {
            TaskSequence seq = buildSeq("separate",
                    new GridPos(10, 64, 0), new GridPos(16, 64, 0));
            long taskId = addTaskFromSeq(blueprints, "test:sep", seq, world);
            world.taskPool.assign(taskId, npc, world);

            // Nav to (10,64,0)
            world.tick(1.0f);
            movOps.completeNav(npc);

            // Op0 executes → op1 different target → side-effect break
            world.tick(1.0f);
            TaskExecutor exec = world.get(npc, TaskExecutor.class);
            assertEquals(1, exec.stepIndex, "only op0 done (2-op sequence, not yet complete)");

            // Tick: op1 at (16,64,0), NPC at (10,64,0): dx²=36 > 25 → navigate again
            world.tick(1.0f);
            assertTrue(movOps.hasActiveNav(npc), "navigate again for different target");
        }
    }
}
