package com.wsteam.wandscape.building.be;

import java.util.*;

import javax.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.building.data.BlockOffset;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.shared.data.WorkItem;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Common base for all Wandscape building block entities.
 * Provides: FIFO queue, colonyId cache, shutdown state,
 * structure integrity tracking, and NBT persistence.
 *
 * <p>Subclasses override {@link #getBuildingTypeId()} to
 * identify the JSON building type.
 */
public abstract class AbstractWandscapeBE extends BlockEntity {
    protected static final Logger LOGGER = LogUtils.getLogger();

    // ---- NBT key constants ----
    private static final String TAG_COLONY_ID = "colony_id";
    private static final String TAG_SHUTDOWN = "shutdown";
    private static final String TAG_STRUCTURE_INTACT = "structure_intact";
    private static final String TAG_TASK_QUEUE = "task_queue";
    private static final String TAG_CURRENT_TASK = "current_task";
    private static final String TAG_QUEUE_ITEM_BLUEPRINT = "blueprint";
    private static final String TAG_QUEUE_ITEM_PARAMS = "params_json";
    private static final String TAG_QUEUE_ITEM_PRIORITY = "priority";

    /**
     * Shared Gson instance for NBT param serialization.
     * Per decision #18: params stored as {@code gson.toJson(params)} flat JSON string.
     */
    private static final Gson PARAMS_GSON = new Gson();
    private static final java.lang.reflect.Type PARAMS_TYPE =
            new TypeToken<Map<String, JsonElement>>(){}.getType();

    // ---- State ----
    @Nullable
    protected UUID colonyId;
    protected boolean isShutdown;
    protected boolean isStructureIntact = true;
    protected final Deque<WorkItem> taskQueue = new ArrayDeque<>();
    @Nullable
    protected UUID currentTaskId;

    protected AbstractWandscapeBE(BlockEntityType<?> type, BlockPos pos, BlockState blockState) {
        super(type, pos, blockState);
    }

    // ---- Subclass contract ----

    /** The building type id matching the JSON config. */
    protected abstract String getBuildingTypeId();

    // ---- Colony ID ----

    /**
     * Returns the cached colony ID. Subclasses or external code
     * should call {@link #setColonyId(UUID)} once the colony is known.
     */
    @Nullable
    public UUID getColonyId() {
        return colonyId;
    }

    public void setColonyId(UUID colonyId) {
        this.colonyId = colonyId;
        setChanged();
    }

    // ---- Shutdown ----

    public boolean isShutdown() {
        return isShutdown;
    }

    public void setShutdown(boolean shutdown) {
        if (this.isShutdown != shutdown) {
            this.isShutdown = shutdown;
            setChanged();
        }
    }

    // ---- Structure integrity ----

    public boolean isStructureIntact() {
        return isStructureIntact;
    }

    /**
     * Walk the building pattern and check each offset against the expected block.
     * Called from block break/explosion event handlers.
     *
     * @param config the building config (cached externally or passed in)
     * @return true if all pattern blocks match their expected state
     */
    public boolean checkStructureIntegrity(BuildingConfig config) {
        if (level == null) return isStructureIntact;

        boolean intact = true;
        for (BlockOffset offset : config.pattern()) {
            BlockPos target = worldPosition.offset(offset.x(), offset.y(), offset.z());
            String expectedKey = offset.toKey();
            String expectedId = config.blockMapping().get(expectedKey);
            if (expectedId == null) continue; // Not required

            BlockState actual = level.getBlockState(target);
            // Compare by block ID string — loose match (ignores blockstate properties for now)
            String actualId = actual.getBlock().builtInRegistryHolder().key().location().toString();
            if (!actualId.equals(expectedId)) {
                intact = false;
                break;
            }
        }

        if (intact != isStructureIntact) {
            isStructureIntact = intact;
            setChanged();
        }
        return isStructureIntact;
    }

    public void setStructureIntact(boolean intact) {
        this.isStructureIntact = intact;
        setChanged();
    }

    // ---- Operational check ----

    /** Building is fully functional: not shut down and structure is intact. */
    public boolean isOperational() {
        return !isShutdown && isStructureIntact;
    }

    // ---- Task queue ----

    /** Whether the building has queued work and is ready to publish. */
    public boolean hasWork() {
        // Only check shutdown — structure damage should NOT block repair work.
        // (Repair tasks use the same queue; blocking them creates a deadlock.)
        return !taskQueue.isEmpty() && !isShutdown;
    }

    /** Peek at the next work item without removing it. */
    @Nullable
    public WorkItem peekWork() {
        return taskQueue.peek();
    }

    /** Enqueue a work item at the end of the FIFO queue. */
    public void enqueueWork(WorkItem work) {
        taskQueue.addLast(work);
        setChanged();
    }

    /**
     * Dequeue the next work item. Returns null if the queue is empty
     * or the building is shut down.
     */
    @Nullable
    public WorkItem dequeueWork() {
        // Only check shutdown — structure damage should NOT block repair work.
        if (isShutdown) return null;
        WorkItem item = taskQueue.pollFirst();
        if (item != null) setChanged();
        return item;
    }

    /** Number of items currently queued. */
    public int queueSize() {
        return taskQueue.size();
    }

    /** All queued items (read-only). */
    public List<WorkItem> getQueuedWork() {
        return List.copyOf(taskQueue);
    }

    // ---- Current task tracking ----

    @Nullable
    public UUID getCurrentTaskId() {
        return currentTaskId;
    }

    public void setCurrentTaskId(@Nullable UUID taskId) {
        this.currentTaskId = taskId;
        setChanged();
    }

    // ---- NBT persistence ----

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.hasUUID(TAG_COLONY_ID)) {
            colonyId = tag.getUUID(TAG_COLONY_ID);
        }
        isShutdown = tag.getBoolean(TAG_SHUTDOWN);
        isStructureIntact = tag.getBoolean(TAG_STRUCTURE_INTACT);
        if (tag.hasUUID(TAG_CURRENT_TASK)) {
            currentTaskId = tag.getUUID(TAG_CURRENT_TASK);
        } else {
            currentTaskId = null;
        }

        // Deserialize queue
        taskQueue.clear();
        ListTag queueTag = tag.getList(TAG_TASK_QUEUE, Tag.TAG_COMPOUND);
        for (int i = 0; i < queueTag.size(); i++) {
            CompoundTag itemTag = queueTag.getCompound(i);
            String blueprint = itemTag.getString(TAG_QUEUE_ITEM_BLUEPRINT);
            int priority = itemTag.getInt(TAG_QUEUE_ITEM_PRIORITY);

            Map<String, JsonElement> params = Collections.emptyMap();
            if (itemTag.contains(TAG_QUEUE_ITEM_PARAMS)) {
                String json = itemTag.getString(TAG_QUEUE_ITEM_PARAMS);
                params = PARAMS_GSON.fromJson(json, PARAMS_TYPE);
                if (params == null) params = Collections.emptyMap();
            }
            taskQueue.addLast(new WorkItem(blueprint, params, priority));
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (colonyId != null) {
            tag.putUUID(TAG_COLONY_ID, colonyId);
        }
        tag.putBoolean(TAG_SHUTDOWN, isShutdown);
        tag.putBoolean(TAG_STRUCTURE_INTACT, isStructureIntact);
        if (currentTaskId != null) {
            tag.putUUID(TAG_CURRENT_TASK, currentTaskId);
        }

        // Serialize queue
        ListTag queueTag = new ListTag();
        for (WorkItem item : taskQueue) {
            CompoundTag itemTag = new CompoundTag();
            itemTag.putString(TAG_QUEUE_ITEM_BLUEPRINT, item.blueprintId());
            itemTag.putInt(TAG_QUEUE_ITEM_PRIORITY, item.priority());

            // Per decision #18: store params as gson.toJson(params) flat JSON string
            String paramsJson = PARAMS_GSON.toJson(item.params());
            itemTag.putString(TAG_QUEUE_ITEM_PARAMS, paramsJson);
            queueTag.add(itemTag);
        }
        tag.put(TAG_TASK_QUEUE, queueTag);
    }
}
