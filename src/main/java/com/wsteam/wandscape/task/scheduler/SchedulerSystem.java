package com.wsteam.wandscape.task.scheduler;

import com.wsteam.wandscape.core.component.*;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.core.component.*;
import com.wsteam.wandscape.core.ecs.System;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.task.runtime.ExecutorState;
import com.wsteam.wandscape.task.engine.pool.GlobalTask;
import com.wsteam.wandscape.task.engine.pool.GlobalTaskPool;
import com.wsteam.wandscape.task.runtime.NpcTaskPackage;
import com.wsteam.wandscape.task.runtime.TaskState;
import com.wsteam.wandscape.core.types.AttributeType;
import com.wsteam.wandscape.core.types.EquipmentSlot;
import com.wsteam.wandscape.core.types.GridPos;

import java.util.*;

import javax.annotation.Nullable;

/**
 * Assigns global tasks to idle NPCs.
 * Runs every 2 ticks (configurable heartbeat).
 * <p>
 * Phase 3 (migration): uses EquipmentComponent for scoring.
 * Scoring: proximity × 0.6 + mana efficiency × 0.4 (temp, being replaced by attribute-weighted).
 */
public class SchedulerSystem implements System {

    private static final int HEARTBEAT_INTERVAL = 2; // ticks between scheduling runs
    private int tickCounter = 0;

    private static final String TAG = "Scheduler";

    /** No-arg constructor. */
    public SchedulerSystem() {
    }

    @Override
    public void update(World world, float delta) {
        tickCounter++;
        if (tickCounter % HEARTBEAT_INTERVAL != 0) return;

        // 1. Find all idle NPCs with full component set
        List<Long> idleNpcs = new ArrayList<>();
        for (long entity : world.query(Position.class, ManaPool.class, TaskExecutor.class,
                EquipmentComponent.class, Inventory.class, ColonyMember.class)) {
            TaskExecutor exec = world.get(entity, TaskExecutor.class);
            if (exec != null && exec.state == ExecutorState.IDLE
                    && exec.npcQueue.isIdle() && exec.globalTaskId == null) {
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

        for (long eid : idleNpcs) {
            EquipmentComponent eq = world.get(eid, EquipmentComponent.class);
            ManaPool mp = world.get(eid, ManaPool.class);
            ColonyMember cm = world.get(eid, ColonyMember.class);
        }

        GlobalTaskPool taskPool = world.taskPool;

        // 3. For each colony, match NPCs to tasks
        for (Map.Entry<UUID, List<Long>> entry : npcsByColony.entrySet()) {
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

                // Find the best NPC for this task
                long bestNpc = -1;
                double bestScore = -1;
                double bestDist = -1;

                for (long npcId : colonyNpcs) {
                    EquipmentComponent eq = world.get(npcId, EquipmentComponent.class);
                    if (eq == null) continue;

                    // Ensure NPC has a wand equipped
                    if (!eq.hasEquipment(EquipmentSlot.WAND)) {
                        eq.equipDefaultWand();
                    }

                    // Check mana: at least enough for first step
                    ManaPool mana = world.get(npcId, ManaPool.class);
                    if (mana == null || mana.isEmpty()) continue;

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

                    // Temp score: proximity + mana efficiency
                    float proximity = 10f / (10f + (float) distance);
                    float manaEff = eq.getAttribute(AttributeType.MANA_COST_MULTIPLIER);
                    double score = proximity * 0.6f + (1f - manaEff) * 0.4f;

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

    /** Extract the first world position from a task's operation sequence. */
    @Nullable
    private static GridPos extractTaskTarget(GlobalTask task) {
        for (int i = 0; i < task.sequence.size(); i++) {
            GridPos t = task.sequence.get(i).target();
            if (t != null) return t;
        }
        return null;
    }

    /** Manually trigger a scheduling heartbeat (for testing). */
    public void forceHeartbeat() {
        tickCounter = HEARTBEAT_INTERVAL;
    }

    public void resetCounter() {
        tickCounter = 0;
    }
}
