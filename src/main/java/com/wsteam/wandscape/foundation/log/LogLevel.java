package com.wsteam.wandscape.foundation.log;

/**
 * Log levels for Wandscape with explicit priority ordering.
 * <p>
 * Priority: DEBUG (1) &lt; INFO (2) &lt; WARN (3) &lt; ERROR (4) &lt; OFF (5).
 */
public enum LogLevel {
    DEBUG(1),
    INFO(2),
    WARN(3),
    ERROR(4),
    OFF(5);

    private final int priority;

    LogLevel(int priority) {
        this.priority = priority;
    }

    public int getPriority() {
        return priority;
    }

    /**
     * Checks if this log level includes the target level.
     * E.g. if this configured level is INFO (2), a WARN (3) log is included, but a DEBUG (1) is not.
     */
    public boolean includes(LogLevel target) {
        return target.priority >= this.priority;
    }

    public static LogLevel fromString(String str, LogLevel fallback) {
        if (str == null || str.isBlank()) return fallback;
        try {
            return LogLevel.valueOf(str.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
