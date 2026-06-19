package com.wsteam.wandscape.shared.event;

import java.util.UUID;

import net.neoforged.bus.api.Event;

public class TaskInterruptedEvent extends Event {
    private final UUID taskId;
    private final UUID npcId;
    private final String reason;

    public TaskInterruptedEvent(UUID taskId, UUID npcId, String reason) {
        this.taskId = taskId;
        this.npcId = npcId;
        this.reason = reason;
    }

    public UUID getTaskId() { return taskId; }
    public UUID getNpcId() { return npcId; }
    public String getReason() { return reason; }
}
