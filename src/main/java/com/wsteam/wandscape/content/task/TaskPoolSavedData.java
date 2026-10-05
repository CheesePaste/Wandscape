package com.wsteam.wandscape.content.task;
import com.wsteam.wandscape.content.task.ecs.World;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.wsteam.wandscape.content.building.data.WorkItem;
import com.wsteam.wandscape.content.building.internal.BuildingSavedData;
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

    /** Signal that task state has changed and a save is needed. */
    public void markChanged() {
        setDirty();
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
                if (q.hasCompletionData()) {
                    CompoundTag compTag = new CompoundTag();
                    for (Map.Entry<String, String> ce : q.getCompletionData().entrySet()) {
                        compTag.putString(ce.getKey(), ce.getValue());
                    }
                    bTag.put("comp", compTag);
                }
                if (q.hasPendingBatches()) {
                    ListTag pbList = new ListTag();
                    for (TaskRequest req : q.getPendingBatches()) {
                        CompoundTag reqTag = taskRequestToNbt(req);
                        if (reqTag != null) {
                            pbList.add(reqTag);
                        }
                    }
                    bTag.put("pending_batches", pbList);
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

        Log.info(TAG, "[TaskPoolSavedData] saved {} tasks, {} building queues (nextId={})",
                list.size(), buildingPool != null ? buildingPool.totalBuildings() : 0, pool.getNextTaskId());
        return tag;
    }

    private static CompoundTag taskToNbt(GlobalTask task) {
        if (task.blueprintId == null) return null;
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

    private static CompoundTag taskRequestToNbt(TaskRequest req) {
        CompoundTag tag = new CompoundTag();
        tag.putString("bp", req.blueprintId());
        tag.putInt("priority", req.priority());
        if (req.colonyId() != null) {
            tag.putUUID("colony", req.colonyId());
        }
        writeParams(tag, req.params());
        return tag;
    }

    @Nullable
    private static TaskRequest taskRequestFromNbt(CompoundTag tag) {
        String bp = tag.getString("bp");
        if (bp.isEmpty()) return null;
        int priority = tag.getInt("priority");
        UUID colonyId = tag.contains("colony") ? tag.getUUID("colony") : null;
        Map<String, JsonElement> params = readParams(tag);
        return new TaskRequest(bp, params, priority, colonyId);
    }

    private static CompoundTag workItemToNbt(WorkItem item) {
        CompoundTag tag = new CompoundTag();
        tag.putString("bp", item.blueprintId());
        tag.putInt("priority", item.priority());
        writeParams(tag, item.params());
        return tag;
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
                                          CompoundTag tag, @Nullable net.minecraft.server.level.ServerLevel level) {
        ListTag list = tag.getList("tasks", Tag.TAG_COMPOUND);
        int loaded = 0;
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            long originalId = t.getLong("id");
            GlobalTask task = taskFromNbt(t, pool, originalId, level);
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

        int loadedQueues = 0;
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
                BuildingTaskQueue q = new BuildingTaskQueue();
                if (bTag.contains("colony")) {
                    q.setColonyId(bTag.getUUID("colony"));
                }
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
                if (bTag.contains("comp")) {
                    CompoundTag compTag = bTag.getCompound("comp");
                    Map<String, String> compMap = new LinkedHashMap<>();
                    for (String key : compTag.getAllKeys()) {
                        compMap.put(key, compTag.getString(key));
                    }
                    q.setCompletionData(compMap);
                }
                if (bTag.contains("pending_batches")) {
                    ListTag pbList = bTag.getList("pending_batches", Tag.TAG_COMPOUND);
                    List<TaskRequest> pBatches = new ArrayList<>();
                    for (int j = 0; j < pbList.size(); j++) {
                        TaskRequest req = taskRequestFromNbt(pbList.getCompound(j));
                        if (req != null) {
                            pBatches.add(req);
                        }
                    }
                    q.setPendingBatches(pBatches);
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
                buildingPool.putQueue(bid, q);
                loadedQueues++;
            }
        }

        Log.info(TAG, "[TaskPoolSavedData] loaded {} tasks, {} building queues (nextId={})",
                loaded, loadedQueues, pool.getNextTaskId());
        return new TaskPoolSavedData(pool, buildingPool);
    }

    @Nullable
    private static GlobalTask taskFromNbt(CompoundTag tag, GlobalTaskPool pool, long originalId,
                                          @Nullable net.minecraft.server.level.ServerLevel level) {
        String blueprintId = tag.getString("bp");
        if (blueprintId.isEmpty()) return null;

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
