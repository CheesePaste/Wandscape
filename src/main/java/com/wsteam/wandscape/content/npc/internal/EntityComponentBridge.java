package com.wsteam.wandscape.content.npc.internal;
import com.wsteam.wandscape.content.task.boundary.RitualOps;
import com.wsteam.wandscape.content.task.boundary.EntityOps;

import com.wsteam.wandscape.impl.CoreBootstrap;
import com.wsteam.wandscape.content.task.component.ColonyMember;
import com.wsteam.wandscape.content.task.component.NpcInventory;
import com.wsteam.wandscape.content.task.component.Position;
import com.wsteam.wandscape.content.task.component.TaskExecutor;
import com.wsteam.wandscape.content.task.ecs.World;
import com.wsteam.wandscape.content.npc.types.FriendlyForce;
import com.wsteam.wandscape.content.task.types.GridPos;
import com.wsteam.wandscape.content.task.types.ResourceStack;
import com.wsteam.wandscape.content.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.content.npc.worker.ColonyWorker;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.log.LogCategory;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Singleton bridge between MC {@link WandscapeNpc} entities and the ECS World.
 *
 * <p>Maintains bidirectional mapping so that:
 * <ul>
 *   <li>The engine SchedulerSystem finds NPCs via ECS component queries.</li>
 *   <li>MC boundary implementations (EntityOps, RitualOps) find the MC entity
 *       from an ECS entity ID.</li>
 *   <li>NpcApiImpl resolves UUID → ECS entity → NPC data.</li>
 * </ul>
 *
 * <p>Calling convention:
 * <ul>
 *   <li>{@link #onNpcJoinWorld} — called from WandscapeNpc.onAddedToLevel</li>
 *   <li>{@link #onWorkerLeaveWorld} — called from WandscapeNpc.onRemovedFromLevel
 *       (KILLED / DISCARDED only; UNLOADED_TO_CHUNK is skipped)</li>
 *   <li>{@link #syncPositions} — called from Wandscape.onServerTick, before the
 *       engine tick gate</li>
 * </ul>
 */
public final class EntityComponentBridge {

    public static final EntityComponentBridge INSTANCE = new EntityComponentBridge();
    private static final String TAG = "EntityComponentBridge";

    /** Stage 2 placeholder colony — allows engine scheduling without real colonies. */
    public static final UUID PLACEHOLDER_COLONY = FriendlyForce.PLACEHOLDER_COLONY;

    // ecsEntityId → 工作者（本模组法师即 WandscapeNpc；第三方实体经 ColonyWorker 适配接入）
    private final Map<Long, ColonyWorker> workerByEcsId = new ConcurrentHashMap<>();
    // MC entity UUID → ecsEntityId
    private final Map<UUID, Long> ecsIdByUuid = new ConcurrentHashMap<>();

    /** NPCs that loaded before the engine was bootstrapped — flush on next tick. */
    private final List<WandscapeNpc> deferredJoins = new ArrayList<>();

    /** NpcInventory items to fill after ECS join (keyed by NPC UUID). */
    private final Map<UUID, java.util.List<ResourceStack>> deferredInventory = new ConcurrentHashMap<>();

    /** ECS component types that make up an NPC. */
    private static final Class<?>[] NPC_COMPONENTS = {
            Position.class,
            TaskExecutor.class,
            NpcInventory.class,
            ColonyMember.class,
    };

    private EntityComponentBridge() {}

    /**
     * Schedule inventory items to be filled into the NPC's ECS NpcInventory
     * after it joins the ECS world.
     *
     * <p>Used by {@code ColonyCommand} to give the builder NPC its starter
     * materials at colony creation time.
     */
    public void scheduleInventoryFill(UUID npcUuid, UUID colonyId,
                                      java.util.List<ResourceStack> items) {
        deferredInventory.put(npcUuid, items);
    }

    // ================================================================
    // Deferred join (for NPCs loaded before engine bootstrap)
    // ================================================================

    /**
     * Queue an NPC for ECS registration once the engine is ready.
     * Called from {@code WandscapeNpc.onAddedToLevel} when
     * {@code World.getActive()} returns null.
     */
    public void deferJoin(WandscapeNpc npc) {
        synchronized (deferredJoins) {
            deferredJoins.add(npc);
        }
    }

    /**
     * Flush all deferred NPC registrations.
     * Called from the engine tick once per frame when the world is live.
     */
    public void flushDeferredJoins(World world) {
        List<WandscapeNpc> pending;
        synchronized (deferredJoins) {
            if (deferredJoins.isEmpty()) return;
            pending = new ArrayList<>(deferredJoins);
            deferredJoins.clear();
        }
        for (WandscapeNpc npc : pending) {
            if (npc.isRemoved()) continue;
            Log.debug(LogCategory.NPC, "bridge", "Deferred NPC {} now joining ECS", npc.getUUID().toString().substring(0, 8));
            onNpcJoinWorld(npc, world);
        }
    }

    // ================================================================
    // Lifecycle
    // ================================================================

    /**
     * 通用登记：把任意 {@link ColonyWorker} 接入 ECS。本模组法师与第三方实体共用。
     *
     * <p>用 {@code ecsIdByUuid.containsKey()} 区分「同会话重连（区块卸载后重载）」与
     * 「跨会话 ECS id 碰撞」——{@link #clear()} 在换档时清空映射，故跨会话一律走新建分支。
     *
     * <p>不做殖民地自动探测——那是刷怪蛋法师的专有行为，见 {@link #onNpcJoinWorld}。
     * 殖民地完全取 {@link ColonyWorker#colonyId()}，为 null 时落占位殖民地。
     */
    public void onWorkerJoinWorld(ColonyWorker worker, World world) {
        UUID uuid = worker.workerId();
        var e = worker.entity();
        if (e.isRemoved()) return;

        Long reconnected = reconnectEcsId(uuid, world);
        if (reconnected != null) {
            // Same-session reconnection (chunk unload/reload): refresh Position only.
            // 刻意不碰 ColonyMember——见 onNpcJoinWorld 里的说明。
            world.addComponent(reconnected,
                    new Position(new GridPos(e.getBlockX(), e.getBlockY(), e.getBlockZ())));
            workerByEcsId.put(reconnected, worker);
            fillDeferredInventory(worker, world);
            return;
        }

        // Fresh registration (or cross-session: stale mapping already cleared)
        UUID colony = worker.colonyId() != null ? worker.colonyId() : PLACEHOLDER_COLONY;
        long ecsId = CoreBootstrap.createNpc(world, e.getBlockX(), e.getBlockY(), e.getBlockZ(), colony);
        workerByEcsId.put(ecsId, worker);
        ecsIdByUuid.put(uuid, ecsId);

        Log.debug(LogCategory.NPC, "bridge", "Worker {} joined ECS as entity {} (colony={})",
                uuid.toString().substring(0, 8), ecsId, colony.toString().substring(0, 8));

        // Fill deferred inventory items (e.g. from colony creation command)
        fillDeferredInventory(worker, world);
    }

    /**
     * 同会话重连（区块卸载后重载）判定：返回已知的 ECS id，非重连返回 null。
     *
     * <p>重连分支**只刷新 Position**，不重写 {@code ColonyMember} 等其它组件。调用方
     * （{@link #onNpcJoinWorld} 的殖民地自动探测）必须用同一个判定来对齐这个边界——
     * 否则会出现「字段改了、组件没改」的分歧：面板按 {@code npc.colonyId} 显示他已入镇，
     * 调度器按 {@code ColonyMember} 分组则永不派活。
     */
    @Nullable
    private Long reconnectEcsId(UUID uuid, World world) {
        Long known = ecsIdByUuid.get(uuid);
        return known != null && world.has(known, Position.class) ? known : null;
    }

    /**
     * 本模组法师的登记入口：先做 NPC 专有前置（无殖民地时按位置自动探测——刷怪蛋召唤入镇），
     * 再走 {@link #onWorkerJoinWorld} 通用登记，最后桥接法杖/盔甲属性修饰符。
     */
    public void onNpcJoinWorld(WandscapeNpc npc, World world) {
        // 只在「新建登记」时探测：重连分支不写 ColonyMember，此时改 npc.colonyId 会让字段与
        // 组件永久分歧（探测结果本应同时进 createNpc 的 colony 实参，重连路径上则没有这一步）。
        boolean reconnecting = reconnectEcsId(npc.getUUID(), world) != null;
        if (!reconnecting && (npc.colonyId == null || PLACEHOLDER_COLONY.equals(npc.colonyId))) {
            var colonyApi = com.wsteam.wandscape.api.WandscapeApis.getColonyApiSilently();
            if (colonyApi != null) {
                UUID detected = colonyApi.getColonyId(npc.blockPosition());
                if (detected != null) {
                    npc.colonyId = detected;
                    Log.debug(LogCategory.NPC, "bridge", "NPC {} auto-assigned to colony {} (spawn-egg detection)",
                            npc.getUUID().toString().substring(0, 8),
                            detected.toString().substring(0, 8));
                }
            }
        }

        onWorkerJoinWorld(npc, world);

        Long ecsId = ecsIdByUuid.get(npc.getUUID());
        if (ecsId != null) npc.ecsEntityId = ecsId;

        // Seed iron-spells and wand attribute bridges
        npc.syncWandAttributes();
        npc.syncIronArmorAttributes();
    }

    /** Fill inventory items that were scheduled before ECS registration. */
    private void fillDeferredInventory(ColonyWorker worker, World world) {
        java.util.List<ResourceStack> items =
                deferredInventory.remove(worker.workerId());
        if (items == null || items.isEmpty()) return;

        Long ecsId = ecsIdByUuid.get(worker.workerId());
        if (ecsId == null) return;

        NpcInventory inv = world.get(ecsId, NpcInventory.class);
        if (inv == null) {
            Log.warn(TAG, "[Bridge] Cannot fill inventory — worker {} has no NpcInventory component",
                    worker.workerId().toString().substring(0, 8));
            return;
        }

        int added = 0;
        for (ResourceStack stack : items) {
            if (inv.add(stack)) added++;
        }
        Log.debug(LogCategory.NPC, "bridge", "Filled worker {} inventory with {} stacks (colony={})",
                worker.workerId().toString().substring(0, 8), added,
                worker.colonyId() != null ? worker.colonyId().toString().substring(0, 8) : "?");
    }

    /** Clear all worker→ECS mappings. Called on world reset to prevent cross-session collisions. */
    public void clear() {
        workerByEcsId.clear();
        ecsIdByUuid.clear();
        deferredInventory.clear();
        Log.debug(LogCategory.NPC, "bridge", "EntityComponentBridge cleared — {} workers, {} UUIDs",
                workerByEcsId.size(), ecsIdByUuid.size());
    }

    /**
     * 工作者退出 ECS：释放全局任务（保留 stepIndex 供重派）+ 取消资源预留与在途运输 + 移除全部组件。
     *
     * <p>只在实体**真正销毁**（KILLED / DISCARDED）时调用。区块卸载（UNLOADED_TO_CHUNK）不清理——
     * 实体仍在，ECS 组件须保留以便重载后重连。
     *
     * <p>清理集中在此而非各实体自己的 {@code onRemovedFromLevel}：第三方工作者走同一份清理，
     * 避免「新接一个实体就漏掉释放任务/取消运输」，制造幽灵工作者占着全局任务不干活。
     */
    public void onWorkerLeaveWorld(ColonyWorker worker, World world) {
        Long ecsIdBoxed = ecsIdByUuid.get(worker.workerId());
        if (ecsIdBoxed == null) return;
        long ecsId = ecsIdBoxed;

        // Release global task for reassignment (preserve stepIndex)
        var exec = world.get(ecsId, TaskExecutor.class);
        if (exec != null && exec.globalTaskId != null) {
            world.taskPool.releaseTaskForReassign(exec.globalTaskId, ecsId, world);
        }

        // Release resource reservations from pending transports.
        // Items were reserved but never consumed — just dropping the
        // reservation is correct (no items need to be returned to bank).
        var rt = com.wsteam.wandscape.content.task.runtime.TaskRuntime.getActive();
        var resourceReqExec = rt != null ? rt.getResourceReqExec() : null;
        if (resourceReqExec != null) {
            resourceReqExec.cancelForNpc(ecsId);
        }

        // Orphan recovery: cancel all in-flight transports for this worker
        var transporter = com.wsteam.wandscape.content.warehouse.transport.ItemTransportManager.getInstance();
        if (transporter != null) {
            var bank = com.wsteam.wandscape.content.warehouse.ColonyItemBank.get(worker.entity().level());
            if (bank != null) {
                UUID cid = worker.colonyId() != null ? worker.colonyId() : PLACEHOLDER_COLONY;
                var member = world.get(ecsId, ColonyMember.class);
                if (member != null && member.colonyId() != null) cid = member.colonyId();
                transporter.cancelForNpc(ecsId, bank, cid);
            }
        }

        for (Class<?> comp : NPC_COMPONENTS) {
            world.removeComponent(ecsId, comp);
        }
        workerByEcsId.remove(ecsId);
        ecsIdByUuid.remove(worker.workerId());

        Log.debug(LogCategory.NPC, "bridge", "Worker {} left ECS (entity {})",
                worker.workerId().toString().substring(0, 8), ecsId);
    }

    // ================================================================
    // Per-tick sync
    // ================================================================

    /**
     * Sync MC entity positions → ECS Position components.
     * Called every MC tick, before the engine tick gate.
     */
    public void syncPositions(World world) {
        for (var entry : workerByEcsId.entrySet()) {
            ColonyWorker worker = entry.getValue();
            if (worker != null && !worker.entity().isRemoved()) {
                world.addComponent(entry.getKey(),
                        new Position(new GridPos(
                                worker.entity().getBlockX(),
                                worker.entity().getBlockY(),
                                worker.entity().getBlockZ())));
            }
        }
    }

    // ================================================================
    // Lookup
    // ================================================================

    /**
     * 按 ECS id 取工作者——**任务执行链（导航/挖放/搬运/仪式/属性）的唯一解析入口**。
     * 第三方工作者（车万女仆等）由此进入同一套原子操作执行器。
     */
    @Nullable
    public ColonyWorker getWorker(long ecsId) {
        return workerByEcsId.get(ecsId);
    }

    /**
     * 按 ECS id 取**本模组法师**。第三方工作者返回 null——交互/网络/UI 等 NPC 专有路径
     * 仍用本方法做严格判定，不受第三方实体接入影响。
     */
    @Nullable
    public WandscapeNpc getNpc(long ecsId) {
        return workerByEcsId.get(ecsId) instanceof WandscapeNpc npc ? npc : null;
    }

    @Nullable
    public Long getEcsId(UUID uuid) {
        return ecsIdByUuid.get(uuid);
    }

    /** 全部已映射工作者（含第三方）——任务面板等需要「并排显示」的场景用。 */
    public Map<Long, ColonyWorker> allWorkers() {
        return Map.copyOf(workerByEcsId);
    }

    /** 全部已映射法师（不含第三方工作者）。 */
    public Map<Long, WandscapeNpc> allNpcs() {
        Map<Long, WandscapeNpc> out = new java.util.HashMap<>();
        workerByEcsId.forEach((id, w) -> {
            if (w instanceof WandscapeNpc npc) out.put(id, npc);
        });
        return Map.copyOf(out);
    }
}
