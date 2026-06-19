package com.wsteam.wandscape.shared.event;

import java.util.UUID;

import com.wsteam.wandscape.shared.data.ElementType;

import net.neoforged.bus.api.Event;

public class TaskAwaitingMaterialsEvent extends Event {
    private final UUID taskId;
    private final ElementType missingElement;
    private final long required;
    private final long available;

    public TaskAwaitingMaterialsEvent(UUID taskId, ElementType missingElement, long required, long available) {
        this.taskId = taskId;
        this.missingElement = missingElement;
        this.required = required;
        this.available = available;
    }

    public UUID getTaskId() { return taskId; }
    public ElementType getMissingElement() { return missingElement; }
    public long getRequired() { return required; }
    public long getAvailable() { return available; }
}
