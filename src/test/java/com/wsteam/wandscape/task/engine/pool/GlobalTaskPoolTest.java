package com.wsteam.wandscape.task.engine.pool;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.wsteam.wandscape.core.CoreBootstrap;
import com.wsteam.wandscape.core.CoreBootstrapConfig;
import com.wsteam.wandscape.core.boundary.MockBoundary;
import com.wsteam.wandscape.core.boundary.MovementOps;
import com.wsteam.wandscape.core.component.TaskExecutor;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.types.NpcAttributes;
import com.wsteam.wandscape.task.engine.dsl.BlueprintRegistry;
import com.wsteam.wandscape.task.runtime.NpcTaskPackage;
import com.wsteam.wandscape.task.runtime.TaskSequence;
import com.wsteam.wandscape.task.runtime.TaskState;
import com.wsteam.wandscape.task.scheduler.SystemBlueprintRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GlobalTaskPool.hasActiveTask —— 祭坛施法发布即锁定：同一蓝图 + 参数子集匹配的非 COMPLETED
 * 任务即视为锁定；任务完成（施放结束）后解锁。
 */
class GlobalTaskPoolTest {

    private static final String ALTAR_BLUEPRINT = "magic:altar_cast";

    private World world;
    private UUID altarId;

    @BeforeEach
    void setUp() {
        MockBoundary mock = new MockBoundary();
        BlueprintRegistry blueprints = new BlueprintRegistry();
        blueprints.register(ALTAR_BLUEPRINT,
                params -> new TaskSequence(List.of(), "altar_cast"));
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
        altarId = UUID.randomUUID();
    }

    private Map<String, JsonElement> altarParams(String magicId) {
        Map<String, JsonElement> params = new HashMap<>();
        params.put("altar", new JsonPrimitive(altarId.toString()));
        params.put("magic_id", new JsonPrimitive(magicId));
        return params;
    }

    @Test
    void activeTask_locksSameAltarAndMagic_untilCompleted() {
        long taskId = world.taskPool.addTask(
                new TaskRequest(ALTAR_BLUEPRINT, altarParams("revive"), 10));

        assertTrue(world.taskPool.hasActiveTask(ALTAR_BLUEPRINT, altarParams("revive")),
                "已发布未完成的任务应锁定同一祭坛同一魔法");
        assertFalse(world.taskPool.hasActiveTask(ALTAR_BLUEPRINT, altarParams("beam")),
                "同一祭坛不同魔法不应锁定");
        assertFalse(world.taskPool.hasActiveTask("magic:other", altarParams("revive")),
                "不同蓝图不应锁定");

        world.taskPool.completeTask(taskId, 1L);
        assertFalse(world.taskPool.hasActiveTask(ALTAR_BLUEPRINT, altarParams("revive")),
                "任务完成（施放结束）后应解锁");
    }

    @Test
    void paramsSubsetMatch_ignoresExtraParams() {
        Map<String, JsonElement> full = altarParams("revive");
        full.put("duration", new JsonPrimitive(600));
        full.put("colony_id", new JsonPrimitive(UUID.randomUUID().toString()));
        world.taskPool.addTask(new TaskRequest(ALTAR_BLUEPRINT, full, 10));

        // 只要求 altar+magic_id 子集即可匹配（任务还带 duration/colony_id）
        assertTrue(world.taskPool.hasActiveTask(ALTAR_BLUEPRINT, altarParams("revive")));
        // altar 不匹配则不锁
        Map<String, JsonElement> otherAltar = new HashMap<>();
        otherAltar.put("altar", new JsonPrimitive(UUID.randomUUID().toString()));
        assertFalse(world.taskPool.hasActiveTask(ALTAR_BLUEPRINT, otherAltar));
    }

    @Test
    void cancelTask_removesPendingAssignTask() {
        long taskId = world.taskPool.addTask(
                new TaskRequest(ALTAR_BLUEPRINT, altarParams("revive"), 10));
        assertEquals(1, world.taskPool.assignableCount());

        long released = world.taskPool.cancelTask(taskId, world);

        assertEquals(-1, released, "pending task has no assigned NPC");
        assertEquals(TaskState.COMPLETED, world.taskPool.get(taskId).state,
                "cancelled task must leave the active set");
        assertEquals(0, world.taskPool.assignableCount(),
                "cancelled task must not remain assignable");
        assertFalse(world.taskPool.hasActiveTask(ALTAR_BLUEPRINT, altarParams("revive")),
                "cancelled task must not keep locking the altar");
    }

    @Test
    void cancelTask_releasesAssignedNpc_andDropsItsGlobalPackage() {
        long npcId = CoreBootstrap.createNpc(world, 0, 64, 0,
                UUID.randomUUID(), NpcAttributes.defaults());
        long taskId = world.taskPool.addTask(
                new TaskRequest(ALTAR_BLUEPRINT, altarParams("revive"), 10));
        world.taskPool.assignLight(taskId, npcId, world);

        TaskExecutor exec = world.get(npcId, TaskExecutor.class);
        // assignLight only sets task.assignedNpcId; the executor's globalTaskId is
        // bound when the NPC actually starts the package — simulate that here.
        exec.globalTaskId = taskId;
        TaskSequence seq = world.taskPool.get(taskId).sequence;
        exec.npcQueue.enqueueNormal(NpcTaskPackage.of("global:" + taskId, seq, null, 10));
        assertNotNull(exec.globalTaskId, "task bound to NPC");
        assertTrue(exec.npcQueue.hasGlobalPackage());

        long released = world.taskPool.cancelTask(taskId, world);

        assertEquals(npcId, released, "cancelled task reports the released NPC");
        assertNull(exec.globalTaskId, "NPC released from the cancelled task");
        assertFalse(exec.npcQueue.hasGlobalPackage(),
                "NPC's queued global package dropped so it stops executing");
    }
}
