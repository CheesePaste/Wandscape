package com.wsteam.wandscape.shared.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonElement;
import com.wsteam.wandscape.shared.data.BlueprintInfo;
import com.wsteam.wandscape.shared.data.TaskData;
import com.wsteam.wandscape.shared.data.TaskStatus;
import com.wsteam.wandscape.shared.data.TaskTemplate;

public interface TaskApi {
    UUID publishTask(TaskTemplate template, UUID colonyId);
    boolean approveTask(UUID taskId);
    boolean cancelTask(UUID taskId);
    boolean suspendTask(UUID taskId);
    List<TaskData> getTasksByStatus(UUID colonyId, TaskStatus status);
    TaskData getTask(UUID taskId);
    UUID enqueueBuildingTask(UUID buildingId, TaskTemplate template);
    List<UUID> getBuildingQueue(UUID buildingId);
    boolean reorderBuildingQueue(UUID buildingId, int fromIndex, int toIndex);

    // ---- GUI-driven task creation ----

    /** Get all available blueprints for the task editor GUI. */
    List<BlueprintInfo> getAvailableBlueprints();

    /**
     * Publish a task from a blueprint id + raw params.
     * Used by the task editor GUI (client → server via network packet).
     *
     * @return the created task id
     */
    UUID publishTask(String blueprintId, Map<String, JsonElement> params, int priority);
}
