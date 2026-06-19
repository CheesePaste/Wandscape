package com.wsteam.wandscape.shared.event;

import java.util.UUID;

import net.neoforged.bus.api.Event;

public class TaskPublishedEvent extends Event {
    private final UUID taskId;
    private final UUID colonyId;

    public TaskPublishedEvent(UUID taskId, UUID colonyId) {
        this.taskId = taskId;
        this.colonyId = colonyId;
    }

    public UUID getTaskId() { return taskId; }
    public UUID getColonyId() { return colonyId; }
}
