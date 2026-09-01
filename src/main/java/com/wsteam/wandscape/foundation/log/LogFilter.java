package com.wsteam.wandscape.foundation.log;
import com.wsteam.wandscape.content.building.ui.BuildingSelectionOverlay;
import com.wsteam.wandscape.foundation.ui.panel.WandscapePanelController;
import com.wsteam.wandscape.foundation.ui.panel.WandscapePanelOverlay;
import com.wsteam.wandscape.content.building.preview.BuildingPreviewRenderer;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
/**
 * Runtime log tag whitelist. Lives in shared/log so all modules can reach it.
 *
 * <p>When enabled, only log messages whose tag is in the whitelist pass through.
 * When disabled, all messages pass.
 */
public final class LogFilter {

    private static volatile boolean enabled = false;
    private static final Set<String> whitelist = new CopyOnWriteArraySet<>();

    private LogFilter() {}

    public static boolean isEnabled() { return enabled; }

    public static void setEnabled(boolean e) { enabled = e; }

    public static boolean allows(String tag) {
        return !enabled || whitelist.contains(tag);
    }

    public static Set<String> getWhitelist() {
        return Collections.unmodifiableSet(whitelist);
    }

    public static void add(String tag) { whitelist.add(tag); }

    public static void remove(String tag) { whitelist.remove(tag); }

    public static void clear() { whitelist.clear(); }

    public static void setWhitelist(Set<String> tags) {
        whitelist.clear();
        whitelist.addAll(tags);
    }

    public static int size() { return whitelist.size(); }

    public static void presetPreviewDebug() {
        enabled = true;
        whitelist.clear();
        // tags used by Log class (class simple names)
        whitelist.add("Scheduler");
        whitelist.add("SchedulerSystem");
        // Preview system
        whitelist.add("Preview");
        whitelist.add("BuildingPreviewRenderer");
        whitelist.add("BuildingSelectionOverlay");
        // Build editor (removed)
        // Projection / placement
        whitelist.add("Projection");
        whitelist.add("ProjectionRenderer");
        whitelist.add("ProjectionFlightController");
        // Road projection
        whitelist.add("RoadProjection");
        whitelist.add("RoadProjectionRenderer");
        whitelist.add("RoadProjectionController");
        // Debug commands / systems
        whitelist.add("Debug");
        whitelist.add("BuildingDebugController");
        // Panel UI
        whitelist.add("Panel");
        whitelist.add("WandscapePanelController");
        whitelist.add("WandscapePanelOverlay");
        // Building interaction
        whitelist.add("Building");
        whitelist.add("BuildingInteractHandler");
    }
}
