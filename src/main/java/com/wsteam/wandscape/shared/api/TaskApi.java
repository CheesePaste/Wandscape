package com.wsteam.wandscape.shared.api;

import java.util.List;
import java.util.UUID;

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
}
