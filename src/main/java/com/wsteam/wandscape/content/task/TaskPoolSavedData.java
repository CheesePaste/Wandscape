package com.wsteam.wandscape.content.task;
import com.wsteam.wandscape.content.task.ecs.World;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.wsteam.wandscape.content.building.data.WorkItem;
import com.wsteam.wandscape.content.building.internal.BuildingSavedData;
import com.wsteam.wandscape.content.building.internal.BuildingState;
import com.wsteam.wandscape.content.building.internal.ConstructionBatches;
import com.wsteam.wandscape.content.building.internal.EnqueueHelper;
import com.wsteam.wandscape.content.task.engine.pool.BuildingTaskPool;
import com.wsteam.wandscape.content.task.engine.pool.BuildingTaskQueue;
import com.wsteam.wandscape.content.task.types.ResourceId;
import com.wsteam.wandscape.content.task.types.ResourceStack;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.content.task.engine.pool.GlobalTask;
import com.wsteam.wandscape.content.task.engine.pool.GlobalTaskPool;
import com.wsteam.wandscape.content.task.engine.pool.TaskRequest;
import com.wsteam.wandscape.content.task.runtime.TaskState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Persists the {@link GlobalTaskPool} across world sessions via Minecraft {@link SavedData}.
 *
 * <p>Only non-COMPLETED tasks with a non-null {@code blueprintId} are persisted.
 * On load, tasks are recompiled from their blueprint; stepIndex and state are
 * restored so partially-completed tasks resume where they left off.
 *
 * <p>拆批建造（多法师批次）**不落盘任何批次 params**：全量写盘曾让本文件涨到 546 MB
 * （{@code pending_batches} 里上万条批次各带一份整栋 {@code blocks_nbt}）。建筑 JSON 是样式的
 * 唯一真源，所以：
 * <ul>
 *   <li>每条建筑队列只存 {@link ConstructionBatches.BatchJob} 那份标量进度（含**完成游标**）；</li>
 *   <li>子批次任务本身也不进 {@code tasks[]}（{@code isRebuiltBatchTask}），读档时用 JSON
 *       重建 WorkItem 再重新分批、按游标跳过已完成的前缀（见 {@link #rebuildBatchBuild}）；</li>
 *   <li>例外是**准备批次**（c=0，带 {@code pattern_offsets}）：它绝不重建（重发会再扣一遍整栋建材），
 *       照旧由通用任务持久化恢复。</li>
 * </ul>
 * 重建结果与存档记录不匹配（方块数对不上、游标越界、建筑类型已删）时按用户口径直接
 * **作废该建筑的建造队列**并 warn，不夹取游标硬放。
 *
 * <p>Serialisation deliberately uses hand-rolled CompoundTag rather than NBT codecs:
 * every field stored is a primitive scalar (long/int/String/UUID or gzip'd Gson JSON),
 * so nothing needs a {@code HolderLookup.Provider} — and the {@code Factory} already
 * threads {@code registries} through, keeping the signature future-proof. A codec migration
 * would not shrink this code nor lift the 64KB-per-StringTag cap (large blueprint JSON must
 * stay gzip'd either way). Revisit with NBT codecs only if a field starts storing
 * registry-bound data (ResourceKey / registry entries) after a Minecraft upgrade.
 */
public final class TaskPoolSavedData extends SavedData {

    private static final String TAG = "TaskPoolSavedData";
    private static final String DATA_NAME = "wandscape_tasks";

    /**
     * 建筑队列里「拆批建造持久化记录」的键（见 {@link ConstructionBatches.BatchJob}）。
     * 批次 params **不再落盘**：建筑 JSON 是样式的唯一真源，读档重建 WorkItem 再重新分批。
     */
    private static final String BATCH_TAG = "batch";

    /**
     * JSON params above this UTF-8 byte size are gzip-compressed before NBT storage.
     * NBT StringTag writes via modified-UTF8 with a hard 64KB limit (UTFDataFormatException);
     * large building blueprints (pattern / block_mapping) routinely exceed it.
     */
    private static final int MAX_PARAM_JSON_BYTES = 60000;

    private final GlobalTaskPool pool;
    @Nullable
    private final BuildingTaskPool buildingPool;

    private TaskPoolSavedData(GlobalTaskPool pool, @Nullable BuildingTaskPool buildingPool) {
        this.pool = pool;
        this.buildingPool = buildingPool;
    }

    /** Get or create the saved-data instance for the given pool and optional building task pool. */
    public static TaskPoolSavedData getOrCreate(
            net.minecraft.server.level.ServerLevel level, GlobalTaskPool pool, @Nullable BuildingTaskPool buildingPool) {
        return level.getDataStorage().computeIfAbsent(
                new Factory<>(() -> new TaskPoolSavedData(pool, buildingPool),
                        (tag, registries) -> load(pool, buildingPool, tag, level)),
                DATA_NAME);
    }

    // ================================================================
    // NBT save
    // ================================================================

    @Override
    public CompoundTag save(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (GlobalTask task : pool.getPersistableTasks()) {
            CompoundTag t = taskToNbt(task);
            if (t != null) {
                list.add(t);
            }
        }
        tag.put("tasks", list);
        tag.putLong("nextId", pool.getNextTaskId());

        int batchBuilds = 0;
        int completedBatchCount = 0;
        int pendingBatchCount = 0;
        if (buildingPool != null) {
            ListTag bqList = new ListTag();
            for (Map.Entry<UUID, BuildingTaskQueue> entry : buildingPool.getAll().entrySet()) {
                UUID bid = entry.getKey();
                BuildingTaskQueue q = entry.getValue();
                CompoundTag bTag = new CompoundTag();
                bTag.putUUID("bid", bid);
                if (q.getColonyId() != null) {
                    bTag.putUUID("colony", q.getColonyId());
                }
                // 拆批建造的活跃集合**不落盘**：placement 子批次本身不落盘（params 从 JSON 重建），
                // 准备批次由通用任务持久化恢复、随后由 BuildingTaskPool.rebuildFromPool 重新索引回本队列。
                // 存下来的旧 task id 读档时已失效，恢复它们会让完成游标误判（见 load）。
                if (!q.hasBatchJob()) {
                    if (q.getHeadTaskId() != null) {
                        bTag.putLong("head", q.getHeadTaskId());
                    }
                    if (q.hasActiveBatches()) {
                        ListTag activeList = new ListTag();
                        for (long id : q.getActiveBatchIds()) {
                            CompoundTag idTag = new CompoundTag();
                            idTag.putLong("id", id);
                            activeList.add(idTag);
                        }
                        bTag.put("active", activeList);
                    }
                    if (q.hasParked()) {
                        ListTag parkedList = new ListTag();
                        for (long id : q.getParkedTaskIds()) {
                            CompoundTag idTag = new CompoundTag();
                            idTag.putLong("id", id);
                            parkedList.add(idTag);
                        }
                        bTag.put("parked", parkedList);
                    }
                }
                if (q.hasCompletionData()) {
                    CompoundTag compTag = new CompoundTag();
                    for (Map.Entry<String, String> ce : q.getCompletionData().entrySet()) {
                        compTag.putString(ce.getKey(), ce.getValue());
                    }
                    bTag.put("comp", compTag);
                }
                if (q.hasBatchJob()) {
                    // 只落盘标量进度：批次 params 全量写盘曾让本文件涨到 546 MB（pending_batches 里
                    // 13854 条批次各带一份整栋 blocks_nbt）。读档用建筑 JSON 重建再重新分批。
                    ConstructionBatches.BatchJob job = q.getBatchJob();
                    CompoundTag jobTag = new CompoundTag();
                    jobTag.putInt("size", job.batchSize());
                    jobTag.putBoolean("prep", job.prepDone());
                    jobTag.putInt("done", job.completedBatches());
                    jobTag.putInt("blocks", job.blockCount());
                    jobTag.putInt("prio", job.priority());
                    jobTag.putBoolean("clearbox", job.clearBox());
                    bTag.put(BATCH_TAG, jobTag);
                    batchBuilds++;
                    completedBatchCount += job.completedBatches();
                    pendingBatchCount += q.getPendingBatches().size();
                }
                if (q.hasPending()) {
                    ListTag piList = new ListTag();
                    for (WorkItem wi : q.getPending()) {
                        CompoundTag wiTag = workItemToNbt(wi);
                        if (wiTag != null) {
                            piList.add(wiTag);
                        }
                    }
                    bTag.put("pending_items", piList);
                }
                bqList.add(bTag);
            }
            tag.put("building_queues", bqList);
        }

        // 观测点：批次 params 已不再落盘，这里把「完成游标」与下次读档要重建的批次数量打出来，
        // 便于实测核对（读档会对每条建筑重发 completedBatchCount 之后的 pendingBatchCount 条批次）。
        Log.info(TAG, "[TaskPoolSavedData] saved {} tasks, {} building queues "
                        + "({} batch builds: cursor at {} completed placement batches, "
                        + "{} pending batches rebuilt from building JSON on load; nextId={})",
                list.size(), buildingPool != null ? buildingPool.totalBuildings() : 0,
                batchBuilds, completedBatchCount, pendingBatchCount, pool.getNextTaskId());
        return tag;
    }

    private static CompoundTag taskToNbt(GlobalTask task) {
        if (task.blueprintId == null) return null;
        // 拆批建造的子批次不落盘：params 能从建筑 JSON + 完成游标完整重建（见 rebuildBatchBuild），
        // 而这里每条都带一份自成一体的 offsets/blocks/block_nbt 分片，magic_academy 一栋上万条。
        if (isRebuiltBatchTask(task)) return null;
        CompoundTag tag = new CompoundTag();
        tag.putLong("id", task.id);
        tag.putString("bp", task.blueprintId);
        tag.putInt("step", task.stepIndex);
        tag.putString("state", task.state.name());
        tag.putInt("priority", task.priority);
        // Mid-channel crafting checkpoint (block_interact channel): lets a reload
        // resume the channel instead of restarting the craft from zero.
        if (task.channelRemainingTicks > 0) {
            tag.putInt("chan_rem", task.channelRemainingTicks);
        }

        // Building attribution: needed after restart so a restored head task knows
        // which building it belongs to (lease release / duplicate-construction guard).
        if (task.buildingId != null) {
            tag.putUUID("bid", task.buildingId);
        }
        tag.putBoolean("head", task.isBuildingHead);

        writeParams(tag, task.taskParams);

        // awaitingResource (now a list, persisted as ListTag of CompoundTags)
        if (task.awaitingResource != null && !task.awaitingResource.isEmpty()) {
            ListTag awaitList = new ListTag();
            for (ResourceStack rs : task.awaitingResource) {
                CompoundTag res = new CompoundTag();
                res.putString("id", rs.resource().id());
                res.putInt("amt", rs.amount());
                awaitList.add(res);
            }
            tag.put("await", awaitList);
        }

        return tag;
    }

    private static CompoundTag workItemToNbt(WorkItem item) {
        CompoundTag tag = new CompoundTag();
        tag.putString("bp", item.blueprintId());
        tag.putInt("priority", item.priority());
        writeParams(tag, item.params());
        return tag;
    }

    /**
     * 拆批建造的子批次（c≥1 的 placement 批次）：params 能从建筑 JSON + 完成游标完整重建，
     * 落盘纯属冗余。识别口径与完成游标共用 {@link ConstructionBatches#isPlacementBatch}
     * —— 准备批次（c=0，带 pattern_offsets）与修复任务照旧全量落盘。
     */
    private static boolean isRebuiltBatchTask(GlobalTask task) {
        return ConstructionBatches.isPlacementBatch(task.taskParams);
    }

    @Nullable
    private static WorkItem workItemFromNbt(CompoundTag tag) {
        String bp = tag.getString("bp");
        if (bp.isEmpty()) return null;
        int priority = tag.getInt("priority");
        Map<String, JsonElement> params = readParams(tag);
        return new WorkItem(bp, params, priority);
    }

    private static void writeParams(CompoundTag target, Map<String, JsonElement> paramsMap) {
        if (paramsMap == null || paramsMap.isEmpty()) return;
        CompoundTag params = new CompoundTag();
        CompoundTag paramsCompressed = new CompoundTag();
        for (var entry : paramsMap.entrySet()) {
            String json = entry.getValue().toString();
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_PARAM_JSON_BYTES) {
                paramsCompressed.putByteArray(entry.getKey(), gzip(json));
            } else {
                params.putString(entry.getKey(), json);
            }
        }
        if (!params.isEmpty()) target.put("params", params);
        if (!paramsCompressed.isEmpty()) target.put("params_c", paramsCompressed);
    }

    private static Map<String, JsonElement> readParams(CompoundTag tag) {
        Map<String, JsonElement> taskParams = new HashMap<>();
        if (tag.contains("params_c")) {
            CompoundTag compressed = tag.getCompound("params_c");
            for (String key : compressed.getAllKeys()) {
                byte[] data = compressed.getByteArray(key);
                try {
                    taskParams.put(key, tryParseJson(ungzip(data)));
                } catch (IOException e) {
                    taskParams.put(key, tryParseJson(new String(data, StandardCharsets.UTF_8)));
                }
            }
        }
        if (tag.contains("params")) {
            CompoundTag paramsTag = tag.getCompound("params");
            for (String key : paramsTag.getAllKeys()) {
                String raw = paramsTag.getString(key);
                taskParams.put(key, tryParseJson(raw));
            }
        }
        return taskParams;
    }

    // ================================================================
    // NBT load
    // ================================================================

    private static TaskPoolSavedData load(GlobalTaskPool pool, @Nullable BuildingTaskPool buildingPool,
                                          CompoundTag tag, @Nullable ServerLevel level) {
        // 建筑队列先读：拆批建造的批次 params 不再落盘，要用建筑 JSON 重建；重建失败的建筑
        //（建筑类型已删 / 中途改过 JSON 导致方块数不匹配 / 旧档格式）必须连它的任务一起丢弃 ——
        // 否则 Wandscape 随后的 rebuildFromPool 会凭残留任务把刚作废的队列又立起来。
        Map<UUID, BuildingTaskQueue> restoredQueues = new LinkedHashMap<>();
        Set<UUID> voidedBuildings = new HashSet<>();
        int rebuiltQueues = 0;
        int rebuiltPendingBatches = 0;
        if (tag.contains("building_queues") && buildingPool != null) {
            ListTag bqList = tag.getList("building_queues", Tag.TAG_COMPOUND);
            for (int i = 0; i < bqList.size(); i++) {
                CompoundTag bTag = bqList.getCompound(i);
                UUID bid = bTag.getUUID("bid");
                if (level != null) {
                    var buildingSd = BuildingSavedData.get(level);
                    if (buildingSd != null && buildingSd.getBuilding(bid) == null) {
                        Log.info(TAG, "[TaskPoolSavedData] Dropping building queue for removed building {}", bid);
                        continue;
                    }
                }
                // 旧档断档（用户定的口径：不迁移）：pending_batches 是全量批次 params 的老形态。
                // 不认识却留着，会让"还差几千格"的建筑照样发 build_complete —— 整条作废并留痕。
                if (bTag.contains("pending_batches")) {
                    Log.warn(TAG, "[TaskPoolSavedData] building {} holds legacy 'pending_batches' "
                            + "(pre-refactor save); dropping its construction queue — repair or re-place it", bid);
                    voidedBuildings.add(bid);
                    continue;
                }
                BuildingTaskQueue q = new BuildingTaskQueue();
                if (bTag.contains("colony")) {
                    q.setColonyId(bTag.getUUID("colony"));
                }
                if (bTag.contains(BATCH_TAG)) {
                    // 拆批建造：head/active/parked **一律不恢复**（存的是过期 id，恢复会让完成游标误判）。
                    // 活跃集合全部重建：placement 批次按游标重发进 pendingBatches（下一次 poll 释放），
                    // 准备批次由通用任务持久化恢复、随后 rebuildFromPool 重新索引回本队列。
                    if (!rebuildBatchBuild(bTag.getCompound(BATCH_TAG), level, bid, q)) {
                        voidedBuildings.add(bid);
                        continue;
                    }
                    if (q.hasPendingBatches()) {
                        rebuiltQueues++;
                        rebuiltPendingBatches += q.getPendingBatches().size();
                    }
                } else {
                    if (bTag.contains("head")) {
                        q.setHeadTaskId(bTag.getLong("head"));
                    }
                    if (bTag.contains("active")) {
                        ListTag activeList = bTag.getList("active", Tag.TAG_COMPOUND);
                        for (int j = 0; j < activeList.size(); j++) {
                            q.addActiveBatch(activeList.getCompound(j).getLong("id"));
                        }
                    }
                    if (bTag.contains("parked")) {
                        ListTag parkedList = bTag.getList("parked", Tag.TAG_COMPOUND);
                        for (int j = 0; j < parkedList.size(); j++) {
                            q.addParked(parkedList.getCompound(j).getLong("id"));
                        }
                    }
                }
                if (bTag.contains("comp")) {
                    CompoundTag compTag = bTag.getCompound("comp");
                    Map<String, String> compMap = new LinkedHashMap<>();
                    for (String key : compTag.getAllKeys()) {
                        compMap.put(key, compTag.getString(key));
                    }
                    q.setCompletionData(compMap);
                }
                if (bTag.contains("pending_items")) {
                    ListTag piList = bTag.getList("pending_items", Tag.TAG_COMPOUND);
                    for (int j = 0; j < piList.size(); j++) {
                        WorkItem wi = workItemFromNbt(piList.getCompound(j));
                        if (wi != null) {
                            q.enqueue(wi);
                        }
                    }
                }
                restoredQueues.put(bid, q);
            }
        }

        ListTag list = tag.getList("tasks", Tag.TAG_COMPOUND);
        int loaded = 0;
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            long originalId = t.getLong("id");
            GlobalTask task = taskFromNbt(t, pool, originalId, level, voidedBuildings);
            if (task != null) {
                pool.addLoadedTask(task, originalId);
                loaded++;
            }
        }
        if (tag.contains("nextId")) {
            long savedNextId = tag.getLong("nextId");
            if (savedNextId > pool.getNextTaskId()) {
                pool.setNextTaskId(savedNextId);
            }
        }

        if (buildingPool != null) {
            for (Map.Entry<UUID, BuildingTaskQueue> entry : restoredQueues.entrySet()) {
                buildingPool.putQueue(entry.getKey(), entry.getValue());
            }
        }

        // 观测点：重建计数（rebuiltQueues/rebuiltPendingBatches）与作废建筑数，便于实测核对
        // —— 拆批建造的批次 params 已不落盘，读档必须重建出同样多的待发批次。
        Log.info(TAG, "[TaskPoolSavedData] loaded {} tasks, {} building queues "
                        + "({} batch builds with {} pending placement batches rebuilt from building JSON, "
                        + "{} buildings voided; nextId={})",
                loaded, restoredQueues.size(), rebuiltQueues, rebuiltPendingBatches,
                voidedBuildings.size(), pool.getNextTaskId());
        return new TaskPoolSavedData(pool, buildingPool);
    }

    /**
     * 读档重建一条拆批建造。
     *
     * <p>批次 params 一律不落盘：建筑 JSON 是样式的唯一真源，这里用 {@link EnqueueHelper#rebuildWorkItem}
     * 重建 WorkItem、再按存档里的**分批尺寸快照**重新分批，然后按**完成游标**决定重发范围：
     * 游标之前的 placement 批次视为已完成、跳过；游标及其之后的全部重发（含当时在途的那几批——
     * 从该批起点重跑，与"重进世界重跑整条"同语义，幂等，不新增丢失）。
     *
     * <p>重发不在这里直接发任务，而是塞回 {@code pendingBatches}：准备批次还没跑完时，
     * {@code checkBatchesProgress} 到不了释放分支（有活跃/停泊任务就提前 return），
     * 所以「prep 必须先完成」这条契约由队列语义天然保证。
     *
     * <p>**准备批次（c=0）不在这里重建**：它是普通 GlobalTask，由通用任务持久化恢复；
     * 重建一条会变成两条 prep 任务，而 {@code ResourceRequestExecutor.finish} 对 material_list
     * 是全额 commit（不查账本），等于再扣一遍整栋建材；何况 {@code skipMaterials}(首建免费)
     * 在 {@code claimFirstFree} 之后无法重算。
     *
     * @return false 表示重建失败，调用方必须作废该建筑的建造队列（warn + 丢弃，不许夹取游标硬放）
     */
    private static boolean rebuildBatchBuild(CompoundTag jobTag, @Nullable ServerLevel level,
                                             UUID buildingId, BuildingTaskQueue queue) {
        ConstructionBatches.BatchJob job = new ConstructionBatches.BatchJob(
                jobTag.getInt("size"), jobTag.getBoolean("prep"), jobTag.getInt("done"),
                jobTag.getInt("blocks"), jobTag.getInt("prio"), jobTag.getBoolean("clearbox"));
        queue.setBatchJob(job);

        // 不可能组合：placement 批次只在 Phase 2 释放之后才存在，而释放必然先判 prep 完成。
        // 出现它说明记录被改坏/与 JSON 错位 —— 作废，不夹取游标硬放。
        if (job.completedBatches() > 0 && !job.prepDone()) {
            Log.warn(TAG, "[TaskPoolSavedData] building {} batch record is inconsistent "
                    + "(cursor at {} completed batches but prep not done) — dropping its construction queue",
                    buildingId, job.completedBatches());
            return false;
        }

        if (level == null) {
            Log.warn(TAG, "[TaskPoolSavedData] building {}: no level to rebuild {} pending placement batches "
                    + "from building JSON — dropping its construction queue", buildingId, job.blockCount());
            return false;
        }
        var sd = BuildingSavedData.get(level);
        BuildingState state = sd != null ? sd.getBuilding(buildingId) : null;
        if (state == null) {
            Log.warn(TAG, "[TaskPoolSavedData] building {} has a batch record but no building state — "
                    + "dropping its construction queue", buildingId);
            return false;
        }
        int rebuiltBlocks;
        ConstructionBatches.SplitResult split;
        try {
            WorkItem work = EnqueueHelper.rebuildWorkItem(state, job.priority(), job.clearBox());
            if (work == null) return false; // rebuildWorkItem 已 warn
            rebuiltBlocks = ConstructionBatches.blockCount(work);
            if (rebuiltBlocks <= 0) {
                Log.warn(TAG, "[TaskPoolSavedData] building {}: rebuilt work has no block list "
                        + "(building JSON bind changed?) — dropping its construction queue", buildingId);
                return false;
            }
            if (rebuiltBlocks != job.blockCount()) {
                Log.warn(TAG, "[TaskPoolSavedData] building {}: building JSON changed mid-build — rebuilt {} "
                                + "blocks but {} were recorded at start; dropping its construction queue "
                                + "(no cursor clamping)",
                        buildingId, rebuiltBlocks, job.blockCount());
                return false;
            }
            split = ConstructionBatches.split(work, queue.getColonyId(), job.batchSize());
        } catch (RuntimeException e) {
            // 建筑 JSON 形态坏了（缺 offsets、block_mapping 类型不对……）：读档不能因此崩，
            // 也不能装着没事——作废该建筑的建造队列并留痕。
            Log.warn(TAG, "[TaskPoolSavedData] building {}: failed to rebuild batches from building JSON: {}",
                    buildingId, e.toString());
            return false;
        }
        int totalBatches = split.remainingBatches().size();
        if (totalBatches <= 0 || job.completedBatches() > totalBatches) {
            // 游标越过重建出的批次数 = 记录与 JSON 对不上（改了分批尺寸快照？），
            // 按用户口径作废，绝不把游标夹到边界上硬放。
            Log.warn(TAG, "[TaskPoolSavedData] building {}: batch cursor {} is out of range (rebuilt {} "
                            + "placement batches) — dropping its construction queue",
                    buildingId, job.completedBatches(), totalBatches);
            return false;
        }
        List<TaskRequest> toRepublish = ConstructionBatches.batchesFrom(split, job.completedBatches());
        queue.setPendingBatches(toRepublish);
        Log.info(TAG, "[TaskPoolSavedData] building {}: rebuilt {} placement batches, cursor={} → "
                        + "republishing {} (batchSize={}, prepDone={}, blocks={})",
                buildingId, totalBatches, job.completedBatches(), toRepublish.size(),
                job.batchSize(), job.prepDone(), rebuiltBlocks);
        return true;
    }

    @Nullable
    private static GlobalTask taskFromNbt(CompoundTag tag, GlobalTaskPool pool, long originalId,
                                          @Nullable ServerLevel level, Set<UUID> voidedBuildings) {
        String blueprintId = tag.getString("bp");
        if (blueprintId.isEmpty()) return null;

        // 队列刚被作废的建筑：它的建造链路任务一并丢弃（作废原因在队列侧已按建筑 warn 过一次，
        // 这里不重复刷屏）。只丢 build:* —— 同建筑上的生产/采集任务与这次作废无关，继续跑。
        if (!voidedBuildings.isEmpty() && blueprintId.startsWith("build:")
                && tag.contains("bid") && voidedBuildings.contains(tag.getUUID("bid"))) {
            return null;
        }

        Map<String, JsonElement> taskParams = readParams(tag);

        // Defense-in-depth: If this is a building task for a building that was deleted/undone, drop it
        if (level != null) {
            var buildingSd = BuildingSavedData.get(level);
            if (buildingSd != null) {
                java.util.UUID bid = null;
                if (tag.contains("bid")) {
                    bid = tag.getUUID("bid");
                } else if (taskParams.containsKey("building_id")) {
                    try {
                        bid = java.util.UUID.fromString(taskParams.get("building_id").getAsString());
                    } catch (Exception ignored) {}
                }
                if (bid != null && blueprintId.startsWith("build:") && buildingSd.getBuilding(bid) == null) {
                    Log.info(TAG, "[TaskPoolSavedData] Dropping orphaned task #{} ('{}') for removed building {}",
                            originalId, blueprintId, bid);
                    return null;
                }
            }
        }

        // Recompile from blueprint
        String stateName = tag.getString("state");
        TaskState state;
        try {
            state = TaskState.valueOf(stateName);
        } catch (IllegalArgumentException e) {
            state = TaskState.PENDING_ASSIGN;
        }
        // IN_PROGRESS → PENDING_ASSIGN on load (NPC assignment is lost across sessions)
        if (state == TaskState.IN_PROGRESS) {
            state = TaskState.PENDING_ASSIGN;
        }
        // Old INTERRUPTED state (removed) — caught by catch block above, maps to PENDING_ASSIGN

        int stepIndex = tag.getInt("step");
        int priority = tag.getInt("priority");

        // Recompile the blueprint to get TaskSequence, requirements, triggers.
        // Use the ORIGINAL id so the task's id field always matches its pool key —
        // otherwise assignLight(task.id) / get(task.id) resolve to the wrong entry
        // after a reload, leaving a ghost task that re-assigns the same task to a
        // new NPC every heartbeat.
        // colonyId 传 null：恢复任务的 colony_id 已随 taskParams 持久化，池不覆盖。
        TaskRequest request = new TaskRequest(blueprintId, taskParams, priority, null);
        try {
            long newId = pool.addTaskWithId(request, originalId);
            GlobalTask task = pool.get(newId);
            if (task != null) {
                task.state = state;
                task.stepIndex = stepIndex;

                // Restore mid-channel crafting checkpoint so the craft resumes,
                // not restarts, after a reload.
                if (tag.contains("chan_rem")) {
                    task.channelRemainingTicks = tag.getInt("chan_rem");
                }

                // Restore building attribution (see taskToNbt).
                if (tag.contains("bid")) {
                    task.buildingId = tag.getUUID("bid");
                    task.isBuildingHead = tag.getBoolean("head");
                }

                // Restore awaitingResource
                if (tag.contains("await")) {
                    List<ResourceStack> awaitList = new ArrayList<>();
                    Tag awaitTag = tag.get("await");
                    if (awaitTag instanceof ListTag listTag) {
                        for (int j = 0; j < listTag.size(); j++) {
                            CompoundTag entryTag = listTag.getCompound(j);
                            String resId = entryTag.getString("id");
                            int amt = entryTag.getInt("amt");
                            if (!resId.isEmpty() && amt > 0) {
                                awaitList.add(new ResourceStack(new ResourceId(resId), amt));
                            }
                        }
                    }
                    task.awaitingResource = awaitList.isEmpty() ? null : awaitList;
                }

                return task;
            }
        } catch (Exception e) {
            Log.warn(TAG, "[TaskPoolSavedData] failed to recompile blueprint '{}': {}",
                    blueprintId, e.getMessage());
        }
        return null;
    }

    /** Try to parse a JSON string; fall back to JsonPrimitive on failure. */
    private static JsonElement tryParseJson(String raw) {
        try {
            return com.google.gson.JsonParser.parseString(raw);
        } catch (Exception e) {
            return new JsonPrimitive(raw);
        }
    }

    /** Gzip a JSON string so large params fit within NBT's 64KB per-string limit. */
    private static byte[] gzip(String s) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(baos)) {
                gz.write(s.getBytes(StandardCharsets.UTF_8));
            }
            return baos.toByteArray();
        } catch (IOException e) {
            // Extremely unlikely (byte-array streams don't throw); store raw bytes as a fallback.
            Log.warn(TAG, "[TaskPoolSavedData] gzip failed, storing param raw: {}", e.getMessage());
            return s.getBytes(StandardCharsets.UTF_8);
        }
    }

    /** Inverse of {@link #gzip}; returns the original string. */
    private static String ungzip(byte[] data) throws IOException {
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(data));
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            gz.transferTo(baos);
            return baos.toString(StandardCharsets.UTF_8);
        }
    }
}
