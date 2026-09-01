package com.wsteam.wandscape.task.scheduler;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import com.wsteam.wandscape.content.task.scheduler.SystemBlueprintRegistry;
import com.wsteam.wandscape.core.CoreBootstrap;
import com.wsteam.wandscape.core.CoreBootstrapConfig;
import com.wsteam.wandscape.core.boundary.MockBoundary;
import com.wsteam.wandscape.core.boundary.MovementOps;
import com.wsteam.wandscape.core.component.TaskExecutor;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.types.FriendlyForce;
import com.wsteam.wandscape.core.types.GridPos;
import com.wsteam.wandscape.core.types.InteractAction;
import com.wsteam.wandscape.core.types.NpcAttributes;
import com.wsteam.wandscape.content.task.op.api.AtomicOp;
import com.wsteam.wandscape.content.task.engine.dsl.BlueprintRegistry;
import com.wsteam.wandscape.content.task.engine.pool.GlobalTask;
import com.wsteam.wandscape.content.task.engine.pool.TaskRequest;
import com.wsteam.wandscape.content.task.runtime.NpcTaskPackage;
import com.wsteam.wandscape.content.task.runtime.TaskSequence;
import com.wsteam.wandscape.content.task.runtime.TaskState;

import com.google.gson.JsonElement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 殖民地归属作用域回归测试——任务的 colony_id 决定由哪个殖民地的 NPC 执行：
 * <ul>
 *   <li>占位殖民地（全零 UUID）NPC 不是任何小镇的工人：无主任务和带殖民地任务都不得派给它；
 *       已绑定全局任务被执行器释放回池（保留步进供他人续跑），个人包（自防御）保留。</li>
 *   <li>带 colony_id 的任务只派给同殖民地 NPC，绝不跨殖民地串仓库。</li>
 *   <li>无主（colonyId=null）任务仍可派给任意真实殖民地 NPC。</li>
 *   <li>GlobalTaskPool 是 colony_id 参数的唯一写入点（由 TaskRequest.colonyId 归一化）。</li>
 * </ul>
 * 复现日志：{@code TaskExec | NPC 17 op ResourceRequestOp failed: [ResourceReq] no storage
 * for colony 00000000-0000-0000-0000-000000000000} 无限循环刷屏、任务无人施工卡死——
 * 刷怪蛋召唤在殖民地外（>256 格）的 NPC 保持占位殖民地，调度器却把它当正常工人派活。
 */
class SchedulerColonyScopeTest {

    private static final String BP_WORK = "test:colony_work";

    private World world;
    private BlueprintRegistry blueprints;
    private MockBoundary mock;
    private UUID colonyA;
    private UUID colonyB;

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
        colonyA = UUID.randomUUID();
        colonyB = UUID.randomUUID();
        blueprints.register(BP_WORK, params -> new TaskSequence(List.of(
                new AtomicOp.BlockInteractOp(new GridPos(0, 64, 0),
                        new InteractAction("synthesize"), Map.of(), 0)), "work"));
    }

    private long createNpc(int x, UUID colony) {
        return CoreBootstrap.createNpc(world, x, 64, 0, colony, NpcAttributes.defaults());
    }

    /** 把一个任务手动绑定到指定 NPC（模拟已接取：分配 + 队列压包 + globalTaskId 绑定）。 */
    private GlobalTask bindTaskToNpc(long npcId, @Nullable UUID taskColony) {
        long taskId = world.taskPool.addTask(new TaskRequest(BP_WORK, Map.of(), 10, taskColony));
        GlobalTask task = world.taskPool.get(taskId);
        world.taskPool.assignLight(taskId, npcId, world);
        TaskExecutor exec = world.get(npcId, TaskExecutor.class);
        exec.globalTaskId = taskId;
        exec.npcQueue.enqueueNormal(
                NpcTaskPackage.of("global:" + taskId, task.sequence, new GridPos(0, 64, 0), 10));
        return task;
    }

    // ── 保底：占位/未注册殖民地 NPC 不干活 ──

    @Test
    void placeholderNpc_isNotAssignedAnyTask() {
        long placeholderNpc = createNpc(0, FriendlyForce.PLACEHOLDER_COLONY);
        long taskId = world.taskPool.addTask(new TaskRequest(BP_WORK, Map.of(), 10, null));
        GlobalTask task = world.taskPool.get(taskId);

        world.tick(1.0f); // heartbeat=1：调度器第一个 tick 派活

        assertNull(task.assignedNpcId,
                "占位殖民地 NPC 不得接取任何任务（含无主任务）——它没有仓库/建筑可服务");
        assertNull(world.get(placeholderNpc, TaskExecutor.class).globalTaskId,
                "占位 NPC 不持有任何任务");
    }

    @Test
    void placeholderNpc_isNotAssignedColonyBoundTask() {
        long placeholderNpc = createNpc(0, FriendlyForce.PLACEHOLDER_COLONY);
        long taskId = world.taskPool.addTask(new TaskRequest(BP_WORK, Map.of(), 10, colonyA));
        GlobalTask task = world.taskPool.get(taskId);

        world.tick(1.0f);

        assertNull(task.assignedNpcId, "带殖民地任务也不得派给占位殖民地 NPC");
        assertNull(world.get(placeholderNpc, TaskExecutor.class).globalTaskId);
    }

    @Test
    void execution_releasesPlaceholderNpcBoundTask() {
        long placeholderNpc = createNpc(0, FriendlyForce.PLACEHOLDER_COLONY);

        GlobalTask task = bindTaskToNpc(placeholderNpc, null);
        assertEquals(TaskState.IN_PROGRESS, task.state, "前置：任务已绑定");

        world.tick(1.0f);

        assertEquals(TaskState.PENDING_ASSIGN, task.state,
                "占位 NPC 已绑定的任务被释放回任务池（保留步进供他人续跑）");
        TaskExecutor exec = world.get(placeholderNpc, TaskExecutor.class);
        assertNull(exec.globalTaskId, "占位 NPC 解除任务绑定");
        assertFalse(exec.npcQueue.hasGlobalPackage(), "占位 NPC 队列里的 global 包被丢弃");
    }

    // ── 殖民地路由：只派给同殖民地 NPC ──

    @Test
    void colonyBoundTask_assignedToSameColonyNpc() {
        long npcA = createNpc(0, colonyA);
        long taskId = world.taskPool.addTask(new TaskRequest(BP_WORK, Map.of(), 10, colonyA));
        GlobalTask task = world.taskPool.get(taskId);

        world.tick(1.0f);

        assertEquals(npcA, task.assignedNpcId, "colony A 的任务派给 colony A 的 NPC");
        assertEquals(taskId, world.get(npcA, TaskExecutor.class).globalTaskId);
    }

    @Test
    void colonyBoundTask_notAssignedToOtherColonyNpc() {
        long npcB = createNpc(0, colonyB);
        long taskId = world.taskPool.addTask(new TaskRequest(BP_WORK, Map.of(), 10, colonyA));
        GlobalTask task = world.taskPool.get(taskId);

        world.tick(1.0f);

        assertNull(task.assignedNpcId, "colony A 的任务不得派给 colony B 的 NPC（多殖民地不串仓库）");
        assertNull(world.get(npcB, TaskExecutor.class).globalTaskId);
    }

    @Test
    void unboundTask_assignedToRealColonyNpc_neverPlaceholder() {
        long placeholderNpc = createNpc(0, FriendlyForce.PLACEHOLDER_COLONY);
        long npcA = createNpc(20, colonyA);
        long taskId = world.taskPool.addTask(new TaskRequest(BP_WORK, Map.of(), 10, null));
        GlobalTask task = world.taskPool.get(taskId);

        world.tick(1.0f);

        assertEquals(npcA, task.assignedNpcId, "无主任务派给真实殖民地 NPC");
        assertNull(world.get(placeholderNpc, TaskExecutor.class).globalTaskId,
                "无主任务也绝不派给占位殖民地 NPC");
    }

    // ── colony_id 归一化：GlobalTaskPool 唯一写入点 ──

    @Test
    void poolWritesColonyIdParam_fromRequestField() {
        long taskId = world.taskPool.addTask(new TaskRequest(BP_WORK, Map.of(), 10, colonyA));
        GlobalTask task = world.taskPool.get(taskId);

        JsonElement el = task.taskParams.get("colony_id");
        assertNotNull(el, "colony_id 参数由池写入");
        assertEquals(colonyA.toString(), el.getAsString(),
                "colony_id 归一化为规范化 UUID 字符串");
    }

    @Test
    void poolKeepsExistingColonyIdParam_whenRequestColonyNull() {
        // 事件透传/存档恢复路径：TaskRequest.colonyId 为 null 时保留 params 里已有的 colony_id
        long taskId = world.taskPool.addTask(new TaskRequest(BP_WORK,
                Map.of("colony_id", new com.google.gson.JsonPrimitive(colonyA.toString())), 10, null));
        GlobalTask task = world.taskPool.get(taskId);

        assertEquals(colonyA.toString(), task.taskParams.get("colony_id").getAsString(),
                "无主字段时保留 params 里已透传的 colony_id");
    }

    @Test
    void unboundTask_hasNoColonyIdParam() {
        long taskId = world.taskPool.addTask(new TaskRequest(BP_WORK, Map.of(), 10, null));
        GlobalTask task = world.taskPool.get(taskId);

        assertFalse(task.taskParams.containsKey("colony_id"), "无主任务不写入 colony_id");
    }
}
