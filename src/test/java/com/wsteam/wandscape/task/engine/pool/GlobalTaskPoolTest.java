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
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.task.engine.dsl.BlueprintRegistry;
import com.wsteam.wandscape.task.runtime.TaskSequence;
import com.wsteam.wandscape.task.scheduler.SystemBlueprintRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
