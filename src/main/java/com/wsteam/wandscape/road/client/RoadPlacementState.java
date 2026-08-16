package com.wsteam.wandscape.road.client;

import java.util.List;

import com.wsteam.wandscape.road.data.RoadPreset;
import com.wsteam.wandscape.road.data.RoadPresetLoader;

import net.minecraft.core.BlockPos;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Client-side static state for road placement mode.
 *
 * <p>Entering the ROAD tab opens the ModernUI Road Studio (right panel). Over the 3D world the
 * player left-click-drags to set start/end; the studio panel holds preset selection and the
 * submit button that publishes the road task.
 */
public final class RoadPlacementState {

    private static final String TAG = "RoadPlacementState";

    /** Road placement phase: BAR (preset selection UI) or PLACING (in-world placement). */
    public enum RoadPhase { BAR, PLACING }

    public enum ToolMode { REPLACE, FILL, DESTROY_FILL, SPLINE }

    private static volatile boolean projecting = false;
    private static volatile RoadPhase roadPhase = RoadPhase.BAR;
    private static volatile ToolMode activeTool = ToolMode.REPLACE;
    private static volatile int selectedPresetIndex = 0;
    private static volatile BlockPos startPos = null;
    private static volatile BlockPos endPos = null;
    private static volatile BlockPos ghostPos = null;

    /** The block ID right-clicked as reference in DESTROY_FILL mode. */
    private static volatile String refBlockId = "";

    // ── Double-click tracking (mirrors WandscapePanelState.BUILD pattern) ──
    private static volatile long lastPresetClickTime = 0;
    private static volatile int lastPresetClickIndex = -1;
    private static final long DOUBLE_CLICK_MS = 400;

    private RoadPlacementState() {}

    // ── Mode ──

    public static boolean isProjecting() { return projecting; }

    public static void enterProjection() {
        projecting = true;
        roadPhase = RoadPhase.BAR;
        // Preserve startPos/endPos/ghostPos/activeTool/selectedPresetIndex/refBlockId
        // across suspend/resume within a session. Fields start null so first entry is clean.
        Log.info(TAG, "[RoadPlacement] Entered placement mode (selection preserved)");
    }

    public static void exitProjection() {
        projecting = false;
        roadPhase = RoadPhase.BAR;
        activeTool = ToolMode.REPLACE;
        startPos = null;
        endPos = null;
        ghostPos = null;
        refBlockId = "";
        Log.info(TAG, "[RoadPlacement] Exited placement mode");
    }

    /**
     * Suspend road placement without clearing the selection (positions, tool, preset,
     * ref block). Used when temporarily leaving ROAD so in-progress placement survives
     * re-entry. Full clear is {@link #exitProjection()}, called only on disconnect.
     */
    public static void suspendProjection() {
        projecting = false;
        Log.info(TAG, "[RoadPlacement] Suspended placement mode (selection preserved)");
    }

    // ── Phase ──

    public static RoadPhase getRoadPhase() { return roadPhase; }

    /** Enter BAR phase (preset selection overlay). Preserves in-progress positions/tool. */
    public static void enterBar() {
        roadPhase = RoadPhase.BAR;
        Log.info(TAG, "[RoadPlacement] Entered BAR phase (positions preserved)");
    }

    /** Enter PLACING phase (in-world start/end selection). Preserves in-progress positions/tool. */
    public static void enterPlacing() {
        roadPhase = RoadPhase.PLACING;
        Log.info(TAG, "[RoadPlacement] Entered PLACING phase (positions preserved)");
    }

    // ── Tool mode ──

    public static ToolMode getActiveTool() { return activeTool; }

    public static void setActiveTool(ToolMode mode) {
        activeTool = mode;
        refBlockId = "";
        Log.info(TAG, "[RoadPlacement] Tool mode → {}", mode);
    }

    public static boolean isReplace() { return activeTool == ToolMode.REPLACE; }

    public static boolean isFill() { return activeTool == ToolMode.FILL; }

    public static boolean isDestroyFill() { return activeTool == ToolMode.DESTROY_FILL; }

    public static boolean isSpline() { return activeTool == ToolMode.SPLINE; }

    public static String getRefBlockId() { return refBlockId; }

    public static void setRefBlockId(String id) { refBlockId = id != null ? id : ""; }

    /**
     * Single-click → select. Double-click (same index within 400ms) → return true,
     * indicating caller should enter PLACING phase.
     */
    public static boolean handlePresetDoubleClick(int index) {
        long now = System.currentTimeMillis();
        if (index == lastPresetClickIndex && (now - lastPresetClickTime) < DOUBLE_CLICK_MS) {
            lastPresetClickTime = 0;
            lastPresetClickIndex = -1;
            return true;
        }
        lastPresetClickTime = now;
        lastPresetClickIndex = index;
        selectedPresetIndex = index;
        return false;
    }

    // ── Preset ──

    public static int getSelectedPresetIndex() { return selectedPresetIndex; }
    public static void setSelectedPresetIndex(int idx) {
        if (idx >= 0 && idx < RoadPresetLoader.getInstance().getAll().size()) {
            selectedPresetIndex = idx;
            SplineEditorClientState.rebuildDynamicTemplate();
        }
    }

    public static RoadPreset getSelectedPreset() {
        List<RoadPreset> all = RoadPresetLoader.getInstance().getAll();
        if (selectedPresetIndex < 0 || selectedPresetIndex >= all.size()) {
            return RoadPreset.DEFAULT_PRESETS.get(0);
        }
        return all.get(selectedPresetIndex);
    }
    public static List<RoadPreset> getPresets() {
        return RoadPresetLoader.getInstance().getAll();
    }

    // ── Positions ──

    public static boolean isPlanning() { return startPos != null; }
    public static boolean hasEnd() { return endPos != null; }
    public static boolean isReady() { return startPos != null && endPos != null; }

    public static BlockPos getStartPos() { return startPos; }
    public static void setStartPos(BlockPos pos) { startPos = pos; }
    public static void clearStartPos() { startPos = null; }

    public static BlockPos getEndPos() { return endPos; }
    public static void setEndPos(BlockPos pos) { endPos = pos; }
    public static void clearEndPos() { endPos = null; }

    public static void clearAll() {
        startPos = null;
        endPos = null;
    }

    // ── Ghost ──

    public static BlockPos getGhostPos() { return ghostPos; }
    public static void setGhostPos(BlockPos pos) { ghostPos = pos; }
}
