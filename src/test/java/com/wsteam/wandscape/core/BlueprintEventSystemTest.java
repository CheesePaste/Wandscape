package com.wsteam.wandscape.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.magiccolony.core.Engine;
import org.magiccolony.core.EngineConfig;
import org.magiccolony.core.component.*;
import org.magiccolony.core.ecs.World;
import org.magiccolony.core.event.CustomEvent;
import org.magiccolony.core.op.AtomicOp;
import org.magiccolony.core.op.DefaultOpExecutors;
import org.magiccolony.core.system.PlayerManualSource;
import org.magiccolony.core.system.SystemBlueprintRegistry;
import org.magiccolony.core.task.*;
import org.magiccolony.core.types.*;
import org.magiccolony.demo.MockBoundary;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for the V2 event-driven blueprint system.
 *
 * <p>Covers:
 * <ul>
 *   <li>EmitEventOp — template resolution (taskId, npcId, task.params.*, pos.*)</li>
 *   <li>IfConditionOp — skip, elseSkip, bounds (overflow → complete)</li>
 *   <li>Pure/side-effect batch — multiple pure ops in one tick</li>
 *   <li>TriggerDeclaration — filter, paramMapping, sourceBlueprintId template, dedup</li>
 *   <li>EventBus deferred removal — EmitEventOp as last step doesn't lose events</li>
 *   <li>Trigger lifecycle — subscribe on assign, unsubscribe on complete</li>
 *   <li>System blueprint permanent triggers + heartbeat steps</li>
 * </ul>
 */
public class BlueprintEventSystemTest {

    private MockBoundary mock;
    private World world;
    private PlayerManualSource manualSource;
    private UUID colonyId;
    private long npc;
    private GridPos center;

    private final List<CustomEvent> capturedEvents = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        mock = new MockBoundary();
        mock.seedWarehouse(ResourceId.STONE_BRICKS, 200);
        mock.seedWarehouse(ResourceId.WOOD, 200);
        mock.seedWarehouse(ResourceId.STONE, 200);

        BlueprintRegistry blueprints = new BlueprintRegistry();

        EngineConfig config = new EngineConfig(
                mock, mock, mock, mock,
                List.of(), blueprints,
                new SystemBlueprintRegistry()
        );
        world = Engine.bootstrap(config);
        DefaultOpExecutors.registerAll(world.opExecutors);
        manualSource = new PlayerManualSource(world.taskPool);

        world.eventBus.subscribe(CustomEvent.class, capturedEvents::add);

        center = new GridPos(0, 64, 0);
        colonyId = UUID.randomUUID();
        Engine.createColony(world, center.x(), center.y(), center.z(), 50);

        Map<BehaviourTag, BehaviourLevel> caps = Map.of(
                BehaviourTag.BUILDING, new BehaviourLevel(2),
                BehaviourTag.RITUAL, new BehaviourLevel(1));
        WandCarrier wand = new WandCarrier(caps, 0.8f, 3);
        npc = Engine.createNpc(world, 0, 64, 0, wand, colonyId, 100, 5);
    }

    // ===================================================================
    // 1. EmitEventOp — template resolution
    // ===================================================================

    @Test
    void emitEventOp_resolvesAllTemplateVariables() {
        registerBp("test:emit_all", List.of(),
                new AtomicOp.EmitEventOp("test_event",
                        Map.of("taskId", "{{taskId}}",
                                "npcId", "{{npcId}}",
                                "myX", "{{task.params.x}}",
                                "px", "{{pos.x}}",
                                "py", "{{pos.y}}",
                                "pz", "{{pos.z}}")));

        long taskId = manualSource.publish(
                makeRequest("test:emit_all", center.add(0, 0, 0), Map.of("x", "10"), 10));
        tickN(10);

        assertEquals(TaskState.COMPLETED, world.taskPool.get(taskId).state);

        Optional<CustomEvent> opt = capturedEvents.stream()
                .filter(e -> "test_event".equals(e.name())).findFirst();
        assertTrue(opt.isPresent(), "Should have emitted test_event");
        CustomEvent e = opt.get();
        assertEquals(String.valueOf(taskId), e.params().get("taskId"));
        assertEquals(String.valueOf(npc), e.params().get("npcId"));
        assertEquals("10", e.params().get("myX"), "{{task.params.x}} should resolve to '10'");
        // NPC created at (0,64,0)
        assertEquals("0", e.params().get("px"), "NPC pos.x=0");
        assertEquals("64", e.params().get("py"), "NPC pos.y=64");
        assertEquals("0", e.params().get("pz"), "NPC pos.z=0");
    }

    @Test
    void emitEventOp_preservesLiteralParams() {
        registerBp("test:emit_literal", List.of(),
                new AtomicOp.EmitEventOp("planted",
                        Map.of("crop", "wheat", "stage", "3")));

        manualSource.publish(makeRequest("test:emit_literal", center, Map.of(), 10));
        tickN(10);

        Optional<CustomEvent> opt = capturedEvents.stream()
                .filter(e -> "planted".equals(e.name())).findFirst();
        assertTrue(opt.isPresent());
        assertEquals("wheat", opt.get().params().get("crop"));
        assertEquals("3", opt.get().params().get("stage"));
    }

    @Test
    void emitEventOp_unresolvedPlaceholder_keptLiteral() {
        registerBp("test:emit_badvar", List.of(),
                new AtomicOp.EmitEventOp("test",
                        Map.of("unknown", "{{no_such_var}}")));

        manualSource.publish(makeRequest("test:emit_badvar", center, Map.of(), 10));
        tickN(10);

        Optional<CustomEvent> opt = capturedEvents.stream()
                .filter(e -> "test".equals(e.name())).findFirst();
        assertTrue(opt.isPresent());
        assertEquals("{{no_such_var}}", opt.get().params().get("unknown"),
                "Unresolved placeholder stays as-is for debugging");
    }

    // ===================================================================
    // 2. IfConditionOp — skip, elseSkip, bounds
    // ===================================================================

    @Test
    void ifConditionOp_skipOnTrue() {
        // inventory_has "diamond" — NPC has none → condition=false → no skip → "skipped" emitted
        registerBp("test:if_skip", List.of(),
                new AtomicOp.IfConditionOp("inventory_has",
                        Map.of("resource", "diamond"), 1, false),
                new AtomicOp.EmitEventOp("skipped", Map.of("marker", "A")),
                new AtomicOp.EmitEventOp("not_skipped", Map.of("marker", "B")));

        manualSource.publish(makeRequest("test:if_skip", center, Map.of(), 10));
        tickN(10);

        assertTrue(capturedEvents.stream().anyMatch(e -> "skipped".equals(e.name())),
                "condition false → no skip → 'skipped' emitted");
        assertTrue(capturedEvents.stream().anyMatch(e -> "not_skipped".equals(e.name())),
                "'not_skipped' also emitted");
    }

    @Test
    void ifConditionOp_elseSkip_skipsWhenFalse() {
        // inventory_has "diamond" → false → elseSkip=true triggers skip → skip 1 → "skipped" skipped
        registerBp("test:if_else", List.of(),
                new AtomicOp.IfConditionOp("inventory_has",
                        Map.of("resource", "diamond"), 1, true),
                new AtomicOp.EmitEventOp("skipped", Map.of("marker", "A")),
                new AtomicOp.EmitEventOp("kept", Map.of("marker", "B")));

        manualSource.publish(makeRequest("test:if_else", center, Map.of(), 10));
        tickN(10);

        assertFalse(capturedEvents.stream().anyMatch(e -> "skipped".equals(e.name())),
                "elseSkip=true, condition false → skip → 'skipped' NOT emitted");
        assertTrue(capturedEvents.stream().anyMatch(e -> "kept".equals(e.name())),
                "'kept' emitted after skip");
    }

    @Test
    void ifConditionOp_skipBounds_taskCompletes() {
        // skipCount=100 on 2-step sequence → overflow → task complete, "never" not emitted
        registerBp("test:if_overflow", List.of(),
                new AtomicOp.IfConditionOp("inventory_has",
                        Map.of("resource", "diamond"), 100, true),
                new AtomicOp.EmitEventOp("never", Map.of()));

        long taskId = manualSource.publish(
                makeRequest("test:if_overflow", center, Map.of(), 10));
        tickN(10);

        assertEquals(TaskState.COMPLETED, world.taskPool.get(taskId).state,
                "Overflow skipCount → task complete");
        assertFalse(capturedEvents.stream().anyMatch(e -> "never".equals(e.name())),
                "EmitEventOp skipped entirely");
    }

    // ===================================================================
    // 3. Pure op batch — multiple pure ops in one tick
    // ===================================================================

    @Test
    void pureOpsExecuteContinuouslyInOneTick() {
        // 4 pure ops: 2 IfConditionOp + 2 EmitEventOp
        // After task is assigned and one execution tick, all run in one loop iteration
        registerBp("test:batch_pure", List.of(),
                new AtomicOp.IfConditionOp("inventory_has",
                        Map.of("resource", "diamond"), 0, false),
                new AtomicOp.EmitEventOp("batch1", Map.of("n", "1")),
                new AtomicOp.IfConditionOp("inventory_has",
                        Map.of("resource", "diamond"), 0, true),
                new AtomicOp.EmitEventOp("batch2", Map.of("n", "2")));

        long taskId = manualSource.publish(
                makeRequest("test:batch_pure", center, Map.of(), 10));

        // Tick 2: Scheduler heartbeat → assign. Ticks 3+: TaskExec runs.
        // Pure ops all execute in one tick → task complete by tick 3 or 4.
        tickN(10);

        assertEquals(TaskState.COMPLETED, world.taskPool.get(taskId).state,
                "All pure ops should complete in one execution tick");
        assertTrue(capturedEvents.stream().anyMatch(e -> "batch1".equals(e.name())),
                "batch1 emitted");
        assertTrue(capturedEvents.stream().anyMatch(e -> "batch2".equals(e.name())),
                "batch2 emitted");
    }

    @Test
    void pureOps_stopAtSideEffect_thenResumeNextTick() {
        // Pure ops then a side-effect op: pure ops run, TransformOp runs once, then loop exits
        registerBp("test:mixed", List.of(),
                new AtomicOp.EmitEventOp("before_transform", Map.of("n", "1")),
                AtomicOp.TransformOp.place(center.add(0, 0, 1), BlockType.STONE),
                new AtomicOp.EmitEventOp("after_transform", Map.of("n", "2")));

        long taskId = manualSource.publish(
                makeRequest("test:mixed", center, Map.of(), 10));
        tickN(15);

        assertEquals(TaskState.COMPLETED, world.taskPool.get(taskId).state);
        assertTrue(capturedEvents.stream().anyMatch(e -> "before_transform".equals(e.name())),
                "Pure op before side-effect runs");
        assertTrue(capturedEvents.stream().anyMatch(e -> "after_transform".equals(e.name())),
                "Pure op after side-effect runs (next tick)");
        assertFalse(mock.isAir(center.add(0, 0, 1)), "Block placed by TransformOp");
    }

    // ===================================================================
    // 4. TriggerDeclaration — filter, paramMapping, template, dedup
    // ===================================================================

    @Test
    void triggerDeclaration_filterMatchesAndCreatesDownstreamTask() {
        registerBp("test:plant_wheat",
                List.of(new TriggerDeclaration(
                        "planted",
                        Map.of("crop", "wheat"),
                        "test:harvest_wheat", 10,
                        Map.of("x", "x", "y", "y"),
                        null)),
                new AtomicOp.EmitEventOp("planted",
                        Map.of("crop", "wheat", "x", "10", "y", "64")));

        registerBp("test:harvest_wheat", List.of(),
                AtomicOp.TransformOp.place(center.add(0, 1, 0), BlockType.STONE));

        long taskId = manualSource.publish(
                makeRequest("test:plant_wheat", center, Map.of(), 10));
        tickN(10);

        assertEquals(TaskState.COMPLETED, world.taskPool.get(taskId).state);

        GlobalTask harvest = findTaskByBpId("test:harvest_wheat");
        assertNotNull(harvest, "Trigger should create downstream harvest task");
        assertEquals(10, harvest.priority);
    }

    @Test
    void triggerDeclaration_filterMismatch_noTaskCreated() {
        registerBp("test:plant_corn",
                List.of(new TriggerDeclaration(
                        "planted",
                        Map.of("crop", "wheat"),  // filter demands wheat
                        "test:harvest_wheat", 10,
                        Map.of(),
                        null)),
                new AtomicOp.EmitEventOp("planted",
                        Map.of("crop", "corn")));  // event has corn

        registerBp("test:harvest_wheat", List.of(),
                AtomicOp.TransformOp.place(center.add(0, 1, 0), BlockType.STONE));

        manualSource.publish(makeRequest("test:plant_corn", center, Map.of(), 10));
        tickN(10);

        assertNull(findTaskByBpId("test:harvest_wheat"),
                "Filter mismatch should NOT create downstream task");
    }

    @Test
    void triggerDeclaration_sourceBlueprintIdTemplate() {
        registerBp("test:need_gather",
                List.of(new TriggerDeclaration(
                        "resource_shortage",
                        Map.of(),
                        "gather:{{event.resource}}", 30,
                        Map.of("amount", "amount"),
                        null)),
                new AtomicOp.EmitEventOp("resource_shortage",
                        Map.of("resource", "stone_bricks", "amount", "64")));

        registerBp("gather:stone_bricks", List.of(),
                AtomicOp.TransformOp.place(center.add(1, 0, 0), BlockType.STONE_BRICKS));

        manualSource.publish(makeRequest("test:need_gather", center, Map.of(), 10));
        tickN(10);

        GlobalTask gather = findTaskByBpId("gather:stone_bricks");
        assertNotNull(gather, "{{event.resource}} template should resolve");
        assertEquals(30, gather.priority);
        assertEquals("64", gather.taskParams.get("amount"),
                "paramMapping: amount → amount");
    }

    @Test
    void triggerDeclaration_paramMapping_keyRename() {
        registerBp("test:mapper",
                List.of(new TriggerDeclaration(
                        "mapped", Map.of(),
                        "test:downstream", 20,
                        Map.of("src_x", "x", "src_y", "y"),
                        null)),
                new AtomicOp.EmitEventOp("mapped",
                        Map.of("src_x", "3", "src_y", "5", "extra", "ignored")));

        registerBp("test:downstream", List.of(),
                AtomicOp.TransformOp.place(center, BlockType.STONE));

        manualSource.publish(makeRequest("test:mapper", center, Map.of(), 10));
        tickN(10);

        GlobalTask downstream = findTaskByBpId("test:downstream");
        assertNotNull(downstream, "Trigger should create downstream task");
        assertEquals("3", downstream.taskParams.get("x"), "src_x→x");
        assertEquals("5", downstream.taskParams.get("y"), "src_y→y");
        assertNull(downstream.taskParams.get("extra"), "unmapped key absent");
        assertNull(downstream.taskParams.get("src_x"), "original key renamed");
    }

    @Test
    void triggerDeclaration_emptyMapping_passthrough() {
        registerBp("test:pass",
                List.of(new TriggerDeclaration(
                        "passthru", Map.of(),
                        "test:receiver", 5,
                        Map.of(),  // empty = passthrough
                        null)),
                new AtomicOp.EmitEventOp("passthru",
                        Map.of("a", "1", "b", "2")));

        registerBp("test:receiver", List.of(),
                AtomicOp.TransformOp.place(center.add(0, 0, 1), BlockType.STONE));

        manualSource.publish(makeRequest("test:pass", center, Map.of(), 10));
        tickN(10);

        GlobalTask receiver = findTaskByBpId("test:receiver");
        assertNotNull(receiver);
        assertEquals("1", receiver.taskParams.get("a"), "passthrough: a=1");
        assertEquals("2", receiver.taskParams.get("b"), "passthrough: b=2");
    }

    @Test
    void triggerDeclaration_dedupKey_preventsDuplicate() {
        // Two EmitEventOp steps emit same event with same dedupKey value in different ticks
        registerBp("test:double_emit",
                List.of(new TriggerDeclaration(
                        "needy", Map.of(),
                        "test:rescue", 30,
                        Map.of("rid", "rid"),
                        "rid")),
                new AtomicOp.EmitEventOp("needy", Map.of("rid", "abc")),
                new AtomicOp.EmitEventOp("needy", Map.of("rid", "abc")));

        registerBp("test:rescue", List.of(),
                AtomicOp.TransformOp.place(center, BlockType.STONE));

        manualSource.publish(makeRequest("test:double_emit", center, Map.of(), 10));
        tickN(10);

        // Both events fire in same dispatch. First creates task, second finds it in-flight → skips.
        assertEquals(1, countTasksByBpId("test:rescue"),
                "Second emit with same dedupKey should be suppressed (1 not 2)");
    }

    // ===================================================================
    // 5. EventBus deferred removal — EmitEventOp as last step
    // ===================================================================

    @Test
    void emitEventOp_asLastStep_handlerStillReceivesEvent() {
        // Blueprint: [EmitEventOp("final_event")] with trigger → "test:followup"
        // EmitEventOp is last step → completeTask unsubscribes → BUT deferred removal
        // keeps handler alive through dispatch → followup IS created
        registerBp("test:last_emit",
                List.of(new TriggerDeclaration(
                        "final_event", Map.of(),
                        "test:followup", 15,
                        Map.of(),
                        null)),
                new AtomicOp.EmitEventOp("final_event",
                        Map.of("marker", "last")));

        registerBp("test:followup", List.of(),
                AtomicOp.TransformOp.place(center.add(0, 0, 1), BlockType.STONE));

        long taskId = manualSource.publish(
                makeRequest("test:last_emit", center, Map.of(), 10));
        tickN(10);

        assertEquals(TaskState.COMPLETED, world.taskPool.get(taskId).state);

        GlobalTask followup = findTaskByBpId("test:followup");
        assertNotNull(followup,
                "Deferred unsub — followup MUST exist (handler fires before removal)");
    }

    // ===================================================================
    // 6. Trigger lifecycle — subscribe/unsubscribe
    // ===================================================================

    @Test
    void trigger_unsubscribeAfterComplete_noFurtherCreation() {
        registerBp("test:one_shot",
                List.of(new TriggerDeclaration(
                        "done_msg", Map.of(),
                        "test:reply", 10,
                        Map.of(),
                        null)),
                new AtomicOp.EmitEventOp("done_msg", Map.of("seq", "1")),
                AtomicOp.TransformOp.place(center.add(1, 0, 0), BlockType.STONE));

        registerBp("test:reply", List.of(),
                AtomicOp.TransformOp.place(center.add(2, 0, 0), BlockType.STONE));

        manualSource.publish(makeRequest("test:one_shot", center, Map.of(), 10));
        tickN(15);

        assertEquals(1, countTasksByBpId("test:reply"),
                "First emit should create 1 reply");

        // Emit same event again directly — trigger was unsubscribed
        capturedEvents.clear();
        world.eventBus.emit(new CustomEvent("done_msg", Map.of("seq", "2")));
        tickN(5);

        assertEquals(1, countTasksByBpId("test:reply"),
                "No new reply after unsub (still 1)");
    }

    @Test
    void trigger_awaitedAndReassigned_stillFires() {
        // Task uses ResourceRequestOp → may AWAIT → reassigned → completes → trigger still fires
        registerBp("test:resilient",
                List.of(new TriggerDeclaration(
                        "resilient_done", Map.of(),
                        "test:resilient_reply", 10,
                        Map.of(),
                        null)),
                new AtomicOp.ResourceRequestOp(
                        new ResourceStack(ResourceId.STONE_BRICKS, 10)),
                new AtomicOp.EmitEventOp("resilient_done", Map.of()));

        registerBp("test:resilient_reply", List.of(),
                AtomicOp.TransformOp.place(center.add(3, 0, 0), BlockType.STONE));

        // Stock 200 → task won't AWAIT → but we test that trigger still fires on complete
        long taskId = manualSource.publish(
                makeRequest("test:resilient", center, Map.of(), 10));
        tickN(15);

        assertEquals(TaskState.COMPLETED, world.taskPool.get(taskId).state);
        assertNotNull(findTaskByBpId("test:resilient_reply"),
                "Trigger should fire even after normal complete");
    }

    // ===================================================================
    // 7. System blueprint permanent triggers + heartbeat steps
    // ===================================================================

    @Test
    void systemBlueprint_permanentTrigger_createsTasksOnEveryEmit() {
        SystemBlueprintRegistry sysBp = new SystemBlueprintRegistry();
        sysBp.register("warehouse:monitor", new Blueprint("warehouse:monitor",
                null,
                List.of(new TriggerDeclaration(
                        "resource_alert", Map.of(),
                        "test:sys_responder", 25,
                        Map.of("rid", "rid"),
                        null))));
        sysBp.subscribePermanentTriggers(world.eventBus, world.taskPool);

        registerBp("test:sys_responder", List.of(),
                AtomicOp.TransformOp.place(center.add(0, 0, 3), BlockType.STONE));

        // First emit
        world.eventBus.emit(new CustomEvent("resource_alert", Map.of("rid", "r1")));
        tickN(5);
        assertEquals(1, countTasksByBpId("test:sys_responder"));

        // Second emit — permanent trigger still active
        world.eventBus.emit(new CustomEvent("resource_alert", Map.of("rid", "r2")));
        tickN(5);
        assertEquals(2, countTasksByBpId("test:sys_responder"),
                "Permanent trigger fires every time");
    }

    @Test
    void systemBlueprint_withSteps_executesViaHeartbeat() {
        SystemBlueprintRegistry sysBp = new SystemBlueprintRegistry();
        sysBp.register("sys:builder", (BlueprintSteps) p ->
                TaskSequence.of("System Build",
                        AtomicOp.TransformOp.place(center.add(5, 0, 0), BlockType.STONE)));

        org.magiccolony.core.system.SystemBlueprintSystem sbSystem =
                new org.magiccolony.core.system.SystemBlueprintSystem(sysBp);

        assertTrue(mock.isAir(center.add(5, 0, 0)), "block is AIR before heartbeat");

        sbSystem.update(world, 1.0f);

        assertFalse(mock.isAir(center.add(5, 0, 0)), "block placed by system heartbeat");
        assertEquals(BlockType.STONE, mock.getBlock(center.add(5, 0, 0)));
    }

    @Test
    void systemBlueprint_pureSteps_batchContinuously() {
        // System blueprint with 2 EmitEventOps → both run in one heartbeat
        SystemBlueprintRegistry sysBp = new SystemBlueprintRegistry();
        sysBp.register("sys:emitter", (BlueprintSteps) p ->
                TaskSequence.of("System Emit",
                        new AtomicOp.EmitEventOp("sys_event_1", Map.of("n", "1")),
                        new AtomicOp.EmitEventOp("sys_event_2", Map.of("n", "2"))));

        org.magiccolony.core.system.SystemBlueprintSystem sbSystem =
                new org.magiccolony.core.system.SystemBlueprintSystem(sysBp);

        sbSystem.update(world, 1.0f);
        // Manual dispatch since we call update() directly, not world.tick()
        ((org.magiccolony.core.event.SimpleEventBus) world.eventBus).dispatch();

        boolean has1 = capturedEvents.stream().anyMatch(e -> "sys_event_1".equals(e.name()));
        boolean has2 = capturedEvents.stream().anyMatch(e -> "sys_event_2".equals(e.name()));
        assertTrue(has1 && has2, "Both pure ops execute in one heartbeat");
    }

    // ===================================================================
    // 8. Condition evaluators
    // ===================================================================

    @Test
    void condition_inventoryFull_works() {
        // NPC has 27-slot inventory → empty → not full
        registerBp("test:inv_check", List.of(),
                new AtomicOp.IfConditionOp("inventory_full", Map.of(), 0, true),
                new AtomicOp.EmitEventOp("not_full", Map.of()));

        manualSource.publish(makeRequest("test:inv_check", center, Map.of(), 10));
        tickN(10);

        assertTrue(capturedEvents.stream().anyMatch(e -> "not_full".equals(e.name())),
                "Empty inventory → condition false → elseSkip skips nothing → emit runs");
    }

    @Test
    void condition_resourceBelow_works() {
        // Warehouse has 200 stone → resource_below(stone, threshold=128) is false
        registerBp("test:res_check", List.of(),
                new AtomicOp.IfConditionOp("resource_below",
                        Map.of("resource", "stone", "threshold", "128"), 0, true),
                new AtomicOp.EmitEventOp("stone_ok", Map.of()));

        manualSource.publish(makeRequest("test:res_check", center, Map.of(), 10));
        tickN(10);

        assertTrue(capturedEvents.stream().anyMatch(e -> "stone_ok".equals(e.name())),
                "200>=128 → condition false → elseSkip passes → emit");
    }

    @Test
    void condition_inventoryHas_positive() {
        // Give NPC 5 stone via inventory
        Inventory inv = world.get(npc, Inventory.class);
        inv.add(new ResourceStack(ResourceId.STONE, 5));

        registerBp("test:has_check", List.of(),
                new AtomicOp.IfConditionOp("inventory_has",
                        Map.of("resource", "stone", "amount", "3"), 0, false),
                new AtomicOp.EmitEventOp("has_enough", Map.of()));

        manualSource.publish(makeRequest("test:has_check", center, Map.of(), 10));
        tickN(10);

        assertTrue(capturedEvents.stream().anyMatch(e -> "has_enough".equals(e.name())),
                "NPC has 5 stone >= 3 → condition true → no skip → emit");
    }

    // ===================================================================
    // Helpers
    // ===================================================================

    /** Register a blueprint with the given triggers and steps. Label = blueprintId. */
    private void registerBp(String id, List<TriggerDeclaration> triggers,
                             AtomicOp... steps) {
        world.blueprintRegistry.register(id, new Blueprint(id,
                (BlueprintSteps) p -> new TaskSequence(List.of(steps), id),
                triggers));
    }

    private static TaskRequest makeRequest(String blueprintId, GridPos pos,
                                            Map<String, String> extra, int priority) {
        Map<String, String> params = new HashMap<>(extra);
        params.putIfAbsent("x", String.valueOf(pos.x()));
        params.putIfAbsent("y", String.valueOf(pos.y()));
        params.putIfAbsent("z", String.valueOf(pos.z()));
        return new TaskRequest(blueprintId, params, priority);
    }

    private void tickN(int n) {
        for (int i = 0; i < n; i++) world.tick(1.0f);
    }

    /** Find a task whose sequence label equals the given blueprint ID. */
    private GlobalTask findTaskByBpId(String bpId) {
        for (GlobalTask t : world.taskPool.all()) {
            if (t.sequence.label().equals(bpId)) return t;
        }
        return null;
    }

    private long countTasksByBpId(String bpId) {
        long count = 0;
        for (GlobalTask t : world.taskPool.all()) {
            if (t.sequence.label().equals(bpId)) count++;
        }
        return count;
    }
}
