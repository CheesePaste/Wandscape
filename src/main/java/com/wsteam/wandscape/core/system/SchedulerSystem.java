package com.wsteam.wandscape.core.system;

import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.core.component.*;
import com.wsteam.wandscape.core.ecs.System;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.op.AtomicOp;
import com.wsteam.wandscape.core.task.ExecutorState;
import com.wsteam.wandscape.core.task.GlobalTask;
import com.wsteam.wandscape.core.task.GlobalTaskPool;
import com.wsteam.wandscape.core.task.NpcTaskPackage;
import com.wsteam.wandscape.core.task.TaskFailureReason;
import com.wsteam.wandscape.core.task.TaskState;
import com.wsteam.wandscape.core.task.WandLifecycle;
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

    /**
     * Maximum scheduler heartbeats a task stays in PENDING_ASSIGN without a
     * matching NPC/wand before being failed so FailureAnalyzer can recover.
     * 30 × 2 ticks = 60 ticks ≈ 3 seconds. Covers wand return transport window.
     */
    private static final int MAX_RETRIES = 30;

    private static final String TAG = "Scheduler";

    @Nullable
    private final WandProvider wandProvider;

    /** No-arg constructor — scheduler runs without wand provisioning. */
    public SchedulerSystem() {
        this.wandProvider = null;
    }

    /** Constructor with wand provisioning support. */
    public SchedulerSystem(@Nullable WandProvider wandProvider) {
        this.wandProvider = wandProvider;
    }

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
                    && exec.npcQueue.isIdle() && exec.globalTaskId == null) {
                idleNpcs.add(entity);
            }
        }

        if (idleNpcs.isEmpty()) {
            Log.debug(TAG, "heartbeat - no idle NPCs");
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

        Log.info(TAG, "heartbeat - colony_count=%d idle_npcs=%d assignable_tasks=%d",
                npcsByColony.size(),
                idleNpcs.size(), world.taskPool.getAssignableTasks().size());
        for (long eid : idleNpcs) {
            WandCarrier wc = world.get(eid, WandCarrier.class);
            ManaPool mp = world.get(eid, ManaPool.class);
            ColonyMember cm = world.get(eid, ColonyMember.class);
            Log.info(TAG, "  idle NPC %d colony=%s caps=%s mana=%.1f/%d",
                    eid,
                    cm != null ? cm.colonyId().toString().substring(0, 8) : "?",
                    wc != null ? wc.capabilities().toString() : "null",
                    mp != null ? mp.current() : -1,
                    mp != null ? mp.max() : -1);
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
                    Log.debug(TAG, "skip #%d '%s' — target %s already occupied",
                            task.id, task.sequence.label(), taskTarget);
                    continue;
                }

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
                    task.schedulerRetryCount = 0; // reset retries on successful match
                    TaskExecutor bestExec = world.get(bestNpc, TaskExecutor.class);
                    if (bestExec != null) {
                        GridPos stance = TaskExecutionSystem.computeTaskStance(task.sequence);
                        NpcTaskPackage pkg = NpcTaskPackage.resumeFrom(
                                "global:" + task.id, task.sequence, stance, task.priority,
                                task.stepIndex);
                        bestExec.npcQueue.enqueueNormal(pkg);
                    }
                    taskPool.assignLight(task.id, bestNpc, world);
                    occupiedTargets.add(taskTarget); // mark target as occupied
                    Log.info(TAG, "assigned #%d '%s' → NPC %d (score=%.2f dist=%.0f)",
                            task.id, task.sequence.label(), bestNpc, bestScore, bestDist);
                    colonyNpcs.remove(bestNpc); // NPC is now busy
                    if (colonyNpcs.isEmpty()) break;
                    continue; // next task
                }

                // No NPC matched — log why
                if (!task.requirements.isEmpty() && !colonyNpcs.isEmpty()) {
                    for (long npcId : colonyNpcs) {
                        WandCarrier wc = world.get(npcId, WandCarrier.class);
                        ManaPool mp = world.get(npcId, ManaPool.class);
                        ColonyMember cm = world.get(npcId, ColonyMember.class);
                        Log.warn(TAG, "  NO_MATCH task #%d '%s' reqs=%s npc=%d colony=%s caps=%s mana=%.1f/%d isEmpty=%b",
                                task.id, task.sequence.label(), task.requirements, npcId,
                                cm != null ? cm.colonyId().toString().substring(0, 8) : "?",
                                wc != null ? wc.capabilities().toString() : "null",
                                mp != null ? mp.current() : -1,
                                mp != null ? mp.max() : -1,
                                mp != null ? mp.isEmpty() : true);
                    }
                }

                // No NPC satisfies the task requirements.
                // Try warehouse wand provisioning with lifecycle reservation.
                if (!task.requirements.isEmpty() && wandProvider != null) {
                    UUID colonyId = entry.getKey();
                    WandLifecycle wandLifecycle = world.wandLifecycle;
                    String wandId = wandProvider.findWand(task.requirements, colonyId);
                    if (wandId != null
                            && wandLifecycle != null
                            && wandLifecycle.isAvailable(colonyId, wandId)
                            && wandLifecycle.reserve(colonyId, wandId)) {
                        // Inject wand equip as urgent package before task
                        for (long npcId : colonyNpcs) {
                            TaskExecutor exec = world.get(npcId, TaskExecutor.class);
                            WandCarrier wc = world.get(npcId, WandCarrier.class);
                            if (exec == null || wc == null) continue;

                            exec.npcQueue.enqueueUrgent(NpcTaskPackage.system(
                                    "system:wand_equip",
                                    new AtomicOp.WandEquipOp(wandId), null, 70));
                            GridPos stance = TaskExecutionSystem.computeTaskStance(task.sequence);
                            NpcTaskPackage pkg = NpcTaskPackage.resumeFrom(
                                    "global:" + task.id, task.sequence, stance, task.priority,
                                    task.stepIndex);
                            exec.npcQueue.enqueueNormal(pkg);
                            taskPool.assignLight(task.id, npcId, world);
                            Log.info(TAG, "provisioned wand %s for #%d '%s' → NPC %d",
                                    wandId, task.id, task.sequence.label(), npcId);
                            colonyNpcs.remove(npcId);
                            break;
                        }
                        // reserve succeeded but no NPC usable → release wand reservation
                        if (colonyNpcs.isEmpty() && wandLifecycle != null) {
                            wandLifecycle.release(colonyId, wandId);
                        }
                        if (!colonyNpcs.isEmpty()) continue;
                    }
                }

                // No NPC satisfies the task requirements and no wand in warehouse.
                // Don't fail immediately — a wand may be in transit (WandReturn).
                // Retry up to MAX_RETRIES heartbeats (~3s), then let FailureAnalyzer recover.
                if (!task.requirements.isEmpty()) {
                    task.schedulerRetryCount++;
                    if (task.schedulerRetryCount >= MAX_RETRIES) {
                        var reason = new TaskFailureReason.WandRequirementUnmet(task.requirements);
                        taskPool.failTask(task.id, reason);
                        Log.warn(TAG, "failed #%d '%s' reason=%s reqs=%s after %d retries",
                                task.id, task.sequence.label(), reason, task.requirements,
                                task.schedulerRetryCount);
                    } else {
                        Log.debug(TAG, "no match for #%d '%s' reqs=%s (retry %d/%d)",
                                task.id, task.sequence.label(), task.requirements,
                                task.schedulerRetryCount, MAX_RETRIES);
                    }
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
