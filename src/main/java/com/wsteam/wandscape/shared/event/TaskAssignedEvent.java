package com.wsteam.wandscape.shared.event;

import java.util.UUID;

import net.neoforged.bus.api.Event;

public class TaskAssignedEvent extends Event {
    private final UUID taskId;
    private final UUID npcId;

    public TaskAssignedEvent(UUID taskId, UUID npcId) {
        this.taskId = taskId;
        this.npcId = npcId;
    }

    public UUID getTaskId() { return taskId; }
    public UUID getNpcId() { return npcId; }
}
