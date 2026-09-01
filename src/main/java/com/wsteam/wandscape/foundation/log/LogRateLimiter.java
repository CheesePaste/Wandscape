package com.wsteam.wandscape.foundation.log;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe rate limiter and deduplicator for log messages.
 * Prevents log spamming in high-frequency loops (e.g. tick routines, pathfinding).
 */
public final class LogRateLimiter {

    private static final Map<String, Long> LAST_LOGGED = new ConcurrentHashMap<>();
    private static final Set<String> ONCE_LOGGED = ConcurrentHashMap.newKeySet();
    private static final int MAX_ENTRIES = 2048;

    private LogRateLimiter() {}

    /**
     * Checks if a log with the given key should be permitted under the time window.
     *
     * @param key        unique throttle identifier
     * @param intervalMs minimum duration in milliseconds between logged messages
     * @return true if permitted, false if suppressed
     */
    public static boolean shouldLog(String key, long intervalMs) {
        if (key == null || intervalMs <= 0) return true;
        long now = System.currentTimeMillis();
        Long prev = LAST_LOGGED.get(key);
        if (prev != null && (now - prev) < intervalMs) {
            return false;
        }

        if (LAST_LOGGED.size() > MAX_ENTRIES) {
            // Prune entries older than 60 seconds to avoid unbounded memory growth
            LAST_LOGGED.entrySet().removeIf(e -> (now - e.getValue()) > 60000L);
        }

        LAST_LOGGED.put(key, now);
        return true;
    }

    /**
     * Checks if a log with the given key should be permitted only once in the entire application run.
     *
     * @param key unique identifier
     * @return true if this is the first time the key is seen, false otherwise
     */
    public static boolean shouldLogOnce(String key) {
        if (key == null) return true;
        return ONCE_LOGGED.add(key);
    }

    /**
     * Clears all throttled entries.
     */
    public static void clear() {
        LAST_LOGGED.clear();
        ONCE_LOGGED.clear();
    }
}
