package com.wsteam.wandscape.engine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.wsteam.wandscape.task.engine.pool.GlobalTask;
import com.wsteam.wandscape.task.engine.pool.GlobalTaskPool;
import com.wsteam.wandscape.task.engine.pool.TaskRequest;
import com.wsteam.wandscape.task.runtime.TaskState;
import com.wsteam.wandscape.core.types.ResourceId;
import com.wsteam.wandscape.core.types.ResourceStack;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Persists the {@link GlobalTaskPool} across world sessions via Minecraft {@link SavedData}.
 *
 * <p>Only non-COMPLETED tasks with a non-null {@code blueprintId} are persisted.
 * On load, tasks are recompiled from their blueprint; stepIndex and state are
 * restored so partially-completed tasks resume where they left off.
 *
 * <p>TODO: this should use {@code HolderLookup.Provider} for NBT codec support
 * in future MC versions. Current implementation uses raw CompoundTag.
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

    private TaskPoolSavedData(GlobalTaskPool pool) {
        this.pool = pool;
    }

    /** Get or create the saved-data instance for the given pool. */
    public static TaskPoolSavedData getOrCreate(
            net.minecraft.server.level.ServerLevel level, GlobalTaskPool pool) {
        return level.getDataStorage().computeIfAbsent(
                new Factory<>(() -> new TaskPoolSavedData(pool),
                        (tag, registries) -> load(pool, tag)),
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
        Log.info(TAG, "[TaskPoolSavedData] saved {} tasks (nextId={})", list.size(), pool.getNextTaskId());
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

        // taskParams: store each JsonElement value as a string; oversized JSON is gzip-compressed
        // into a ByteArrayTag because NBT StringTag has a 64KB write limit.
        if (!task.taskParams.isEmpty()) {
            CompoundTag params = new CompoundTag();
            CompoundTag paramsCompressed = new CompoundTag();
            for (var entry : task.taskParams.entrySet()) {
                String json = entry.getValue().toString();
                if (json.getBytes(StandardCharsets.UTF_8).length > MAX_PARAM_JSON_BYTES) {
                    paramsCompressed.putByteArray(entry.getKey(), gzip(json));
                } else {
                    params.putString(entry.getKey(), json);
                }
            }
            if (!params.isEmpty()) tag.put("params", params);
            if (!paramsCompressed.isEmpty()) tag.put("params_c", paramsCompressed);
        }

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

        // approval info (for PENDING_APPROVAL tasks)
        if (task.approval != null && task.approval.suggestedPosition() != null) {
            CompoundTag appr = new CompoundTag();
            var pos = task.approval.suggestedPosition();
            appr.putInt("sx", pos.x());
            appr.putInt("sy", pos.y());
            appr.putInt("sz", pos.z());
            appr.putLong("deadline", task.approval.deadline());
            appr.putBoolean("autoApproved", task.approval.autoApproved());
            tag.put("approval", appr);
        }

        return tag;
    }

    // ================================================================
    // NBT load
    // ================================================================

    private static TaskPoolSavedData load(GlobalTaskPool pool, CompoundTag tag) {
        ListTag list = tag.getList("tasks", Tag.TAG_COMPOUND);
        int loaded = 0;
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            long originalId = t.getLong("id");
            GlobalTask task = taskFromNbt(t, pool, originalId);
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
        Log.info(TAG, "[TaskPoolSavedData] loaded {} tasks (nextId={})", loaded, pool.getNextTaskId());
        return new TaskPoolSavedData(pool);
    }

    @Nullable
    private static GlobalTask taskFromNbt(CompoundTag tag, GlobalTaskPool pool, long originalId) {
        String blueprintId = tag.getString("bp");
        if (blueprintId.isEmpty()) return null;

        // Reconstruct taskParams. Compressed params first (gzip'd during save), then plain strings.
        Map<String, JsonElement> taskParams = new HashMap<>();
        if (tag.contains("params_c")) {
            CompoundTag compressed = tag.getCompound("params_c");
            for (String key : compressed.getAllKeys()) {
                byte[] data = compressed.getByteArray(key);
                try {
                    taskParams.put(key, tryParseJson(ungzip(data)));
                } catch (IOException e) {
                    // Fall back: bytes may be an uncompressed string if gzip failed during save.
                    taskParams.put(key, tryParseJson(new String(data, StandardCharsets.UTF_8)));
                }
            }
        }
        if (tag.contains("params")) {
            CompoundTag paramsTag = tag.getCompound("params");
            for (String key : paramsTag.getAllKeys()) {
                String raw = paramsTag.getString(key);
                // Parse via Gson to preserve JSON structure
                taskParams.put(key, tryParseJson(raw));
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
        TaskRequest request = new TaskRequest(blueprintId, taskParams, priority);
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
