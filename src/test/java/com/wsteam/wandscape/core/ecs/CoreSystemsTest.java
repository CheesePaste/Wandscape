package com.wsteam.wandscape.core.ecs;
import com.wsteam.wandscape.core.boundary.MockBoundary;
import java.lang.System;

import com.wsteam.wandscape.core.CoreBootstrap;
import com.wsteam.wandscape.core.CoreBootstrapConfig;
import com.wsteam.wandscape.core.TemplateResolver;
import com.wsteam.wandscape.core.types.BlockType;
import com.wsteam.wandscape.core.types.GridPos;
import com.wsteam.wandscape.core.types.NpcAttributes;
import com.wsteam.wandscape.core.types.ResourceId;
import com.wsteam.wandscape.core.types.RitualId;
import com.wsteam.wandscape.task.engine.dsl.Blueprint;
import com.wsteam.wandscape.task.engine.dsl.BlueprintRegistry;
import com.wsteam.wandscape.task.engine.dsl.BlueprintSteps;
import com.wsteam.wandscape.core.component.TaskExecutor;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.wsteam.wandscape.task.engine.pool.GlobalTask;
import com.wsteam.wandscape.task.engine.pool.TaskRequest;
import com.wsteam.wandscape.task.runtime.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.op.api.AtomicOp;
import com.wsteam.wandscape.task.scheduler.TaskExecutionSystem;
import com.wsteam.wandscape.op.executor.DefaultOpExecutors;
import com.wsteam.wandscape.task.scheduler.SystemBlueprintRegistry;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for untested engine subsystems: scheduler scoring, RitualOp lifecycle,
 * private queue mechanics, approval flow, TemplateResolver edges, and task interrupts.
 */
public class CoreSystemsTest {

    // ===================================================================
    // 1. SchedulerSystem — scoring, requirements, colony grouping
    // ===================================================================

    @Nested
    class SchedulerTests {
        private MockBoundary mock;
        private World world;
        private UUID colonyId;
        private long npcHighRange, npcHighLevel;
        private GridPos center;

        @BeforeEach
        void setUp() {
            mock = new MockBoundary();
            mock.seedWarehouse(ResourceId.STONE_BRICKS, 200);
            mock.seedWarehouse(ResourceId.WOOD, 200);

            BlueprintRegistry blueprints = new BlueprintRegistry();
            CoreBootstrapConfig config = new CoreBootstrapConfig(mock, mock, mock, null, mock, List.of(), blueprints,
                    new SystemBlueprintRegistry(), false);
            world = CoreBootstrap.bootstrap(config);
            DefaultOpExecutors.registerAll(world.opExecutors);

            center = new GridPos(0, 64, 0);
            colonyId = UUID.randomUUID();
            CoreBootstrap.createColony(world, center.x(), center.y(), center.z(), 50);

            // NPC-A: high range (6), BUILDING:1, mid efficiency (0.9), at (1,64,0)
            // NPC-B: low range (1), BUILDING:3, good efficiency (0.7), at (2,64,0)
            // Task target at (10,64,0): NPC-B dist=8 < NPC-A dist=9, so NPC-B wins
            npcHighRange = CoreBootstrap.createNpc(world, 1, 64, 0, colonyId, NpcAttributes.defaults());
            npcHighLevel = CoreBootstrap.createNpc(world, 2, 64, 0, colonyId, NpcAttributes.defaults());
        }

        @Test
        void bestScoringNpcWinsAssignment() {
            // Both NPCs qualify for BUILDING:1 task
            // NPC-B is closer to target + more efficient → gets assigned
            registerSimpleBp("test:best_win",
                    AtomicOp.TransformOp.place(center.add(10, 0, 0), BlockType.STONE));

            long taskId = world.taskPool.addTask(
                    makeRequest("test:best_win", center, 10));
            tickN(5);

            GlobalTask task = world.taskPool.get(taskId);
            // Task should have been assigned and completed
            assertEquals(TaskState.COMPLETED, task.state);
            // Block placed → confirms execution
            assertFalse(mock.isAir(center.add(10, 0, 0)));
        }

        @Test
        void npcWithoutMatchingRequirements_taskStaysPending() {
            // Scheduler no longer checks requirements — any NPC with mana can pick up any task.
            // This tests that the scheduler doesn't crash when task has requirements set.
            registerSimpleBp("test:ritual_req",
                    new AtomicOp.RitualOp(RitualId.WARDING, center, Collections.emptyMap()));

            GlobalTask task = GlobalTask.createSmall(0,
                    TaskSequence.of("Ritual Task",
                            new AtomicOp.RitualOp(RitualId.WARDING, center, Collections.emptyMap())),
                    10, List.of(), Map.of());
            long taskId = world.taskPool.addTask(task);

            // Without requirement gating, NPC picks up the task and completes it
            tickN(62);

            GlobalTask t = world.taskPool.get(taskId);
            assertEquals(TaskState.COMPLETED, t.state,
                    "Without requirement check, task gets completed by any NPC");
        }

        @Test
        void reload_loadedTasksKeepOriginalId_singleOwnerPerTask() {
            // Regression: 退出世界重进后同一任务被多个 NPC 反复接取。
            // 根因：taskFromNbt 原先用 pool.addTask() 给任务分配临时 id，再用
            // addLoadedTask() 把同一对象 re-key 到保存的 id。GlobalTask.id 是 final，
            // 字段不会跟着变 → 任务 id 字段与池 key 不一致。调度器 assignLight(task.id)
            // 因此查到错误任务或 null，幽灵任务留在 assignableSet，每个心跳把同一个
            // 底层任务重新分配给一个新空闲 NPC。修复：加载直接用原始 id 建任务
            // （addTaskWithId），使 id 字段恒等于池 key。
            registerSimpleBp("test:load_a",
                    AtomicOp.TransformOp.place(center.add(10, 0, 0), BlockType.STONE),
                    AtomicOp.TransformOp.place(center.add(10, 1, 0), BlockType.STONE),
                    AtomicOp.TransformOp.place(center.add(10, 2, 0), BlockType.STONE),
                    AtomicOp.TransformOp.place(center.add(10, 3, 0), BlockType.STONE),
                    AtomicOp.TransformOp.place(center.add(10, 4, 0), BlockType.STONE),
                    AtomicOp.TransformOp.place(center.add(10, 5, 0), BlockType.STONE),
                    AtomicOp.TransformOp.place(center.add(10, 6, 0), BlockType.STONE),
                    AtomicOp.TransformOp.place(center.add(10, 7, 0), BlockType.STONE),
                    AtomicOp.TransformOp.place(center.add(10, 8, 0), BlockType.STONE),
                    AtomicOp.TransformOp.place(center.add(10, 9, 0), BlockType.STONE),
                    AtomicOp.TransformOp.place(center.add(10, 10, 0), BlockType.STONE),
                    AtomicOp.TransformOp.place(center.add(10, 11, 0), BlockType.STONE));
            registerSimpleBp("test:load_b",
                    AtomicOp.TransformOp.place(center.add(20, 0, 0), BlockType.STONE),
                    AtomicOp.TransformOp.place(center.add(20, 1, 0), BlockType.STONE),
                    AtomicOp.TransformOp.place(center.add(20, 2, 0), BlockType.STONE));
            // A third, far-away NPC that stays idle — the old ghost would hand the
            // same task to it every heartbeat.
            long spare = CoreBootstrap.createNpc(world, 50, 64, 50, colonyId, NpcAttributes.defaults());

            // Simulate the load path: two saved tasks whose saved ids (7 and 1) don't
            // line up with load order (7 loads first, so the old flow gave it temp id 1).
            long taskA = world.taskPool.addTaskWithId(makeRequest("test:load_a", center, 10), 7);
            long taskB = world.taskPool.addTaskWithId(makeRequest("test:load_b", center, 10), 1);
            world.taskPool.addLoadedTask(world.taskPool.get(taskA), 7);
            world.taskPool.addLoadedTask(world.taskPool.get(taskB), 1);

            // Invariant (root fix): a loaded task's id field always equals its pool key.
            assertEquals(7, world.taskPool.get(taskA).id,
                    "loaded task id must equal its pool key");
            assertEquals(1, world.taskPool.get(taskB).id,
                    "loaded task id must equal its pool key");

            // Both tasks get claimed by the two near NPCs; the far spare stays idle.
            tickN(4);
            GlobalTask a = world.taskPool.get(taskA);
            assertNotEquals(TaskState.PENDING_ASSIGN, a.state, "task A should be claimed");
            assertNotEquals(spare, (long) a.assignedNpcId, "spare must not steal task A");
            long ownerA = a.assignedNpcId;

            // Keep heartbeating while the spare remains idle — the owner must not rotate.
            tickN(8);
            GlobalTask a2 = world.taskPool.get(taskA);
            assertEquals(ownerA, (long) a2.assignedNpcId,
                    "task A must stay with its original owner across heartbeats");
            assertNotEquals(spare, (long) a2.assignedNpcId,
                    "task A must not be re-assigned to the spare idle NPC");
        }

        // ---- helpers ----
        private void registerSimpleBp(String id, AtomicOp... steps) {
            world.blueprintRegistry.register(id, new Blueprint(id,
                    (BlueprintSteps) p -> new TaskSequence(List.of(steps), id)));
        }

        private TaskRequest makeRequest(String blueprintId, GridPos pos, int priority) {
            Map<String, JsonElement> params = new HashMap<>();
            params.put("x", new JsonPrimitive(pos.x()));
            params.put("y", new JsonPrimitive(pos.y()));
            params.put("z", new JsonPrimitive(pos.z()));
            return new TaskRequest(blueprintId, params, priority);
        }

        private void tickN(int n) {
            for (int i = 0; i < n; i++) world.tick(1.0f);
        }
    }

    // ===================================================================
    // 2. RitualOp — begin→WAITING→poll→DONE lifecycle
    // ===================================================================

    @Nested
    class RitualOpTests {
        private MockBoundary mock;
        private World world;
        private UUID colonyId;
        private long npc;

        @BeforeEach
        void setUp() {
            mock = new MockBoundary();
            mock.seedWarehouse(ResourceId.STONE_BRICKS, 200);
            BlueprintRegistry blueprints = new BlueprintRegistry();
            CoreBootstrapConfig config = new CoreBootstrapConfig(mock, mock, mock, null, mock, List.of(), blueprints,
                    new SystemBlueprintRegistry(), false);
            world = CoreBootstrap.bootstrap(config);
            DefaultOpExecutors.registerAll(world.opExecutors);

            GridPos center = new GridPos(0, 64, 0);
            colonyId = UUID.randomUUID();
            CoreBootstrap.createColony(world, center.x(), center.y(), center.z(), 50);

            npc = CoreBootstrap.createNpc(world, 0, 64, 0, colonyId, NpcAttributes.defaults());
        }

        @Test
        void ritualOp_syncCompletesAndAdvancesStep() {
            // V2.5 async model: MockBoundary.beginRitual returns completedFuture
            // → RitualOp advances in one tick
            registerSimpleBp("test:ward_sync",
                    new AtomicOp.RitualOp(RitualId.WARDING, new GridPos(5, 64, 0), Collections.emptyMap()),
                    AtomicOp.TransformOp.place(new GridPos(5, 64, 0), BlockType.GLASS));

            long taskId = world.taskPool.addTask(
                    makeRequest("test:ward_sync", new GridPos(0, 64, 0), 10));
            tickN(10);

            GlobalTask task = world.taskPool.get(taskId);
            assertEquals(TaskState.COMPLETED, task.state,
                    "Should complete after ritual + TransformOp");
            assertFalse(mock.isAir(new GridPos(5, 64, 0)),
                    "TransformOp placed block after RitualOp DONE");
        }

        @Test
        void ritualOp_engineDoesNotReinvokeExecute() {
            // V2.5: execute() is called once for a RitualOp.
            // MockBoundary returns completedFuture → advances immediately.
            registerSimpleBp("test:ward_once",
                    new AtomicOp.RitualOp(RitualId.WARDING, new GridPos(10, 64, 0), Collections.emptyMap()));

            long taskId = world.taskPool.addTask(
                    makeRequest("test:ward_once", new GridPos(0, 64, 0), 10));
            tickN(10);

            GlobalTask task = world.taskPool.get(taskId);
            assertEquals(TaskState.COMPLETED, task.state,
                    "RitualOp should complete — execute() called once, returned completedFuture");
            TaskExecutor exec = world.get(npc, TaskExecutor.class);
            assertNull(exec.pendingFuture, "No pending future after sync ritual");
            assertNull(exec.globalTaskId, "Task released after completion");
        }

        // ---- helpers ----
        private void registerSimpleBp(String id, AtomicOp... steps) {
            world.blueprintRegistry.register(id, new Blueprint(id,
                    (BlueprintSteps) p -> new TaskSequence(List.of(steps), id)));
        }

        private TaskRequest makeRequest(String blueprintId, GridPos pos, int priority) {
            Map<String, JsonElement> params = new HashMap<>();
            params.put("x", new JsonPrimitive(pos.x()));
            params.put("y", new JsonPrimitive(pos.y()));
            params.put("z", new JsonPrimitive(pos.z()));
            return new TaskRequest(blueprintId, params, priority);
        }

        private void tickN(int n) {
            for (int i = 0; i < n; i++) world.tick(1.0f);
        }
    }

    // ===================================================================
    // 3. Private queue — priority, mixed pure/side-effect
    // ===================================================================

    @Nested
    class PrivateQueueTests {
        private MockBoundary mock;
        private World world;
        private UUID colonyId;
        private long npc;

        @BeforeEach
        void setUp() {
            mock = new MockBoundary();
            mock.seedWarehouse(ResourceId.STONE_BRICKS, 200);
            BlueprintRegistry blueprints = new BlueprintRegistry();
            CoreBootstrapConfig config = new CoreBootstrapConfig(mock, mock, mock, null, mock, List.of(), blueprints,
                    new SystemBlueprintRegistry(), false);
            world = CoreBootstrap.bootstrap(config);
            DefaultOpExecutors.registerAll(world.opExecutors);

            GridPos center = new GridPos(0, 64, 0);
            colonyId = UUID.randomUUID();
            CoreBootstrap.createColony(world, center.x(), center.y(), center.z(), 50);

            npc = CoreBootstrap.createNpc(world, 0, 64, 0, colonyId, NpcAttributes.defaults());
        }

        @Test
        void privateQueue_executesBeforeGlobalTask() {
            TaskExecutor exec = world.get(npc, TaskExecutor.class);
            exec.npcQueue.enqueueNormal(NpcTaskPackage.system("system:legacy",
                    AtomicOp.TransformOp.place(new GridPos(1, 64, 0), BlockType.STONE), null, 0));

            // Global task — won't be assigned until private queue drains,
            // because Scheduler skips NPCs with non-empty private queue
            registerSimpleBp("test:after_pq",
                    AtomicOp.TransformOp.place(new GridPos(2, 64, 0), BlockType.GLASS));
            world.taskPool.addTask(
                    makeRequest("test:after_pq", new GridPos(0, 64, 0), 10));

            tickN(15);

            // Both blocks placed — private first, then global
            assertFalse(mock.isAir(new GridPos(1, 64, 0)),
                    "Private queue op placed block");
            assertFalse(mock.isAir(new GridPos(2, 64, 0)),
                    "Global task op placed block (after private queue drained)");
        }

        @Test
        void privateQueue_drainsBeforeSchedulerAssignsGlobal() {
            // Private queue with op → scheduler skips this NPC → private drains → NPC idle → assigned
            TaskExecutor exec = world.get(npc, TaskExecutor.class);
            exec.npcQueue.enqueueNormal(NpcTaskPackage.system("system:legacy",
                    AtomicOp.TransformOp.place(new GridPos(3, 64, 0), BlockType.STONE), null, 0));

            // Before any ticks: private queue is non-empty
            assertFalse(exec.npcQueue.isIdle());

            // Tick once: TaskExec processes private op → DONE → pop
            world.tick(1.0f);

            assertTrue(exec.npcQueue.isIdle(),
                    "Private queue should be drained after one tick");
            assertEquals(ExecutorState.IDLE, exec.state,
                    "NPC should be IDLE after private queue drained");
        }

        @Test
        void privateQueue_ifConditionOp_skipCount_popsMultiple() {
            // Push: [IfConditionOp(elseSkip=true, skip=1), EmitEventOp(skipped), TransformOp]
            // NPC has no diamond → condition false → elseSkip triggers → advance by 2
            // → pops IfConditionOp + EmitEventOp → TransformOp remains
            TaskExecutor exec = world.get(npc, TaskExecutor.class);
            exec.npcQueue.enqueueNormal(NpcTaskPackage.system("system:legacy",
                    new AtomicOp.IfConditionOp("inventory_has",
                            Map.of("resource", "diamond"), 1, true), null, 0));
            exec.npcQueue.enqueueNormal(NpcTaskPackage.system("system:legacy",
                    new AtomicOp.EmitEventOp("to_skip", Map.of()), null, 0));
            exec.npcQueue.enqueueNormal(NpcTaskPackage.system("system:legacy",
                    AtomicOp.TransformOp.place(new GridPos(5, 64, 0), BlockType.STONE), null, 0));

            // Push a dummy global task so NPC has work after private queue drains
            registerSimpleBp("test:dummy_pq",
                    AtomicOp.TransformOp.place(new GridPos(6, 64, 0), BlockType.STONE));
            world.taskPool.addTask(
                    makeRequest("test:dummy_pq", new GridPos(0, 64, 0), 10));

            tickN(15);

            // The TransformOp at pos(5,64,0) should have executed
            // (it was the 3rd in private queue, and the IfConditionOp should have skipped EmitEventOp)
            assertFalse(mock.isAir(new GridPos(5, 64, 0)),
                    "TransformOp after IfConditionOp skip should execute");
        }

        // ---- helpers ----
        private void registerSimpleBp(String id, AtomicOp... steps) {
            world.blueprintRegistry.register(id, new Blueprint(id,
                    (BlueprintSteps) p -> new TaskSequence(List.of(steps), id)));
        }

        private TaskRequest makeRequest(String blueprintId, GridPos pos, int priority) {
            Map<String, JsonElement> params = new HashMap<>();
            params.put("x", new JsonPrimitive(pos.x()));
            params.put("y", new JsonPrimitive(pos.y()));
            params.put("z", new JsonPrimitive(pos.z()));
            return new TaskRequest(blueprintId, params, priority);
        }

        private void tickN(int n) {
            for (int i = 0; i < n; i++) world.tick(1.0f);
        }
    }

    // ===================================================================
    // 4. Approval flow — approve/reject lifecycle
    // ===================================================================

    @Nested
    class ApprovalTests {
        private World world;

        @BeforeEach
        void setUp() {
            MockBoundary mock = new MockBoundary();
            mock.seedWarehouse(ResourceId.STONE_BRICKS, 200);
            BlueprintRegistry blueprints = new BlueprintRegistry();
            // Register the blueprint BEFORE creating any TaskRequest that references it
            blueprints.register("test:any", new Blueprint("test:any",
                    (BlueprintSteps) p ->
                            TaskSequence.of("test:any",
                                    AtomicOp.TransformOp.place(
                                            new GridPos(0, 64, 0), BlockType.STONE))));
            CoreBootstrapConfig config = new CoreBootstrapConfig(mock, mock, mock, null, mock, List.of(), blueprints,
                    new SystemBlueprintRegistry(), false);
            world = CoreBootstrap.bootstrap(config);
            DefaultOpExecutors.registerAll(world.opExecutors);
        }

        @Test
        void highPriorityTask_startsPendingApproval() {
            long taskId = world.taskPool.addTask(new TaskRequest("test:any",
                    Map.of("x", new JsonPrimitive(0), "y", new JsonPrimitive(64), "z", new JsonPrimitive(0)), 60));
            assertEquals(TaskState.PENDING_APPROVAL, world.taskPool.get(taskId).state);
            assertNotNull(world.taskPool.get(taskId).approval);
        }

        @Test
        void lowPriorityTask_skipsApproval() {
            long taskId = world.taskPool.addTask(new TaskRequest("test:any",
                    Map.of("x", new JsonPrimitive(0), "y", new JsonPrimitive(64), "z", new JsonPrimitive(0)), 10));
            assertEquals(TaskState.PENDING_ASSIGN, world.taskPool.get(taskId).state,
                    "Priority < 50 should skip approval");
        }

        @Test
        void approve_movesToPendingAssign() {
            long taskId = world.taskPool.addTask(new TaskRequest("test:any",
                    Map.of("x", new JsonPrimitive(0), "y", new JsonPrimitive(64), "z", new JsonPrimitive(0)), 55));
            assertEquals(TaskState.PENDING_APPROVAL, world.taskPool.get(taskId).state);

            world.taskPool.approve(taskId);
            assertEquals(TaskState.PENDING_ASSIGN, world.taskPool.get(taskId).state);
        }

        @Test
        void reject_movesToCompleted() {
            long taskId = world.taskPool.addTask(new TaskRequest("test:any",
                    Map.of("x", new JsonPrimitive(0), "y", new JsonPrimitive(64), "z", new JsonPrimitive(0)), 60));
            assertEquals(TaskState.PENDING_APPROVAL, world.taskPool.get(taskId).state);

            world.taskPool.reject(taskId);
            assertEquals(TaskState.COMPLETED, world.taskPool.get(taskId).state);
        }

        @Test
        void pendingApprovalTasks_notScheduled() {
            // Create approved (low-prio) task — will be scheduled
            long approvedId = world.taskPool.addTask(new TaskRequest("test:any",
                    Map.of("x", new JsonPrimitive(0), "y", new JsonPrimitive(64), "z", new JsonPrimitive(0)), 10));
            // Create high-prio task — needs approval, stays PENDING_APPROVAL
            long pendingId = world.taskPool.addTask(new TaskRequest("test:any",
                    Map.of("x", new JsonPrimitive(0), "y", new JsonPrimitive(64), "z", new JsonPrimitive(0)), 60));

            // Create NPC and tick
            UUID colonyId = UUID.randomUUID();
            CoreBootstrap.createColony(world, 0, 64, 0, 50);
            CoreBootstrap.createNpc(world, 0, 64, 0, colonyId, NpcAttributes.defaults());

            tickN(10);

            assertEquals(TaskState.COMPLETED, world.taskPool.get(approvedId).state,
                    "Low-prio task should be scheduled and completed");
            assertEquals(TaskState.PENDING_APPROVAL, world.taskPool.get(pendingId).state,
                    "PENDING_APPROVAL task should NOT be scheduled");
        }

        private void tickN(int n) {
            for (int i = 0; i < n; i++) world.tick(1.0f);
        }
    }

    // ===================================================================
    // 5. TemplateResolver — edge cases (pure unit tests)
    // ===================================================================

    @Nested
    class TemplateResolverTests {

        @Test
        void nullTemplate_returnsNull() {
            assertNull(TemplateResolver.resolve(null, Map.of()));
        }

        @Test
        void noPlaceholders_returnsUnchanged() {
            assertEquals("hello world",
                    TemplateResolver.resolve("hello world", Map.of()));
        }

        @Test
        void singlePlaceholder_resolved() {
            assertEquals("hello Alice",
                    TemplateResolver.resolve("hello {{name}}", Map.of("name", "Alice")));
        }

        @Test
        void multipleDifferentPlaceholders_allResolved() {
            assertEquals("Hello World!",
                    TemplateResolver.resolve("{{greeting}} {{name}}!",
                            Map.of("greeting", "Hello", "name", "World")));
        }

        @Test
        void samePlaceholderRepeated_allResolved() {
            assertEquals("7-7-7",
                    TemplateResolver.resolve("{{x}}-{{x}}-{{x}}", Map.of("x", "7")));
        }

        @Test
        void unmatchedPlaceholder_keptLiteral() {
            assertEquals("yes {{unknown}}",
                    TemplateResolver.resolve("{{known}} {{unknown}}",
                            Map.of("known", "yes")));
        }

        @Test
        void emptyVariableMap_allPlaceholdersKept() {
            assertEquals("{{a}} {{b}}",
                    TemplateResolver.resolve("{{a}} {{b}}", Map.of()));
        }

        @Test
        void emptyString_returnsEmpty() {
            assertEquals("", TemplateResolver.resolve("", Map.of("x", "y")));
        }

        @Test
        void adjacentPlaceholders_resolved() {
            assertEquals("AB",
                    TemplateResolver.resolve("{{a}}{{b}}", Map.of("a", "A", "b", "B")));
        }

        @Test
        void resolveMap_allValuesResolved() {
            Map<String, String> result = TemplateResolver.resolveMap(
                    Map.of("k1", "{{v1}}", "k2", "literal", "k3", "{{v1}}-{{v2}}"),
                    Map.of("v1", "X", "v2", "Y"));
            assertEquals("X", result.get("k1"));
            assertEquals("literal", result.get("k2"));
            assertEquals("X-Y", result.get("k3"));
            assertEquals(3, result.size());
        }

        @Test
        void resolveMap_nullInput_returnsEmpty() {
            assertEquals(Map.of(), TemplateResolver.resolveMap(null, Map.of("a", "b")));
        }

        @Test
        void resolveMap_emptyInput_returnsEmpty() {
            assertEquals(Map.of(), TemplateResolver.resolveMap(Map.of(), Map.of("a", "b")));
        }

        @Test
        void singleBraces_notTreatedAsPlaceholder() {
            assertEquals("{not_a_var}",
                    TemplateResolver.resolve("{not_a_var}", Map.of("not_a_var", "x")));
        }
    }

    // ===================================================================
    // 7. Task interrupt — records and releases NPC
    // ===================================================================

    @Nested
    class InterruptTests {
        private World world;
        private UUID colonyId;
        private long npc;

        @BeforeEach
        void setUp() {
            MockBoundary mock = new MockBoundary();
            mock.seedWarehouse(ResourceId.STONE_BRICKS, 200);
            BlueprintRegistry blueprints = new BlueprintRegistry();
            CoreBootstrapConfig config = new CoreBootstrapConfig(mock, mock, mock, null, mock, List.of(), blueprints,
                    new SystemBlueprintRegistry(), false);
            world = CoreBootstrap.bootstrap(config);
            DefaultOpExecutors.registerAll(world.opExecutors);

            GridPos center = new GridPos(0, 64, 0);
            colonyId = UUID.randomUUID();
            CoreBootstrap.createColony(world, center.x(), center.y(), center.z(), 50);

            npc = CoreBootstrap.createNpc(world, 0, 64, 0, colonyId, NpcAttributes.defaults());
        }

        @Test
        void interrupt_recordsHistoryAndReleasesNpc() {
            GlobalTask task = GlobalTask.createSmall(0,
                    TaskSequence.of("Interruptible",
                            AtomicOp.TransformOp.place(new GridPos(5, 64, 0), BlockType.STONE),
                            AtomicOp.TransformOp.place(new GridPos(5, 65, 0), BlockType.STONE)),
                    10, List.of(), Map.of());

            long taskId = world.taskPool.addTask(task);
            world.taskPool.assignLight(taskId, npc, world);
            TaskExecutor exec = world.get(npc, TaskExecutor.class);
            GridPos stance = TaskExecutionSystem.computeTaskStance(task.sequence);
            exec.npcQueue.enqueueNormal(NpcTaskPackage.of("global:" + taskId, task.sequence, stance, task.priority));

            assertEquals(TaskState.IN_PROGRESS, world.taskPool.get(taskId).state);
            assertEquals(npc, (long) world.taskPool.get(taskId).assignedNpcId);

            long now = System.currentTimeMillis();
            task.interrupt(npc, now);

            assertEquals(TaskState.PENDING_ASSIGN, task.state,
                    "Interrupted task back to PENDING_ASSIGN");
            assertNull(task.assignedNpcId, "NPC released from task");
            assertEquals(1, task.interruptHistory.size());
            InterruptRecord rec = task.interruptHistory.peekFirst();
            assertEquals(npc, rec.npcId());
            assertEquals(now, rec.timestamp());

            exec.releaseGlobalTask();
            assertEquals(ExecutorState.IDLE, exec.state);
        }

        @Test
        void interruptedTask_preservesStepIndex() {
            // Advance task 1 step, then interrupt — stepIndex should be preserved
            GlobalTask task = GlobalTask.createSmall(0,
                    TaskSequence.of("Multi-step",
                            AtomicOp.TransformOp.place(new GridPos(10, 64, 0), BlockType.STONE),
                            AtomicOp.TransformOp.place(new GridPos(10, 65, 0), BlockType.STONE),
                            AtomicOp.TransformOp.place(new GridPos(10, 66, 0), BlockType.GLASS)),
                    10, List.of(), Map.of());

            world.taskPool.addTask(task);
            world.taskPool.assignLight(task.id, npc, world);
            TaskExecutor exec = world.get(npc, TaskExecutor.class);
            GridPos stance = TaskExecutionSystem.computeTaskStance(task.sequence);
            exec.npcQueue.enqueueNormal(NpcTaskPackage.of("global:" + task.id, task.sequence, stance, task.priority));
            // Advance to step 1 manually
            task.stepIndex = 1;

            task.interrupt(npc, System.currentTimeMillis());
            assertEquals(1, task.stepIndex,
                    "StepIndex preserved after interrupt (resume from where left off)");
            assertEquals(1, task.interruptHistory.size());
            assertEquals(1, task.interruptHistory.peekFirst().atStepIndex());
        }
    }

    // ===================================================================
    // 8. 跟随模式 — 调度器不派任务 + 执行系统释放手头任务（保留自防御个人包）
    // ===================================================================

    @Nested
    class FollowModeTests {
        private MockBoundary mock;
        private World world;
        private UUID colonyId;
        private GridPos center;

        @BeforeEach
        void setUp() {
            mock = new MockBoundary();
            mock.seedWarehouse(ResourceId.STONE_BRICKS, 200);
            BlueprintRegistry blueprints = new BlueprintRegistry();
            CoreBootstrapConfig config = new CoreBootstrapConfig(mock, mock, mock, null, mock, List.of(), blueprints,
                    new SystemBlueprintRegistry(), false);
            world = CoreBootstrap.bootstrap(config);
            DefaultOpExecutors.registerAll(world.opExecutors);

            center = new GridPos(0, 64, 0);
            colonyId = UUID.randomUUID();
            CoreBootstrap.createColony(world, center.x(), center.y(), center.z(), 50);
        }

        @Test
        void followingNpcIsNeverAssignedTask() {
            // 唯一 NPC 处于跟随模式 → 任务必须保持待分派，不被接走
            long npc = CoreBootstrap.createNpc(world, 1, 64, 0, colonyId, NpcAttributes.defaults());
            mock.setFollowing(npc, true);

            registerSimpleBp("test:follow_only",
                    AtomicOp.TransformOp.place(center.add(5, 0, 0), BlockType.STONE));
            long taskId = world.taskPool.addTask(
                    makeRequest("test:follow_only", center, 10));

            tickN(20);

            assertEquals(TaskState.PENDING_ASSIGN, world.taskPool.get(taskId).state,
                    "跟随 NPC 不接任务 → 任务保持待分派");
            assertNull(world.get(npc, TaskExecutor.class).globalTaskId,
                    "跟随 NPC 始终未被分派");
        }

        @Test
        void followingNpcExcludedButFreeNpcTakesTask() {
            // 跟随 NPC 在任务目标旁（若可接必中），空闲 NPC 远离 → 任务由空闲 NPC 完成
            long npcFollow = CoreBootstrap.createNpc(world, 1, 64, 0, colonyId, NpcAttributes.defaults());
            long npcFree = CoreBootstrap.createNpc(world, 50, 64, 50, colonyId, NpcAttributes.defaults());
            mock.setFollowing(npcFollow, true);

            registerSimpleBp("test:follow_free",
                    AtomicOp.TransformOp.place(center.add(5, 0, 0), BlockType.STONE));
            long taskId = world.taskPool.addTask(
                    makeRequest("test:follow_free", center, 10));

            tickN(20);

            assertEquals(TaskState.COMPLETED, world.taskPool.get(taskId).state,
                    "空闲 NPC 接取了任务");
            assertFalse(mock.isAir(center.add(5, 0, 0)), "任务由空闲 NPC 完成执行");
            assertNull(world.get(npcFollow, TaskExecutor.class).globalTaskId,
                    "跟随 NPC 始终未被分派");
        }

        @Test
        void enablingFollowReleasesInHandTask() {
            // 模拟调度器已分派全局任务并推进了一步；开启跟随 → 手头任务释放回任务池
            long npc = CoreBootstrap.createNpc(world, 1, 64, 0, colonyId, NpcAttributes.defaults());
            TaskExecutor exec = world.get(npc, TaskExecutor.class);

            GlobalTask task = GlobalTask.createSmall(0,
                    TaskSequence.of("Build",
                            AtomicOp.TransformOp.place(new GridPos(5, 64, 0), BlockType.STONE),
                            AtomicOp.TransformOp.place(new GridPos(5, 65, 0), BlockType.STONE),
                            AtomicOp.TransformOp.place(new GridPos(5, 66, 0), BlockType.STONE)),
                    10, List.of(), Map.of());
            long taskId = world.taskPool.addTask(task);
            world.taskPool.assignLight(taskId, npc, world);
            GridPos stance = TaskExecutionSystem.computeTaskStance(task.sequence);
            exec.npcQueue.enqueueNormal(
                    NpcTaskPackage.of("global:" + taskId, task.sequence, stance, task.priority));

            tickN(2); // 绑定全局任务并推进几步，仍 IN_PROGRESS
            assertEquals(TaskState.IN_PROGRESS, world.taskPool.get(taskId).state);
            assertNotNull(exec.globalTaskId);

            mock.setFollowing(npc, true);
            tickN(2);

            assertEquals(TaskState.PENDING_ASSIGN, world.taskPool.get(taskId).state,
                    "跟随开启后手头全局任务被释放回任务池");
            assertNull(exec.globalTaskId, "NPC 解除全局任务绑定");
            assertTrue(exec.npcQueue.isIdle(), "NPC 队列无 global 包残留");
        }

        // ---- helpers ----
        private void registerSimpleBp(String id, AtomicOp... steps) {
            world.blueprintRegistry.register(id, new Blueprint(id,
                    (BlueprintSteps) p -> new TaskSequence(List.of(steps), id)));
        }

        private TaskRequest makeRequest(String blueprintId, GridPos pos, int priority) {
            Map<String, JsonElement> params = new HashMap<>();
            params.put("x", new JsonPrimitive(pos.x()));
            params.put("y", new JsonPrimitive(pos.y()));
            params.put("z", new JsonPrimitive(pos.z()));
            return new TaskRequest(blueprintId, params, priority);
        }

        private void tickN(int n) {
            for (int i = 0; i < n; i++) world.tick(1.0f);
        }
    }

}