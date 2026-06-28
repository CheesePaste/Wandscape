package com.wsteam.wandscape.shared.event;

import java.util.UUID;

import net.neoforged.bus.api.Event;
public class TaskCompletedEvent extends Event {
    private final UUID taskId;
    private final UUID npcId;

    public TaskCompletedEvent(UUID taskId, UUID npcId) {
        this.taskId = taskId;
        this.npcId = npcId;
    }

    public UUID getTaskId() { return taskId; }
    public UUID getNpcId() { return npcId; }
}
