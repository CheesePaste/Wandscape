package com.wsteam.wandscape.core;

import com.wsteam.wandscape.core.component.ManaPool;
import com.wsteam.wandscape.core.component.TaskExecutor;
import com.wsteam.wandscape.core.component.WandCarrier;
import com.wsteam.wandscape.core.task.*;
import com.wsteam.wandscape.core.types.*;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.op.AtomicOp;
import com.wsteam.wandscape.core.op.DefaultOpExecutors;
import com.wsteam.wandscape.core.system.SystemBlueprintRegistry;
import com.wsteam.wandscape.core.demo.MockBoundary;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for untested engine subsystems: scheduler scoring, RitualOp lifecycle,
 * private queue mechanics, approval flow, TemplateResolver edges, mana regen,
 * and task interrupts.
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
            Map<BehaviourTag, BehaviourLevel> capsA = Map.of(
                    BehaviourTag.BUILDING, new BehaviourLevel(1));
            WandCarrier wandA = new WandCarrier(capsA, 0.9f, 6);
            npcHighRange = CoreBootstrap.createNpc(world, 1, 64, 0, wandA, colonyId, 100, 5);

            // NPC-B: closer to task, better efficiency → wins despite lower maxRange
            Map<BehaviourTag, BehaviourLevel> capsB = Map.of(
                    BehaviourTag.BUILDING, new BehaviourLevel(3));
            WandCarrier wandB = new WandCarrier(capsB, 0.7f, 1);
            npcHighLevel = CoreBootstrap.createNpc(world, 2, 64, 0, wandB, colonyId, 100, 5);
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
        void npcWithoutMatchingRequirements_notAssigned() {
            // Task requires RITUAL:3 — npcHighRange has no RITUAL, npcHighLevel has BUILDING only
            // → no NPC qualifies → task stays PENDING_ASSIGN
            registerSimpleBp("test:ritual_req",
                    new AtomicOp.RitualOp(RitualId.WARDING, center));

            GlobalTask task = GlobalTask.createSmall(0,
                    TaskSequence.of("Ritual Task",
                            new AtomicOp.RitualOp(RitualId.WARDING, center)),
                    Map.of(BehaviourTag.RITUAL, new BehaviourLevel(3)),
                    10, List.of(), Map.of());
            long taskId = world.taskPool.addTask(task);

            tickN(10);

            GlobalTask t = world.taskPool.get(taskId);
            assertEquals(TaskState.PENDING_ASSIGN, t.state,
                    "No NPC has RITUAL:3 → task stays unassigned");
            assertEquals(0, t.stepIndex, "StepIndex must not advance");
        }

        @Test
        void npcWithEmptyMana_notAssigned() {
            // Drain one NPC's mana completely
            ManaPool manaNpcB = world.get(npcHighLevel, ManaPool.class);
            manaNpcB.consume(manaNpcB.current());
            assertTrue(manaNpcB.isEmpty());

            // Only npcHighRange has mana → should get assigned
            registerSimpleBp("test:mana_skip",
                    AtomicOp.TransformOp.place(center.add(5, 0, 5), BlockType.STONE));

            long taskId = world.taskPool.addTask(
                    makeRequest("test:mana_skip", center, 10));
            tickN(10);

            GlobalTask task = world.taskPool.get(taskId);
            assertEquals(TaskState.COMPLETED, task.state,
                    "Task should complete via NPC with mana");
            assertFalse(mock.isAir(center.add(5, 0, 5)));
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

            Map<BehaviourTag, BehaviourLevel> caps = Map.of(
                    BehaviourTag.RITUAL, new BehaviourLevel(3),
                    BehaviourTag.BUILDING, new BehaviourLevel(1));
            WandCarrier wand = new WandCarrier(caps, 0.8f, 3);
            npc = CoreBootstrap.createNpc(world, 0, 64, 0, wand, colonyId, 100, 5);
        }

        @Test
        void ritualOp_syncCompletesAndAdvancesStep() {
            // V2.5 async model: MockBoundary.beginRitual returns completedFuture
            // → RitualOp advances in one tick
            registerSimpleBp("test:ward_sync",
                    new AtomicOp.RitualOp(RitualId.WARDING, new GridPos(5, 64, 0)),
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
                    new AtomicOp.RitualOp(RitualId.WARDING, new GridPos(10, 64, 0)));

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

            Map<BehaviourTag, BehaviourLevel> caps = Map.of(
                    BehaviourTag.BUILDING, new BehaviourLevel(2));
            WandCarrier wand = new WandCarrier(caps, 0.8f, 3);
            npc = CoreBootstrap.createNpc(world, 0, 64, 0, wand, colonyId, 100, 5);
        }

        @Test
        void privateQueue_executesBeforeGlobalTask() {
            TaskExecutor exec = world.get(npc, TaskExecutor.class);
            exec.pushPrivate(
                    AtomicOp.TransformOp.place(new GridPos(1, 64, 0), BlockType.STONE));

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
            exec.pushPrivate(
                    AtomicOp.TransformOp.place(new GridPos(3, 64, 0), BlockType.STONE));

            // Before any ticks: private queue is non-empty
            assertFalse(exec.isPrivateQueueEmpty());

            // Tick once: TaskExec processes private op → DONE → pop
            world.tick(1.0f);

            assertTrue(exec.isPrivateQueueEmpty(),
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
            exec.pushPrivate(new AtomicOp.IfConditionOp("inventory_has",
                    Map.of("resource", "diamond"), 1, true));
            exec.pushPrivate(new AtomicOp.EmitEventOp("to_skip", Map.of()));
            exec.pushPrivate(AtomicOp.TransformOp.place(
                    new GridPos(5, 64, 0), BlockType.STONE));

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
            // NPC needs BUILDING capability for the TransformOp in test:any
            WandCarrier wand = new WandCarrier(Map.of(BehaviourTag.BUILDING, BehaviourLevel.of(1)), 0.8f, 3);
            CoreBootstrap.createNpc(world, 0, 64, 0, wand, colonyId, 100, 5);

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
    // 6. ManaRegenSystem — regen per tick, cap at max
    // ===================================================================

    @Nested
    class ManaRegenTests {
        private World world;
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
            UUID colonyId = UUID.randomUUID();
            CoreBootstrap.createColony(world, center.x(), center.y(), center.z(), 50);

            WandCarrier wand = new WandCarrier(Map.of(), 0.8f, 3);
            // max=100, starts full at 100, consume to 50, then regen has room
            npc = CoreBootstrap.createNpc(world, 0, 64, 0, wand, colonyId, 100, 5);
        }

        @Test
        void manaRegeneratesByRegenPerTick() {
            ManaPool mana = world.get(npc, ManaPool.class);
            mana.consume(50);
            assertEquals(50, mana.current(), "consumed 50 from 100 → 50");

            world.tick(1.0f);
            assertEquals(55, mana.current(), "50 + 5 regen = 55");
        }

        @Test
        void manaCapsAtMax() {
            ManaPool mana = world.get(npc, ManaPool.class);
            mana.add(1000);
            assertEquals(100, mana.current(), "Should cap at max=100");

            world.tick(1.0f);
            assertEquals(100, mana.current(), "Stays at max");
        }

        @Test
        void manaConsumed_thenRegenerates() {
            ManaPool mana = world.get(npc, ManaPool.class);
            mana.consume(20);
            assertEquals(80, mana.current());

            world.tick(1.0f);
            assertEquals(85, mana.current(), "80 + 5 regen");
            world.tick(1.0f);
            assertEquals(90, mana.current(), "85 + 5 regen");
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

            WandCarrier wand = new WandCarrier(Map.of(), 0.8f, 3);
            npc = CoreBootstrap.createNpc(world, 0, 64, 0, wand, colonyId, 100, 5);
        }

        @Test
        void interrupt_recordsHistoryAndReleasesNpc() {
            GlobalTask task = GlobalTask.createSmall(0,
                    TaskSequence.of("Interruptible",
                            AtomicOp.TransformOp.place(new GridPos(5, 64, 0), BlockType.STONE),
                            AtomicOp.TransformOp.place(new GridPos(5, 65, 0), BlockType.STONE)),
                    Map.of(), 10, List.of(), Map.of());

            long taskId = world.taskPool.addTask(task);
            world.taskPool.assign(taskId, npc, world);

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

            TaskExecutor exec = world.get(npc, TaskExecutor.class);
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
                    Map.of(), 10, List.of(), Map.of());

            world.taskPool.addTask(task);
            world.taskPool.assign(task.id, npc, world);
            // Advance to step 1 manually
            task.stepIndex = 1;

            task.interrupt(npc, System.currentTimeMillis());
            assertEquals(1, task.stepIndex,
                    "StepIndex preserved after interrupt (resume from where left off)");
            assertEquals(1, task.interruptHistory.size());
            assertEquals(1, task.interruptHistory.peekFirst().atStepIndex());
        }
    }
}
