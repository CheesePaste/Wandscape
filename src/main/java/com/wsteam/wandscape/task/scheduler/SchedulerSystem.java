package com.wsteam.wandscape.task.scheduler;

import com.google.gson.JsonElement;
import com.wsteam.wandscape.core.component.ColonyMember;
import com.wsteam.wandscape.core.component.Inventory;
import com.wsteam.wandscape.core.component.Position;
import com.wsteam.wandscape.core.component.TaskExecutor;
import com.wsteam.wandscape.core.ecs.System;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.types.GridPos;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.task.engine.pool.GlobalTask;
import com.wsteam.wandscape.task.engine.pool.GlobalTaskPool;
import com.wsteam.wandscape.task.runtime.ExecutorState;
import com.wsteam.wandscape.task.runtime.NpcTaskPackage;
import com.wsteam.wandscape.task.runtime.TaskState;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Assigns global tasks to idle NPCs.
 * Runs every {@code heartbeatInterval} ticks (interval wired from
 * {@code Config.SCHEDULER_HEARTBEAT_TICKS} at bootstrap).
 * <p>
 * Phase 3 (migration): uses EquipmentComponent for scoring.
 * Scoring: proximity × 0.6 + mana efficiency × 0.4 (temp, being replaced by attribute-weighted).
 */
public class SchedulerSystem implements System {

    private final int heartbeatInterval;
    private int tickCounter = 0;

    private static final String TAG = "Scheduler";

    /** @param heartbeatInterval ticks between scheduling runs */
    public SchedulerSystem(int heartbeatInterval) {
        this.heartbeatInterval = heartbeatInterval;
    }

    @Override
    public void update(World world, float delta) {
        try (var span = com.wsteam.wandscape.shared.util.TickProfiler.INSTANCE.start("ecs.scheduler.tick")) {
        tickCounter++;
        if (tickCounter % heartbeatInterval != 0) return;

        // 1. Find all idle NPCs with full component set
        // 跟随模式：NPC 不接取任何小镇任务，从空闲候选中排除
        // 幽灵 NPC（MC 实体缺失/已移除，如区块卸载）：任务不得派给不存在的工人
        List<Long> idleNpcs = new ArrayList<>();
        for (long entity : world.query(Position.class, TaskExecutor.class,
                Inventory.class, ColonyMember.class)) {
            TaskExecutor exec = world.get(entity, TaskExecutor.class);
            if (exec != null && exec.state == ExecutorState.IDLE
                    && exec.npcQueue.isIdle() && exec.globalTaskId == null
                    && (world.entityOps == null || (!world.entityOps.isFollowing(entity)
                            && !world.entityOps.isResting(entity)))
                    && (world.entityOps == null || world.entityOps.isNpcAlive(entity))) {
                idleNpcs.add(entity);
            }
        }

        if (idleNpcs.isEmpty()) {
            return;
        }

        // 2. Group NPCs by colony (needed for per-colony logging below)
        Map<UUID, List<Long>> npcsByColony = new HashMap<>();
        for (long npcId : idleNpcs) {
            ColonyMember member = world.get(npcId, ColonyMember.class);
            if (member != null) {
                npcsByColony.computeIfAbsent(member.colonyId(), k -> new ArrayList<>()).add(npcId);
            }
        }

        GlobalTaskPool taskPool = world.taskPool;

        // 3. For each colony, match NPCs to tasks
        for (Map.Entry<UUID, List<Long>> entry : npcsByColony.entrySet()) {
            // 占位/未注册殖民地 NPC（刷怪蛋召唤在殖民地外、殖民地已删除但 NPC 留档）不是任何
            // 小镇的工人：不派任何任务——它们没有仓库/建筑可服务，派了只会 no-storage 死循环
            // （全零占位殖民地 getFounder 为 null，会被 isColonyActive 误判为激活）。
            if (world.entityOps != null && !world.entityOps.isColonyRegistered(entry.getKey())) {
                continue;
            }
            // 创始人不在线且关闭离线运行 → 冻结该小镇：不分配任何任务
            if (world.entityOps != null && !world.entityOps.isColonyActive(entry.getKey())) {
                continue;
            }
            List<Long> colonyNpcs = entry.getValue();
            List<GlobalTask> assignable = taskPool.getAssignableTasks();
            if (assignable.isEmpty()) continue;

            // Collect target positions already occupied by IN_PROGRESS tasks
            Set<GridPos> occupiedTargets = new HashSet<>();
            for (GlobalTask t : taskPool.getByState(TaskState.IN_PROGRESS)) {
                GridPos target = extractTaskTarget(t);
                if (target != null) occupiedTargets.add(target);
            }

            for (GlobalTask task : assignable) {
                // Skip if another NPC is already working on the same target position
                GridPos taskTarget = extractTaskTarget(task);
                if (taskTarget != null && occupiedTargets.contains(taskTarget)) {
                    continue;
                }

                // 任务可声明小镇归属 + 魔力门槛（如祭坛施法）：
                // 只分给指定小镇的 NPC，且其当前魔力必须 ≥ 任务蓝耗（不足则任务挂起，等回蓝）。
                String taskColony = taskColonyFilter(task);
                if (taskColony != null && !taskColony.equals(entry.getKey().toString())) {
                    continue;
                }
                int manaRequirement = taskManaRequirement(task);

                // Find the best NPC for this task
                long bestNpc = -1;
                double bestScore = -1;
                double bestDist = -1;

                for (long npcId : colonyNpcs) {
                    // 魔力门槛：接取前当前魔力 ≥ 任务蓝耗（否则跳过，等魔力恢复后下轮再评）
                    if (manaRequirement > 0 && (world.entityOps == null
                            || world.entityOps.getCurrentMana(npcId) < manaRequirement)) {
                        continue;
                    }

                    // Calculate horizontal distance from NPC to task target
                    double distance = 0;
                    if (taskTarget != null) {
                        Position pos = world.get(npcId, Position.class);
                        if (pos != null) {
                            double dx = pos.pos().x() - taskTarget.x();
                            double dz = pos.pos().z() - taskTarget.z();
                            distance = Math.sqrt(dx * dx + dz * dz);
                        }
                    }

                    // Score: proximity + work speed (faster workers favored)
                    float proximity = 10f / (10f + (float) distance);
                    float workSpeed = (world.entityOps != null) ? world.entityOps.getWorkSpeed(npcId) : 1f;
                    float workEff = Math.min(workSpeed, 4f);
                    double score = proximity * 0.6f + (workEff - 1f) * 0.4f;

                    if (score > bestScore) {
                        bestScore = score;
                        bestNpc = npcId;
                        bestDist = distance;
                    }
                }

                if (bestNpc >= 0) {
                    TaskExecutor bestExec = world.get(bestNpc, TaskExecutor.class);
                    if (bestExec != null) {
                        GridPos stance = TaskExecutionSystem.computeTaskStance(task.sequence);
                        NpcTaskPackage pkg = NpcTaskPackage.resumeFrom(
                                "global:" + task.id, task.sequence, stance, task.priority,
                                task.stepIndex);
                        bestExec.npcQueue.enqueueNormal(pkg);
                    }
                    taskPool.assignLight(task.id, bestNpc, world);
                    occupiedTargets.add(taskTarget);
                    Log.info(TAG, "assigned #%d '%s' → NPC %d (score=%.2f dist=%.0f)",
                            task.id, task.sequence.label(), bestNpc, bestScore, bestDist);
                    colonyNpcs.remove(bestNpc);
                    if (colonyNpcs.isEmpty()) break;
                    continue;
                }

                // No NPC matched — log diagnostics
                if (!colonyNpcs.isEmpty()) {
                    ColonyMember cm = world.get(colonyNpcs.get(0), ColonyMember.class);
                    Log.warn(TAG, "  NO_MATCH task #%d '%s' — no suitable NPC in colony=%s",
                            task.id, task.sequence.label(),
                            cm != null ? cm.colonyId().toString().substring(0, 8) : "?");
                }
            }
        }
        }
    }

    /** Extract the first world position from a task's operation sequence. */
    @Nullable
    private static GridPos extractTaskTarget(GlobalTask task) {
        for (int i = 0; i < task.sequence.size(); i++) {
            GridPos t = task.sequence.get(i).target();
            if (t != null) return t;
        }
        return null;
    }

    /** 任务声明的小镇归属（params["colony_id"]）；无 = 不限小镇。 */
    @Nullable
    private static String taskColonyFilter(GlobalTask task) {
        JsonElement el = task.taskParams.get("colony_id");
        return el != null && el.isJsonPrimitive() ? el.getAsString() : null;
    }

    /** 任务要求的接取魔力门槛（params["mana_cost"]）；无 = 0（不限）。 */
    private static int taskManaRequirement(GlobalTask task) {
        JsonElement el = task.taskParams.get("mana_cost");
        if (el != null && el.isJsonPrimitive()) {
            try {
                return el.getAsInt();
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    /** Manually trigger a scheduling heartbeat (for testing). */
    public void forceHeartbeat() {
        tickCounter = heartbeatInterval;
    }

    public void resetCounter() {
        tickCounter = 0;
    }
}
