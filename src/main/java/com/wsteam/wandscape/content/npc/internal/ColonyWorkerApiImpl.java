package com.wsteam.wandscape.content.npc.internal;

import com.wsteam.wandscape.api.ColonyWorkerApi;
import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.content.npc.worker.MobColonyWorker;
import com.wsteam.wandscape.content.task.component.ColonyMember;
import com.wsteam.wandscape.content.task.ecs.World;
import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link ColonyWorkerApi} 的实现：外部模组把普通 {@link Mob} 登记为殖民地工人。
 *
 * <p>登记的全部动作就是造一个 {@link MobColonyWorker} 适配器交给
 * {@link EntityComponentBridge#onWorkerJoinWorld}——之后它和本模组法师走完全同一条工作链
 * （调度器按 ECS 组件派活、执行器经 {@code ColonyWorker} 解析实体），无需任何特判。
 *
 * <p>注销有两条路：外部显式调 {@link #dismiss}，或实体被其它模组移除（死亡/删除）后由
 * {@link #tick} 对账清理——后者不能依赖实体自己的回调，第三方实体不会调我们的
 * {@code onRemovedFromLevel}，漏了会留下"幽灵工作者占着全局任务不干活"。
 */
public final class ColonyWorkerApiImpl implements ColonyWorkerApi {

    private static final String TAG = "ColonyWorkerApi";

    /** 已登记的外部工作者：实体 UUID → 适配器。 */
    private final Map<UUID, MobColonyWorker> enlisted = new ConcurrentHashMap<>();

    @Override
    public boolean enlist(UUID colonyId, LivingEntity entity) {
        if (colonyId == null || entity == null) return false;

        if (!(entity instanceof Mob mob)) {
            Log.warn(TAG, "enlist rejected: {} is not a Mob (worker needs vanilla pathfinding)",
                    entity.getType());
            return false;
        }
        if (mob.isRemoved()) return false;

        // 必须是已注册的真实殖民地：占位/已删殖民地会被调度器挡住（isColonyRegistered），
        // 登记了也永远拿不到任务，不如当场拒绝并说清原因。
        var colonyApi = WandscapeApis.getColonyApiSilently();
        if (colonyApi == null || !colonyApi.getAllColonyIds().contains(colonyId)) {
            Log.warn(TAG, "enlist rejected: colony {} is not a registered colony",
                    colonyId.toString().substring(0, 8));
            return false;
        }

        MobColonyWorker existing = enlisted.get(mob.getUUID());
        if (existing != null) {
            if (colonyId.equals(existing.colonyId())) return true; // 幂等
            // 改归属：不动 ECS 实体，只换殖民地，避免重建组件打断在途任务
            existing.setColonyId(colonyId);
            World world = World.getActive();
            Long ecsId = EntityComponentBridge.INSTANCE.getEcsId(mob.getUUID());
            if (world != null && ecsId != null) {
                world.addComponent(ecsId, new ColonyMember(colonyId));
            }
            Log.info(TAG, "worker {} re-assigned to colony {}",
                    mob.getUUID().toString().substring(0, 8), colonyId.toString().substring(0, 8));
            return true;
        }

        World world = World.getActive();
        if (world == null) {
            Log.warn(TAG, "enlist failed: ECS engine not bootstrapped yet — retry after server start");
            return false;
        }

        MobColonyWorker worker = new MobColonyWorker(mob, colonyId);
        enlisted.put(mob.getUUID(), worker);
        EntityComponentBridge.INSTANCE.onWorkerJoinWorld(worker, world);
        Log.info(TAG, "enlisted {} as colony worker of {}",
                mob.getUUID().toString().substring(0, 8), colonyId.toString().substring(0, 8));
        return true;
    }

    @Override
    public void dismiss(LivingEntity entity) {
        if (entity == null) return;
        MobColonyWorker worker = enlisted.remove(entity.getUUID());
        if (worker == null) return;
        World world = World.getActive();
        if (world != null) {
            EntityComponentBridge.INSTANCE.onWorkerLeaveWorld(worker, world);
        }
        // 恢复它自己的 AI 移动（登记期间被 setControlFlag(MOVE,false) 关掉了）
        worker.setAiWanderingEnabled(true);
        Log.info(TAG, "dismissed worker {}", entity.getUUID().toString().substring(0, 8));
    }

    @Override
    public boolean isEnlisted(LivingEntity entity) {
        return entity != null && enlisted.containsKey(entity.getUUID());
    }

    @Override
    @Nullable
    public UUID getWorkerColony(LivingEntity entity) {
        if (entity == null) return null;
        MobColonyWorker worker = enlisted.get(entity.getUUID());
        return worker != null ? worker.colonyId() : null;
    }

    @Override
    public long getWorkerEcsId(LivingEntity entity) {
        if (entity == null || !enlisted.containsKey(entity.getUUID())) return -1;
        Long ecsId = EntityComponentBridge.INSTANCE.getEcsId(entity.getUUID());
        return ecsId != null ? ecsId : -1;
    }

    /**
     * 每 MC tick 对账：清掉已被其它模组移除（死亡 / /kill / 删除）的工作者。
     *
     * <p>第三方实体不会调本模组的 {@code onRemovedFromLevel}，所以这条路径必须自己扫；
     * 清理内容与显式 {@link #dismiss} 完全一致（共用 {@code onWorkerLeaveWorld}）。
     */
    public void tick() {
        if (enlisted.isEmpty()) return;
        World world = World.getActive();
        if (world == null) return;
        for (var it = enlisted.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            MobColonyWorker worker = entry.getValue();
            if (worker.entity().isRemoved()) {
                it.remove();
                EntityComponentBridge.INSTANCE.onWorkerLeaveWorld(worker, world);
                Log.debug(com.wsteam.wandscape.foundation.log.LogCategory.NPC, "worker",
                        "worker {} removed externally — cleaned up",
                        entry.getKey().toString().substring(0, 8));
            }
        }
    }
}
