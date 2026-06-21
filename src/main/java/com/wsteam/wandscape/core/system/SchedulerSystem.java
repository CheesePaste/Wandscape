package com.wsteam.wandscape.core.system;

import com.wsteam.wandscape.core.Log;
import com.wsteam.wandscape.core.component.*;
import com.wsteam.wandscape.core.ecs.System;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.task.ExecutorState;
import com.wsteam.wandscape.core.task.GlobalTask;
import com.wsteam.wandscape.core.task.GlobalTaskPool;
import com.wsteam.wandscape.core.types.BehaviourLevel;
import com.wsteam.wandscape.core.types.BehaviourTag;
import com.wsteam.wandscape.core.types.GridPos;

import java.util.*;

import javax.annotation.Nullable;

/**
 * Assigns global tasks to idle NPCs.
 * Runs every 2 ticks (configurable heartbeat).
 * Scoring: proximity × 0.5 + (1 − manaEfficiency) × 0.3 + behaviourLevel × 0.2,
 * where proximity = 10 / (10 + horizontalDistance).
 */
public class SchedulerSystem implements System {

    private static final int HEARTBEAT_INTERVAL = 2; // ticks between scheduling runs
    private int tickCounter = 0;

    private static final String TAG = "Scheduler";

    @Override
    public void update(World world, float delta) {
        tickCounter++;
        if (tickCounter % HEARTBEAT_INTERVAL != 0) return;

        // 1. Find all idle NPCs with full component set
        List<Long> idleNpcs = new ArrayList<>();
        for (long entity : world.query(Position.class, ManaPool.class, TaskExecutor.class,
                WandCarrier.class, Inventory.class, ColonyMember.class)) {
            TaskExecutor exec = world.get(entity, TaskExecutor.class);
            if (exec != null && exec.state == ExecutorState.IDLE
                    && exec.isPrivateQueueEmpty() && exec.globalTaskId == null) {
                idleNpcs.add(entity);
            }
        }

        if (idleNpcs.isEmpty()) {
            Log.debug(TAG, "heartbeat - no idle NPCs");
            return;
        }

        Log.debug(TAG, "heartbeat - %d idle NPCs, %d assignable tasks",
                idleNpcs.size(), world.taskPool.getAssignableTasks().size());

        // 2. Group NPCs by colony
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
            List<Long> colonyNpcs = entry.getValue();
            List<GlobalTask> assignable = taskPool.getAssignableTasks();
            if (assignable.isEmpty()) continue;

            for (GlobalTask task : assignable) {
                // Extract task target once for distance scoring
                GridPos taskTarget = extractTaskTarget(task);

                // Find the best NPC for this task
                long bestNpc = -1;
                double bestScore = -1;
                double bestDist = -1;

                for (long npcId : colonyNpcs) {
                    WandCarrier wc = world.get(npcId, WandCarrier.class);
                    if (wc == null) continue;

                    // Check if NPC satisfies task requirements
                    if (!satisfies(wc, task.requirements)) continue;

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

                    double score = score(wc, task.requirements, distance);
                    if (score > bestScore) {
                        bestScore = score;
                        bestNpc = npcId;
                        bestDist = distance;
                    }
                }

                if (bestNpc >= 0) {
                    taskPool.assign(task.id, bestNpc, world);
                    Log.info(TAG, "assigned #%d '%s' → NPC %d (score=%.2f dist=%.0f)",
                            task.id, task.sequence.label(), bestNpc, bestScore, bestDist);
                    colonyNpcs.remove(bestNpc); // NPC is now busy
                    if (colonyNpcs.isEmpty()) break;
                } else {
                    Log.debug(TAG, "no capable NPC for #%d '%s'", task.id, task.sequence.label());
                }
            }
        }
    }

    private boolean satisfies(WandCarrier wc, Map<BehaviourTag, BehaviourLevel> requirements) {
        if (requirements.isEmpty()) return true;
        return wc.satisfies(requirements);
    }

    /** Score an NPC for a task. Higher is better. */
    private double score(WandCarrier wc, Map<BehaviourTag, BehaviourLevel> requirements, double distance) {
        // Proximity score: 1.0 at dist=0, ~0.5 at dist=10, ~0.09 at dist=100
        double rangeScore = (10.0 / (10.0 + distance)) * 0.5;
        double efficiencyScore = (1.0 - wc.bestManaEfficiency()) * 0.3;

        // Use the highest behaviour level among required tags
        int bestLevel = 0;
        for (BehaviourTag tag : requirements.keySet()) {
            int lv = wc.level(tag);
            if (lv > bestLevel) bestLevel = lv;
        }
        double levelScore = bestLevel * 0.2;

        return rangeScore + efficiencyScore + levelScore;
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
