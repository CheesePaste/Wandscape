package com.wsteam.wandscape.content.building.internal;

import com.wsteam.wandscape.content.npc.internal.EntityComponentBridge;
import com.wsteam.wandscape.content.npc.worker.ColonyWorker;
import com.wsteam.wandscape.content.task.component.ColonyMember;
import com.wsteam.wandscape.content.task.component.TaskExecutor;
import com.wsteam.wandscape.content.task.ecs.World;
import com.wsteam.wandscape.content.task.runtime.ExecutorState;
import com.wsteam.wandscape.foundation.log.Log;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 建筑委派（Building Delegation）——**「一名法师专职一座建筑」这套规则的唯一命名类**。
 *
 * <p>规则一共三条，全部由本类与 {@code SchedulerSystem} / {@code TaskExecutionSystem} 的两处
 * 门槛共同保证（存储层 {@link BuildingSavedData#setDelegatedMage} 保证「一法师只一座建筑」）：
 * <ol>
 *   <li>被委派的法师**只做**该建筑派发的任务（别的建筑的任务、护卫等无主任务一律不接）；</li>
 *   <li>该建筑的**任务只派给**它的委派法师（委派法师不在岗时任务就等着，不落给别人）；</li>
 *   <li>一名法师同时只能被委派到一座建筑（再次委派 = 从原岗位调走）。</li>
 * </ol>
 *
 * <p>存储落在建筑身上（{@link BuildingState#getDelegatedMage()}，随建筑存档），因为委派
 * 是建筑的属性、且建筑拆除时本就该一并消失；任务域只通过
 * {@code EntityOps} 的两个查询方法读它。真正的派发/释放判定不在这里——那是任务域的事，
 * 本类只负责**校验与写入**，以及 UI 需要的候选法师列表。
 *
 * <p>纯数据 + 校验，不含任务派发逻辑；对 MC 的依赖只有 {@link Level}（取存档）与实体名解析。
 */
public final class BuildingDelegation {

    private static final String TAG = "BuildingDelegation";

    /**
     * 支持委派的建筑类别：物品工坊 / 装备工坊 / 魔法工坊 / 采集节点。
     *
     * <p>以后要给别的建筑开委派，**只往这里加一项**——面板按钮与网络层的校验都读它。
     * 刻意不复用 {@code BuildingSavedData.SHARED_QUEUE_CATEGORIES}（眼下两者内容相同）：
     * 「共享队列」与「可委派」是两套规则，谁先变都不该拖着另一个走。
     */
    private static final Set<String> SUPPORTED_CATEGORIES =
            Set.of("workstation", "crafting_station", "magic_station", "node");

    private BuildingDelegation() {}

    // ── 规则判定 ──

    /** 该建筑类别是否支持委派。 */
    public static boolean supports(@Nullable String category) {
        return category != null && SUPPORTED_CATEGORIES.contains(category);
    }

    /** 该建筑是否支持委派（类别白名单 + 已建成：未完工的工地还没有"岗位"可言）。 */
    public static boolean supports(BuildingState state) {
        return state != null && supports(state.getCategory()) && state.hasEverCompleted();
    }

    /** 委派结果；非 OK 用于回执文案。 */
    public enum Result {
        OK,
        /** 建筑不存在（已拆/坐标失效）。 */
        NO_BUILDING,
        /** 该建筑类别不支持委派。 */
        UNSUPPORTED,
        /** 未完工：工地在建成前不开放委派。 */
        UNDER_CONSTRUCTION,
        /** 目标法师不存在（已阵亡/被解散）。 */
        NO_MAGE,
        /** 法师与建筑不属于同一个小镇。 */
        WRONG_COLONY
    }

    // ── 读写 ──

    /**
     * 把 {@code mageUuid} 委派到 {@code buildingId}（该法师原先的岗位自动解除）。
     *
     * <p>只写数据，不搬任务：被委派法师手上**不属于该建筑**的活、以及该建筑落在别人手上的活，
     * 都由 {@code TaskExecutionSystem} 的逐拍委派约束在下一拍自动释放回池、由调度器按新归属重派。
     * 那里已有「释放任务（保留步进 + 退还已取元素 + 丢全局包）」的成熟路径，本类不另造一套。
     */
    public static Result delegate(UUID buildingId, UUID mageUuid) {
        BuildingSavedData sd = savedData();
        if (sd == null) return Result.NO_BUILDING;
        BuildingState state = sd.getBuilding(buildingId);
        if (state == null) return Result.NO_BUILDING;
        if (!supports(state.getCategory())) return Result.UNSUPPORTED;
        if (!state.hasEverCompleted()) return Result.UNDER_CONSTRUCTION;

        Long ecsId = EntityComponentBridge.INSTANCE.getEcsId(mageUuid);
        ColonyWorker worker = ecsId != null ? EntityComponentBridge.INSTANCE.getWorker(ecsId) : null;
        if (workerGone(worker)) return Result.NO_MAGE;

        World world = World.getActive();
        ColonyMember member = world != null ? world.get(ecsId, ColonyMember.class) : null;
        UUID mageColony = member != null ? member.colonyId() : null;
        if (state.getColonyId() == null || !state.getColonyId().equals(mageColony)) {
            return Result.WRONG_COLONY;
        }

        // setDelegatedMage 内部会摘掉该法师原先的岗位（一个法师只服务一座建筑）
        sd.setDelegatedMage(buildingId, mageUuid);
        Log.info(TAG, "building {} delegated to mage {} ({})",
                buildingId.toString().substring(0, 8),
                worker.entity().getName().getString(),
                mageUuid.toString().substring(0, 8));
        return Result.OK;
    }

    /** 解除建筑的委派（没有委派时是空操作）。 */
    public static void clear(UUID buildingId) {
        BuildingSavedData sd = savedData();
        if (sd == null) return;
        UUID mage = sd.getDelegatedMage(buildingId);
        if (mage == null) return;
        sd.setDelegatedMage(buildingId, null);
        Log.info(TAG, "building {} delegation cleared (was mage {})",
                buildingId.toString().substring(0, 8), mage.toString().substring(0, 8));
    }

    /**
     * 法师不再存在（阵亡/解散）时解约：由 {@code EntityComponentBridge.onWorkerLeaveWorld} 调用。
     * 不这么做的话，那座建筑会留着一个永远不会到岗的委派，它的任务全部卡死。
     *
     * <p>但「离开 ECS」不等于「人没了」：第三方工作者的对账钩子（{@code ColonyWorkerApiImpl.tick}）
     * 把区块卸载也当成移除，而卸载的法师还会回来——它的岗位必须留着。判据收在这里而不是靠调用方，
     * 因为这是唯一同时看到「离场事件」与「离场原因」的地方（口径同 {@link #workerGone}）。
     *
     * @return 被解约的建筑 id；该法师本就没委派、或只是区块卸载返回 null
     */
    @Nullable
    public static UUID onMageGone(UUID mageUuid) {
        Long ecsId = EntityComponentBridge.INSTANCE.getEcsId(mageUuid);
        ColonyWorker worker = ecsId != null ? EntityComponentBridge.INSTANCE.getWorker(ecsId) : null;
        if (worker != null && !workerGone(worker)) {
            Log.debug(TAG, "mage {} left ECS but is only unloaded — delegation kept",
                    mageUuid.toString().substring(0, 8));
            return null;
        }
        BuildingSavedData sd = savedData();
        if (sd == null) return null;
        UUID buildingId = sd.clearDelegationOfMage(mageUuid);
        if (buildingId != null) {
            Log.info(TAG, "mage {} gone — building {} delegation released",
                    mageUuid.toString().substring(0, 8), buildingId.toString().substring(0, 8));
        }
        return buildingId;
    }

    /** 建筑存档（主世界）；服务器未运行返回 null。 */
    @Nullable
    private static BuildingSavedData savedData() {
        var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        return server != null ? BuildingSavedData.get(server.overworld()) : null;
    }

    /** 该建筑委派法师的显示名；未委派或法师已不在世返回 null。 */
    @Nullable
    public static String delegatedMageName(BuildingSavedData sd, UUID buildingId) {
        if (sd == null) return null;
        UUID mage = sd.getDelegatedMage(buildingId);
        if (mage == null) return null;
        Long ecsId = EntityComponentBridge.INSTANCE.getEcsId(mage);
        ColonyWorker worker = ecsId != null ? EntityComponentBridge.INSTANCE.getWorker(ecsId) : null;
        if (workerGone(worker)) return null;
        return worker.entity().getName().getString();
    }

    // ── 面板候选 ──

    /**
     * 工作者是否**真的没了**（阵亡/被销毁），而不是「区块卸载中」。
     *
     * <p>两者在 MC 里都表现为 {@code isRemoved()==true}，只有 {@code RemovalReason#shouldDestroy()}
     * 分得开：卸载的法师还会回来，它的委派必须留着；阵亡的不会，委派必须解约。
     * 这也是 {@code ReviveHandler.isBridgeEntryAlive} 的同一口径。
     */
    private static boolean workerGone(@Nullable ColonyWorker worker) {
        if (worker == null) return true;
        var entity = worker.entity();
        if (!entity.isRemoved()) return false;
        var reason = entity.getRemovalReason();
        return reason == null || reason.shouldDestroy();
    }


    /**
     * 面板「选择一名法师」列表的一行。
     *
     * @param mageUuid            法师（NPC UUID）
     * @param name                显示名
     * @param state               {@code "IDLE"} / {@code "BUSY"} / {@code "FOLLOWING"}——客户端据此出文案
     * @param delegatedBuilding   该法师当前的岗位建筑；未委派为 null
     * @param delegatedBuildingName 岗位建筑显示名（未委派为 ""）
     */
    public record Candidate(UUID mageUuid, String name, String state,
                            @Nullable UUID delegatedBuilding, String delegatedBuildingName) {}

    /**
     * 该建筑所在小镇里可以被委派的法师（在世的工作者，按名字排序）。
     *
     * <p>包含已被委派到**别的**建筑的法师——委派它们等于把该法师从原岗位调过来，
     * 列表里会显示原岗位名以免玩家误操作。
     */
    public static List<Candidate> candidates(BuildingSavedData sd, BuildingState state) {
        List<Candidate> result = new ArrayList<>();
        World world = World.getActive();
        if (sd == null || state == null || state.getColonyId() == null) return result;

        for (var entry : EntityComponentBridge.INSTANCE.allWorkers().entrySet()) {
            long ecsId = entry.getKey();
            ColonyWorker worker = entry.getValue();
            if (workerGone(worker)) continue;
            ColonyMember member = world != null ? world.get(ecsId, ColonyMember.class) : null;
            if (member == null || !state.getColonyId().equals(member.colonyId())) continue;

            UUID post = sd.getDelegatedBuildingOfMage(worker.workerId());
            String postName = "";
            if (post != null) {
                BuildingState postState = sd.getBuilding(post);
                postName = postState != null ? postState.getDisplayName() : post.toString().substring(0, 8);
            }

            TaskExecutor exec = world != null ? world.get(ecsId, TaskExecutor.class) : null;
            boolean busy = exec != null && (exec.state != ExecutorState.IDLE
                    || exec.globalTaskId != null || !exec.npcQueue.isIdle());
            String stateTag = worker.isFollowMode() ? "FOLLOWING" : (busy ? "BUSY" : "IDLE");

            result.add(new Candidate(worker.workerId(), worker.entity().getName().getString(),
                    stateTag, post, postName));
        }
        result.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.name(), b.name()));
        return result;
    }
}
