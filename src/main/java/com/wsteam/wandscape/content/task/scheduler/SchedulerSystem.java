package com.wsteam.wandscape.content.task.scheduler;
import com.wsteam.wandscape.content.task.boundary.EntityOps;

import com.google.gson.JsonElement;
import com.wsteam.wandscape.content.task.component.ColonyMember;
import com.wsteam.wandscape.content.task.component.NpcInventory;
import com.wsteam.wandscape.content.task.component.Position;
import com.wsteam.wandscape.content.task.component.TaskExecutor;
import com.wsteam.wandscape.content.task.ecs.EcsSystem;
import com.wsteam.wandscape.content.task.ecs.World;
import com.wsteam.wandscape.content.task.types.GridPos;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.log.LogCategory;
import com.wsteam.wandscape.content.task.engine.pool.GlobalTask;
import com.wsteam.wandscape.content.task.engine.pool.GlobalTaskPool;
import com.wsteam.wandscape.content.task.runtime.ExecutorState;
import com.wsteam.wandscape.content.task.runtime.NpcTaskPackage;
import com.wsteam.wandscape.content.task.runtime.TaskState;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Assigns global tasks to idle NPCs.
 * Runs every {@code heartbeatInterval} ticks (interval wired from
 * {@code SCHEDULER_HEARTBEAT_TICKS} at bootstrap).
 * <p>
 * Phase 3 (migration): uses EquipmentComponent for scoring.
 * Scoring: proximity × 0.6 + mana efficiency × 0.4 (temp, being replaced by attribute-weighted).
 */
public class SchedulerSystem implements EcsSystem {

    private final int heartbeatInterval;
    private int tickCounter = 0;

    /**
     * 「立刻跑一轮」请求：任务刚完工、法师空出来时由 {@link TaskExecutionSystem} 置位。
     * 空出来的法师若等心跳（{@link #heartbeatInterval} tick，默认 1 秒）才拿到下一条活，
     * 玩家看到的就是「干几秒、手上停一下」。
     */
    private boolean immediatePassRequested;

    private static final String TAG = "Scheduler";

    /** @param heartbeatInterval ticks between scheduling runs */
    public SchedulerSystem(int heartbeatInterval) {
        this.heartbeatInterval = heartbeatInterval;
    }

    /** 请求下一 tick 立刻跑一轮派活（不等心跳）。 */
    public void requestImmediatePass() {
        immediatePassRequested = true;
    }

    @Override
    public void update(World world, float delta) {
    tickCounter++;
    boolean heartbeat = tickCounter % heartbeatInterval == 0;
    if (!heartbeat && !immediatePassRequested) return;
    immediatePassRequested = false;

    // 1. Find all idle NPCs with full component set
    // 跟随模式：NPC 不接取任何小镇任务，从空闲候选中排除
    // 幽灵 NPC（MC 实体缺失/已移除，如区块卸载）：任务不得派给不存在的工人
    List<Long> idleNpcs = new ArrayList<>();
    for (long entity : world.query(Position.class, TaskExecutor.class,
            NpcInventory.class, ColonyMember.class)) {
        TaskExecutor exec = world.get(entity, TaskExecutor.class);
        if (exec != null && exec.state == ExecutorState.IDLE
                && exec.npcQueue.isIdle() && exec.globalTaskId == null
                && (world.entityOps == null || !world.entityOps.isFollowing(entity))
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

        // 在跑的任务一次扫完两件事：
        //   ① occupiedTargets：同一目标位置不重复派人（原有口径）；
        //   ② servedBuildings：哪些任务组已经有工人——公平派活序的判据（见 fairOrder）。
        Set<GridPos> occupiedTargets = new HashSet<>();
        Set<UUID> servedBuildings = new HashSet<>();
        for (GlobalTask t : taskPool.getByState(TaskState.IN_PROGRESS)) {
            GridPos target = extractTaskTarget(t);
            if (target != null) occupiedTargets.add(target);
            if (t.buildingId != null) servedBuildings.add(t.buildingId);
        }

        List<GlobalTask> assignable = fairOrder(taskPool.getAssignableTasks(), servedBuildings);
        if (assignable.isEmpty()) continue;

        for (GlobalTask task : assignable) {
            // Skip if another NPC is already working on the same target position
            GridPos taskTarget = extractTaskTarget(task);
            if (taskTarget != null && occupiedTargets.contains(taskTarget)) {
                continue;
            }

            // 建筑委派（Building Delegation）：这座建筑是否已被委派给某一名法师。
            // 已委派 → 它的任务只许那名法师接；那名法师不在岗（别的活/不在世）时任务就等，
            // 绝不"降级"给别人——这正是委派与普通抢单的区别。
            long buildingDelegate = (task.buildingId != null && world.entityOps != null)
                    ? world.entityOps.delegatedNpcOf(task.buildingId) : -1;

            // 任务可声明小镇归属 + 魔力门槛（如祭坛施法）：
            // 只分给指定小镇的 NPC，且其当前魔力必须 ≥ 任务蓝耗（不足则任务挂起，等回蓝）。
            String taskColony = taskColonyFilter(task);
            if (taskColony != null && !taskColony.equals(entry.getKey().toString())) {
                continue;
            }
            int manaRequirement = taskManaRequirement(task);
            boolean casterOnly = taskIsCasterOnly(task);

            // Find the best NPC for this task
            long bestNpc = -1;
            double bestScore = -1;
            double bestDist = -1;

            for (long npcId : colonyNpcs) {
                // 委派约束（与上面对称的另一半）：被委派的法师只接它那座建筑的任务——
                // 别的建筑的任务、以及 guard:attack 这类没有建筑归属的任务（含护卫）一律不接。
                if (world.entityOps != null) {
                    UUID npcPost = world.entityOps.delegatedBuildingOf(npcId);
                    if (npcPost != null
                            && (task.buildingId == null || !npcPost.equals(task.buildingId))) {
                        continue;
                    }
                    if (buildingDelegate >= 0 && buildingDelegate != npcId) {
                        continue;
                    }
                }

                // 施法者门槛：守卫/祭坛等任务由只认本模组法师的执行器实现，其它工作者接不了
                // （接了执行器拿不到实体，任务会瞬间"完成"并空转）
                if (casterOnly && (world.entityOps == null
                        || !world.entityOps.canCastColonyMagic(npcId))) {
                    continue;
                }

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
                    GridPos stance = TaskExecutionSystem.resolveTaskStance(task);
                    NpcTaskPackage pkg = NpcTaskPackage.resumeFrom(
                            "global:" + task.id, task.sequence, stance, task.priority,
                            task.stepIndex);
                    bestExec.npcQueue.enqueueNormal(pkg);
                }
                taskPool.assignLight(task.id, bestNpc, world);
                occupiedTargets.add(taskTarget);
                Log.debug(LogCategory.TASK, "scheduler", "assigned #%d '%s' → NPC %d (score=%.2f dist=%.0f)",
                        task.id, task.sequence.label(), bestNpc, bestScore, bestDist);
                colonyNpcs.remove(bestNpc);
                if (colonyNpcs.isEmpty()) break;
                continue;
            }

            // No NPC matched — log diagnostics
            if (!colonyNpcs.isEmpty()) {
                ColonyMember cm = world.get(colonyNpcs.get(0), ColonyMember.class);
                Log.debug(LogCategory.TASK, "scheduler", "NO_MATCH task #%d '%s' — no suitable NPC in colony=%s",
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

    /**
     * 公平派活序：**先给「还没有工人的任务组」各排一条**，其余条目按原优先序排在其后。
     *
     * <p>为什么必须这样排：宏建筑会被拆成几十条批次任务，按纯粹优先序派活时，先入池的那栋楼
     * （批次最多、id 最老）会一直霸占所有空出来的法师，玩家后放的建筑 B、C 永远等不到人。
     * 现在的口径是「先保证每个任务/每栋建筑都先分到一个人，只有还剩空闲法师时才让第二个、
     * 第三个工人去做同一件事」——也就是空闲法师才去协同建造。
     *
     * <p>组 = {@code building_id}；没有归属建筑的任务（采集点、路段、祭坛施法等）各自成组，
     * 它们同样属于「还没人做」。组内与组间都保持入参的顺序（priority desc → createdAt asc →
     * id asc），所以地基批次永远排在屋顶之前、高优先任务组的第一个人也排在低优先任务组之前。
     *
     * <p>任务可以声明建筑委派（只许某一名法师接）：委派约束在下面的候选循环里生效，
     * 这里只负责排序，不动委派语义。
     */
    private static List<GlobalTask> fairOrder(List<GlobalTask> assignable, Set<UUID> servedBuildings) {
        List<GlobalTask> firstWorker = new ArrayList<>();
        List<GlobalTask> spareWorkers = new ArrayList<>();
        Set<UUID> alreadyPicked = new HashSet<>();

        for (GlobalTask task : assignable) {
            UUID building = task.buildingId;
            if (building == null) {
                firstWorker.add(task); // 无归属建筑 = 自成一组，永远算「还没人做」
            } else if (!servedBuildings.contains(building) && alreadyPicked.add(building)) {
                firstWorker.add(task);
            } else {
                spareWorkers.add(task);
            }
        }

        if (firstWorker.isEmpty()) return assignable;
        List<GlobalTask> ordered = new ArrayList<>(firstWorker.size() + spareWorkers.size());
        ordered.addAll(firstWorker);
        ordered.addAll(spareWorkers);
        return ordered;
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

    /**
     * 任务是否**只能由会施放殖民地法术的工作者**接取（params["caster_only"]；任务源声明）。
     *
     * <p>守卫 {@code guard:attack} 与祭坛施法的 MC 执行器只认本模组法师——拿不到法师时会把任务
     * 立刻判为完成。若让第三方工作者接取，会变成"接了不动、威胁没处理、
     * 任务源再发布"的空转，故在候选阶段就挡掉（判定见 {@code ColonyWorker#canCastColonyMagic}）。
     */
    private static boolean taskIsCasterOnly(GlobalTask task) {
        JsonElement el = task.taskParams.get("caster_only");
        return el != null && el.isJsonPrimitive() && el.getAsBoolean();
    }

}
