package org.magiccolony.core.system;

import org.magiccolony.core.Log;
import org.magiccolony.core.component.*;
import org.magiccolony.core.ecs.System;
import org.magiccolony.core.ecs.World;
import org.magiccolony.core.task.ExecutorState;
import org.magiccolony.core.task.GlobalTask;
import org.magiccolony.core.task.GlobalTaskPool;
import org.magiccolony.core.types.BehaviourLevel;
import org.magiccolony.core.types.BehaviourTag;

import java.util.*;

/**
 * Assigns global tasks to idle NPCs.
 * Runs every 2 ticks (configurable heartbeat).
 * Scoring: range × 0.5 + (1 - manaEfficiency) × 0.3 + behaviourLevel × 0.2
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
                // Find the best NPC for this task
                long bestNpc = -1;
                double bestScore = -1;

                for (long npcId : colonyNpcs) {
                    WandCarrier wc = world.get(npcId, WandCarrier.class);
                    if (wc == null) continue;

                    // Check if NPC satisfies task requirements
                    if (!satisfies(wc, task.requirements)) continue;

                    // Check mana: at least enough for first step
                    ManaPool mana = world.get(npcId, ManaPool.class);
                    if (mana == null || mana.isEmpty()) continue;

                    double score = score(wc, task.requirements);
                    if (score > bestScore) {
                        bestScore = score;
                        bestNpc = npcId;
                    }
                }

                if (bestNpc >= 0) {
                    taskPool.assign(task.id, bestNpc, world);
                    Log.info(TAG, "assigned #%d '%s' → NPC %d (score=%.2f)",
                            task.id, task.sequence.label(), bestNpc, bestScore);
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
    private double score(WandCarrier wc, Map<BehaviourTag, BehaviourLevel> requirements) {
        double rangeScore = wc.maxRange() * 0.5;
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

    /** Manually trigger a scheduling heartbeat (for testing). */
    public void forceHeartbeat() {
        tickCounter = HEARTBEAT_INTERVAL;
    }

    public void resetCounter() {
        tickCounter = 0;
    }
}
