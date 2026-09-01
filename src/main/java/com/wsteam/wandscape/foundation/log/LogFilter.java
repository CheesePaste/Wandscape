package com.wsteam.wandscape.foundation.log;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Runtime log tag whitelist.
 * <p>
 * When enabled, only log messages whose subTag/tag is in the whitelist pass through.
 * When disabled, all subTags pass through.
 */
public final class LogFilter {

    private static volatile boolean enabled = false;
    private static final Set<String> whitelist = new CopyOnWriteArraySet<>();

    private LogFilter() {}

    public static boolean isEnabled() { return enabled; }

    public static void setEnabled(boolean e) { enabled = e; }

    public static boolean allows(String tag) {
        if (tag == null || !enabled) return true;
        return whitelist.contains(tag);
    }

    public static Set<String> getWhitelist() {
        return Collections.unmodifiableSet(whitelist);
    }

    public static void add(String tag) {
        if (tag != null && !tag.isBlank()) {
            whitelist.add(tag.trim());
        }
    }

    public static void remove(String tag) {
        if (tag != null) {
            whitelist.remove(tag.trim());
        }
    }

    public static void clear() { whitelist.clear(); }

    public static void setWhitelist(Set<String> tags) {
        whitelist.clear();
        if (tags != null) {
            whitelist.addAll(tags);
        }
    }

    public static int size() { return whitelist.size(); }

    public static void presetPreviewDebug() {
        enabled = true;
        whitelist.clear();
        // Activate DEBUG level on relevant categories
        LogConfig.setLevel(LogCategory.BUILDING, LogLevel.DEBUG);
        LogConfig.setLevel(LogCategory.TASK, LogLevel.DEBUG);
        LogConfig.setLevel(LogCategory.ROAD, LogLevel.DEBUG);
        LogConfig.setLevel(LogCategory.UI, LogLevel.DEBUG);

        // Subtags used by preview and building overlays
        whitelist.add("Scheduler");
        whitelist.add("SchedulerSystem");
        whitelist.add("Preview");
        whitelist.add("BuildingPreviewRenderer");
        whitelist.add("BuildingSelectionOverlay");
        whitelist.add("Projection");
        whitelist.add("ProjectionRenderer");
        whitelist.add("ProjectionFlightController");
        whitelist.add("RoadProjection");
        whitelist.add("RoadProjectionRenderer");
        whitelist.add("RoadProjectionController");
        whitelist.add("Debug");
        whitelist.add("BuildingDebugController");
        whitelist.add("Panel");
        whitelist.add("WandscapePanelController");
        whitelist.add("WandscapePanelOverlay");
        whitelist.add("Building");
        whitelist.add("BuildingInteractHandler");
    }
}
