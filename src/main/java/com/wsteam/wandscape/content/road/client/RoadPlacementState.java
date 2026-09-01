package com.wsteam.wandscape.content.road.client;
import com.wsteam.wandscape.foundation.ui.panel.WandscapePanelState;

import com.wsteam.wandscape.content.road.data.RoadPreset;
import com.wsteam.wandscape.content.road.data.RoadPresetLoader;
import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side static state for road placement mode.
 *
 * <p>Entering the ROAD tab opens the ImGui Road Studio (right panel). Over the 3D world the
 * player left-click-drags to set start/end; the studio panel holds preset selection and the
 * submit button that publishes the road task.
 */
public final class RoadPlacementState {

    private static final String TAG = "RoadPlacementState";

    /** Road placement phase: BAR (preset selection UI) or PLACING (in-world placement). */
    public enum RoadPhase { BAR, PLACING }

    public enum ToolMode { REPLACE, FILL, DESTROY_FILL, SPLINE }

    public enum PaletteSourceMode { PRESET, PROCEDURAL }

    public static class ProceduralEntry {
        public String blockId;
        public int weight;

        public ProceduralEntry(String blockId, int weight) {
            this.blockId = blockId;
            this.weight = Math.max(1, weight);
        }
    }

    public enum GizmoTarget { NONE, START, END }

    public enum AxisDrag { NONE, X_POS, X_NEG, Y_POS, Y_NEG, Z_POS, Z_NEG }

    private static volatile boolean projecting = false;
    private static volatile RoadPhase roadPhase = RoadPhase.BAR;
    private static volatile ToolMode activeTool = ToolMode.REPLACE;
    private static volatile PaletteSourceMode paletteMode = PaletteSourceMode.PRESET;

    private static final List<ProceduralEntry> proceduralEntries = new ArrayList<>(List.of(
            new ProceduralEntry("minecraft:stone_bricks", 5),
            new ProceduralEntry("minecraft:mossy_stone_bricks", 2),
            new ProceduralEntry("minecraft:cracked_stone_bricks", 1)
    ));

    private static volatile int selectedPresetIndex = 0;
    private static volatile BlockPos startPos = null;
    private static volatile BlockPos endPos = null;
    private static volatile BlockPos ghostPos = null;

    private static volatile GizmoTarget hoveredTarget = GizmoTarget.NONE;
    private static volatile AxisDrag hoveredAxis = AxisDrag.NONE;
    private static volatile GizmoTarget draggingTarget = GizmoTarget.NONE;
    private static volatile AxisDrag draggingAxis = AxisDrag.NONE;

    /** The block ID right-clicked as reference in DESTROY_FILL mode. */
    private static volatile String refBlockId = "";

    /** Whether DESTROY_FILL mode should pad/fill depressions below the reference height. Default: false (pure flatten). */
    private static volatile boolean fillDepressions = false;

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
        paletteMode = PaletteSourceMode.PRESET;
        startPos = null;
        endPos = null;
        ghostPos = null;
        refBlockId = "";
        fillDepressions = false;
        resetGizmoState();
        Log.info(TAG, "[RoadPlacement] Exited placement mode");
    }

    public static void resetGizmoState() {
        hoveredTarget = GizmoTarget.NONE;
        hoveredAxis = AxisDrag.NONE;
        draggingTarget = GizmoTarget.NONE;
        draggingAxis = AxisDrag.NONE;
    }

    /**
     * Suspend road placement without clearing the selection (positions, tool, preset,
     * ref block). Used when temporarily leaving ROAD so in-progress placement survives
     * re-entry. Full clear is {@link #exitProjection()}, called only on disconnect.
     */
    public static void suspendProjection() {
        projecting = false;
        resetGizmoState();
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

    // ── Palette Mode & Procedural Blend ──

    public static PaletteSourceMode getPaletteMode() { return paletteMode; }

    public static void setPaletteMode(PaletteSourceMode mode) {
        paletteMode = mode;
        Log.info(TAG, "[RoadPlacement] Palette mode → {}", mode);
    }

    public static boolean isProcedural() { return paletteMode == PaletteSourceMode.PROCEDURAL; }

    public static List<ProceduralEntry> getProceduralEntries() { return proceduralEntries; }

    public static void addProceduralEntry(String blockId, int weight) {
        if (blockId == null || blockId.isEmpty()) return;
        for (var e : proceduralEntries) {
            if (e.blockId.equals(blockId)) {
                e.weight = Math.min(10, e.weight + Math.max(1, weight));
                return;
            }
        }
        proceduralEntries.add(new ProceduralEntry(blockId, weight));
    }

    public static void removeProceduralEntry(int index) {
        if (index >= 0 && index < proceduralEntries.size() && proceduralEntries.size() > 1) {
            proceduralEntries.remove(index);
        }
    }

    public static void setProceduralWeight(int index, int weight) {
        if (index >= 0 && index < proceduralEntries.size()) {
            proceduralEntries.get(index).weight = Math.max(1, Math.min(10, weight));
        }
    }

    public static void resetProceduralEntries() {
        proceduralEntries.clear();
        proceduralEntries.add(new ProceduralEntry("minecraft:stone_bricks", 5));
        proceduralEntries.add(new ProceduralEntry("minecraft:mossy_stone_bricks", 2));
        proceduralEntries.add(new ProceduralEntry("minecraft:cracked_stone_bricks", 1));
    }

    public static String getProceduralPresetId() {
        if (proceduralEntries.isEmpty()) return "minecraft:stone";
        StringBuilder sb = new StringBuilder("custom:");
        for (int i = 0; i < proceduralEntries.size(); i++) {
            if (i > 0) sb.append(";");
            var e = proceduralEntries.get(i);
            sb.append(e.blockId).append("*").append(e.weight);
        }
        return sb.toString();
    }

    public static RoadPreset getActivePreset() {
        if (paletteMode == PaletteSourceMode.PRESET) {
            return getSelectedPreset();
        }
        List<RoadPreset.WeightedEntry> entries = new ArrayList<>();
        for (var e : proceduralEntries) {
            entries.add(new RoadPreset.WeightedEntry(e.blockId, e.weight));
        }
        return new RoadPreset(getProceduralPresetId(), "程序化混合", List.copyOf(entries));
    }

    public static String getActivePresetId() {
        if (paletteMode == PaletteSourceMode.PRESET) {
            return getSelectedPreset().id();
        }
        return getProceduralPresetId();
    }

    public static boolean isReplace() { return activeTool == ToolMode.REPLACE; }

    public static boolean isFill() { return activeTool == ToolMode.FILL; }

    public static boolean isDestroyFill() { return activeTool == ToolMode.DESTROY_FILL; }

    public static boolean isSpline() { return activeTool == ToolMode.SPLINE; }

    public static String getRefBlockId() { return refBlockId; }

    public static void setRefBlockId(String id) { refBlockId = id != null ? id : ""; }

    public static boolean isFillDepressions() { return fillDepressions; }

    public static void setFillDepressions(boolean fill) { fillDepressions = fill; }

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
        resetGizmoState();
    }

    // ── Gizmo ──

    public static GizmoTarget getHoveredTarget() { return hoveredTarget; }
    public static void setHoveredTarget(GizmoTarget target) { hoveredTarget = target; }

    public static AxisDrag getHoveredAxis() { return hoveredAxis; }
    public static void setHoveredAxis(AxisDrag axis) { hoveredAxis = axis; }

    public static GizmoTarget getDraggingTarget() { return draggingTarget; }
    public static void setDraggingTarget(GizmoTarget target) { draggingTarget = target; }

    public static AxisDrag getDraggingAxis() { return draggingAxis; }
    public static void setDraggingAxis(AxisDrag axis) { draggingAxis = axis; }

    public static boolean isDraggingGizmo() { return draggingTarget != GizmoTarget.NONE && draggingAxis != AxisDrag.NONE; }

    // ── Ghost ──

    public static BlockPos getGhostPos() { return ghostPos; }
    public static void setGhostPos(BlockPos pos) { ghostPos = pos; }
}
