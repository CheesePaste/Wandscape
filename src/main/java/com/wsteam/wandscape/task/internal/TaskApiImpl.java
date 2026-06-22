package com.wsteam.wandscape.task.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import org.slf4j.Logger;

import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.core.task.BlueprintDefinition;
import com.wsteam.wandscape.core.task.BlueprintRegistry;
import com.wsteam.wandscape.core.task.GlobalTaskPool;
import com.wsteam.wandscape.core.system.PlayerManualSource;
import com.wsteam.wandscape.core.task.TaskRequest;
import com.wsteam.wandscape.core.task.TaskState;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.engine.source.blueprint.BlueprintConfigLoader;
import com.wsteam.wandscape.shared.api.TaskApi;
import com.wsteam.wandscape.shared.bridge.TypeBridge;
import com.wsteam.wandscape.shared.data.BlueprintInfo;
import com.wsteam.wandscape.shared.data.ParamTypeInfo;
import com.wsteam.wandscape.shared.data.TaskData;
import com.wsteam.wandscape.shared.data.TaskStatus;
import com.wsteam.wandscape.shared.data.TaskTemplate;
import com.wsteam.wandscape.shared.registry.WandscapeApis;

/**
 * Server-side implementation of {@link TaskApi}.
 *
 * <p>Wires {@link PlayerManualSource} and {@link BlueprintRegistry}
 * into the shared API layer. Registered into {@link WandscapeApis}
 * at server startup.
 *
 * <p>Note: core engine uses {@code long} IDs internally; the shared API
 * uses {@link UUID}. This impl converts at the boundary.
 */
public class TaskApiImpl implements TaskApi {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final PlayerManualSource playerSource;
    private final BlueprintRegistry blueprintRegistry;

    public TaskApiImpl(PlayerManualSource playerSource, BlueprintRegistry blueprintRegistry) {
        this.playerSource = playerSource;
        this.blueprintRegistry = blueprintRegistry;
        WandscapeApis.setTaskApi(this);
    }

    // ── Helpers ──

    @Nullable
    private GlobalTaskPool getPool() {
        var world = WandscapeEngine.getWorld();
        return world != null ? world.taskPool : null;
    }

    private static long toLongId(UUID uuid) {
        return uuid != null ? uuid.getMostSignificantBits() : 0L;
    }

    private static UUID toUuid(long id) {
        return new UUID(id, 0);
    }

    // ── GUI-driven task creation ──

    @Override
    public List<BlueprintInfo> getAvailableBlueprints() {
        BlueprintConfigLoader loader = WandscapeEngine.getBlueprintConfigLoader();
        if (loader == null) {
            LOGGER.warn("[TaskApi] BlueprintConfigLoader not available");
            return List.of();
        }

        List<BlueprintInfo> result = new ArrayList<>();
        for (var entry : loader.getAll().entrySet()) {
            BlueprintDefinition def = entry.getValue();
            Map<String, ParamTypeInfo> paramInfos = new java.util.LinkedHashMap<>();
            if (def.params() != null) {
                for (var paramEntry : def.params().entrySet()) {
                    paramInfos.put(paramEntry.getKey(), ParamTypeInfo.fromCore(paramEntry.getValue()));
                }
            }
            String displayName = def.displayName() != null && !def.displayName().isEmpty()
                    ? def.displayName() : def.id();
            String description = def.description() != null ? def.description() : "";
            result.add(new BlueprintInfo(def.id(), displayName, description, paramInfos));
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public UUID publishTask(String blueprintId, Map<String, JsonElement> params, int priority) {
        GlobalTaskPool pool = getPool();
        if (pool == null) {
            throw new IllegalStateException("Engine not bootstrapped — task pool unavailable");
        }

        if (blueprintId == null || blueprintId.isEmpty()) {
            throw new IllegalArgumentException("blueprintId must not be empty");
        }

        Map<String, JsonElement> safeParams = params != null ? params : Collections.emptyMap();
        long taskId = playerSource.publish(new TaskRequest(blueprintId, safeParams, priority));

        LOGGER.info("[TaskApi] published '{}' → task #{} (priority={}, params={})",
                blueprintId, taskId, priority, safeParams.size());

        return toUuid(taskId);
    }

    // ── Existing API implementations (delegate to GlobalTaskPool) ──

    @Override
    public UUID publishTask(TaskTemplate template, UUID colonyId) {
        throw new UnsupportedOperationException(
                "Legacy TaskTemplate publish not yet wired. Use publishTask(String, Map, int) instead.");
    }

    @Override
    public boolean approveTask(UUID taskId) {
        GlobalTaskPool pool = getPool();
        if (pool == null) return false;
        pool.approve(toLongId(taskId));
        return true;
    }

    @Override
    public boolean cancelTask(UUID taskId) {
        GlobalTaskPool pool = getPool();
        if (pool == null) return false;
        if (pool.get(toLongId(taskId)) == null) return false;
        pool.reject(toLongId(taskId));
        return true;
    }

    @Override
    public boolean suspendTask(UUID taskId) {
        throw new UnsupportedOperationException("Task suspension not yet implemented");
    }

    @Override
    public List<TaskData> getTasksByStatus(UUID colonyId, TaskStatus status) {
        GlobalTaskPool pool = getPool();
        if (pool == null) return List.of();

        TaskState coreStatus = status != null ? TypeBridge.toTaskState(status) : null;
        List<com.wsteam.wandscape.core.task.GlobalTask> coreTasks =
                coreStatus != null ? pool.getByState(coreStatus) : new ArrayList<>(pool.all());

        List<TaskData> result = new ArrayList<>();
        for (com.wsteam.wandscape.core.task.GlobalTask t : coreTasks) {
            result.add(fromGlobalTask(t));
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public TaskData getTask(UUID taskId) {
        GlobalTaskPool pool = getPool();
        if (pool == null) return null;
        com.wsteam.wandscape.core.task.GlobalTask task = pool.get(toLongId(taskId));
        return task != null ? fromGlobalTask(task) : null;
    }

    @Override
    public UUID enqueueBuildingTask(UUID buildingId, TaskTemplate template) {
        throw new UnsupportedOperationException(
                "Building task enqueue not yet wired to TaskApi");
    }

    @Override
    public List<UUID> getBuildingQueue(UUID buildingId) {
        throw new UnsupportedOperationException(
                "Building queue query not yet wired to TaskApi");
    }

    @Override
    public boolean reorderBuildingQueue(UUID buildingId, int fromIndex, int toIndex) {
        throw new UnsupportedOperationException(
                "Building queue reorder not yet wired to TaskApi");
    }

    // ── Internal type bridge ──

    /**
     * Bridges between core task types and shared data types.
     */
    private static TaskData fromGlobalTask(com.wsteam.wandscape.core.task.GlobalTask task) {
        return new TaskData() {
            @Override
            public UUID getTaskId() {
                return toUuid(task.id);
            }

            @Override
            public TaskStatus getStatus() {
                return TypeBridge.toTaskStatus(task.state);
            }

            @Override
            public int getPriority() {
                return task.priority;
            }

            @Override
            public com.wsteam.wandscape.shared.data.BehaviorType getRequiredBehavior() {
                return com.wsteam.wandscape.shared.data.BehaviorType.BUILDING;
            }

            @Override
            public int getRequiredLevel() {
                return 1;
            }

            @Override
            public java.util.List<com.wsteam.wandscape.shared.data.AtomicStep> getSteps() {
                return List.of();
            }

            @Override
            public int getCurrentStepIndex() {
                return task.stepIndex;
            }

            @Override
            public UUID getAssignedNpcId() {
                return task.assignedNpcId != null ? toUuid(task.assignedNpcId) : null;
            }

            @Override
            public UUID getOwnerBuildingId() {
                return null;
            }

            @Override
            public java.util.List<com.wsteam.wandscape.shared.data.InterruptRecord>
            getInterruptHistory() {
                return List.of();
            }
        };
    }
}
