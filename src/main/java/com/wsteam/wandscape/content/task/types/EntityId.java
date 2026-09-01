package com.wsteam.wandscape.content.task.types;

/**
 * Strongly-typed wrapper around a long entity ID.
 * Prevents accidental mixing with other long IDs.
 */
public record EntityId(long value) {

    public static final EntityId NONE = new EntityId(-1);

    public boolean isNone() {
        return value == -1;
    }

    @Override
    public String toString() {
        return "EntityId(" + value + ")";
    }
}
