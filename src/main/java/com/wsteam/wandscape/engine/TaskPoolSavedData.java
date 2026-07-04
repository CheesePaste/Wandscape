package com.wsteam.wandscape.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

        // taskParams: store each JsonElement value as a string
        if (!task.taskParams.isEmpty()) {
            CompoundTag params = new CompoundTag();
            for (var entry : task.taskParams.entrySet()) {
                params.putString(entry.getKey(), entry.getValue().toString());
            }
            tag.put("params", params);
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
            GlobalTask task = taskFromNbt(t, pool);
            if (task != null) {
                long originalId = t.getLong("id");
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
    private static GlobalTask taskFromNbt(CompoundTag tag, GlobalTaskPool pool) {
        String blueprintId = tag.getString("bp");
        if (blueprintId.isEmpty()) return null;

        // Reconstruct taskParams
        Map<String, JsonElement> taskParams = new HashMap<>();
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

        // Recompile the blueprint to get TaskSequence, requirements, triggers
        TaskRequest request = new TaskRequest(blueprintId, taskParams, priority);
        try {
            // Use raw addTask to compile, then adjust state/stepIndex
            long newId = pool.addTask(request);
            GlobalTask task = pool.get(newId);
            if (task != null) {
                task.state = state;
                task.stepIndex = stepIndex;

                // Restore awaitingResource (new format: ListTag; old format: CompoundTag)
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
                    } else if (awaitTag instanceof CompoundTag resTag) {
                        // Backward-compat: old single-resource format
                        String resId = resTag.getString("id");
                        int amt = resTag.getInt("amt");
                        if (!resId.isEmpty() && amt > 0) {
                            awaitList.add(new ResourceStack(new ResourceId(resId), amt));
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
}
