package com.wsteam.wandscape.task.scheduler;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.wsteam.wandscape.core.CoreBootstrap;
import com.wsteam.wandscape.core.CoreBootstrapConfig;
import com.wsteam.wandscape.core.boundary.MockBoundary;
import com.wsteam.wandscape.core.boundary.MovementOps;
import com.wsteam.wandscape.core.component.TaskExecutor;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.types.GridPos;
import com.wsteam.wandscape.core.types.InteractAction;
import com.wsteam.wandscape.core.types.NpcAttributes;
import com.wsteam.wandscape.op.api.AtomicOp;
import com.wsteam.wandscape.task.engine.dsl.BlueprintRegistry;
import com.wsteam.wandscape.task.engine.pool.GlobalTask;
import com.wsteam.wandscape.task.engine.pool.TaskRequest;
import com.wsteam.wandscape.task.runtime.NpcTaskPackage;
import com.wsteam.wandscape.task.runtime.TaskSequence;
import com.wsteam.wandscape.task.runtime.TaskState;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 幽灵 NPC 回归测试——NPC 的 MC 实体缺失/已移除（如区块卸载）但 ECS 组件仍在时：
 * 调度器不得把它当空闲工人派活，执行系统不得驱动它（遇到即释放绑定任务）。
 * 复现日志：{@code TaskExec | NPC 51 op ResourceRequestOp failed: [ResourceReq] NPC 51 not found}
 * 与 {@code navigateTo: unknown or removed NPC 51} 交替刷屏、任务无人施工卡死。
 */
class SchedulerPhantomNpcTest {

    private static final String BP_BLOCKED = "test:phantom_blocked";

    private World world;
    private BlueprintRegistry blueprints;
    private MockBoundary mock;
    private UUID colony;

    @BeforeEach
    void setUp() {
        mock = new MockBoundary();
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
                new SystemBlueprintRegistry(), false, 1, null);
        world = CoreBootstrap.bootstrap(config);
        colony = UUID.randomUUID();
        // 无执行器的 op：若被驱动会卡在 executor==null → return，用于证明守卫先释放。
        blueprints.register(BP_BLOCKED, params -> new TaskSequence(List.of(
                new AtomicOp.BlockInteractOp(new GridPos(0, 64, 0),
                        new InteractAction("synthesize"), Map.of(), 0)), "blocked"));
    }

    private long createNpc(int x) {
        return CoreBootstrap.createNpc(world, x, 64, 0, colony, NpcAttributes.defaults());
    }

    /** 把一个任务手动绑定到指定 NPC（模拟已接取：分配 + 队列压包 + globalTaskId 绑定）。 */
    private GlobalTask bindTaskToNpc(long npcId) {
        long taskId = world.taskPool.addTask(new TaskRequest(BP_BLOCKED, Map.of(), 10));
        GlobalTask task = world.taskPool.get(taskId);
        world.taskPool.assignLight(taskId, npcId, world);
        TaskExecutor exec = world.get(npcId, TaskExecutor.class);
        exec.globalTaskId = taskId;
        exec.npcQueue.enqueueNormal(
                NpcTaskPackage.of("global:" + taskId, task.sequence, new GridPos(0, 64, 0), 10));
        return task;
    }

    @Test
    void scheduler_excludesPhantomNpc_fromIdleCandidates() {
        long healthyId = createNpc(0);
        long phantomId = createNpc(10);
        mock.setNpcRemoved(phantomId, true);

        long taskId = world.taskPool.addTask(new TaskRequest(BP_BLOCKED, Map.of(), 10));
        GlobalTask task = world.taskPool.get(taskId);

        world.tick(1.0f); // heartbeat=1：调度器第一个 tick 派活

        assertEquals(healthyId, task.assignedNpcId,
                "任务只派给存活的 NPC，绝不派给幽灵 NPC");
        assertNull(world.get(phantomId, TaskExecutor.class).globalTaskId,
                "幽灵 NPC 不得持有任何任务");
        assertEquals(taskId, world.get(healthyId, TaskExecutor.class).globalTaskId,
                "健康 NPC 接取了任务");
    }

    @Test
    void execution_releasesPhantomNpcBoundTask() {
        long phantomId = createNpc(0);
        mock.setNpcRemoved(phantomId, true);

        GlobalTask task = bindTaskToNpc(phantomId);
        assertEquals(TaskState.IN_PROGRESS, task.state, "前置：任务已绑定");

        world.tick(1.0f);

        assertEquals(TaskState.PENDING_ASSIGN, task.state,
                "幽灵 NPC 的绑定任务被释放回任务池（保留步进供他人续跑）");
        TaskExecutor exec = world.get(phantomId, TaskExecutor.class);
        assertNull(exec.globalTaskId, "幽灵 NPC 解除任务绑定");
        assertFalse(exec.npcQueue.hasGlobalPackage(), "幽灵 NPC 队列里的 global 包被丢弃");
    }

    @Test
    void releasedPhantomTask_reassignsToHealthyNpc() {
        long healthyId = createNpc(0);
        long phantomId = createNpc(10);
        mock.setNpcRemoved(phantomId, true);

        GlobalTask task = bindTaskToNpc(phantomId);

        world.tick(1.0f); // tick 1：执行守卫释放幽灵的任务 → PENDING_ASSIGN
        assertEquals(TaskState.PENDING_ASSIGN, task.state);

        world.tick(1.0f); // tick 2：调度器把任务续派给健康 NPC

        assertEquals(healthyId, task.assignedNpcId,
                "被幽灵卡住的任务由健康 NPC 接续，无人施工解除");
        TaskExecutor healthyExec = world.get(healthyId, TaskExecutor.class);
        assertEquals(task.id, healthyExec.globalTaskId, "健康 NPC 绑定续跑的任务");
        assertTrue(healthyExec.npcQueue.hasGlobalPackage());
        assertNull(world.get(phantomId, TaskExecutor.class).globalTaskId);
    }
}
