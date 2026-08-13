package com.wsteam.wandscape.projection.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.engine.service.SoundService;
import com.wsteam.wandscape.engine.sound.WandscapeSounds;
import com.wsteam.wandscape.projection.BuildingCentering;
import com.wsteam.wandscape.projection.data.BuildingSlot;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Client-side static state holder for soul projection mode.
 * Thread-safe via volatile fields + synchronized collections.
 * Pattern mirrors {@code RoadEditorClientState}.
 */
public final class ProjectionClientState {

    private static final String TAG = "ProjectionClientState";

    /** Whether the player is currently in projection mode. */
    private static volatile boolean projecting = false;

    /** World position where the player's body is anchored (used for beam rendering). */
    private static volatile BlockPos bodyAnchor = null;

    /** Index into {@link #buildingSlots} of the currently selected building. */
    private static volatile int selectedSlotIndex = 0;

    /** Current ghost preview position (null = no valid target under crosshair). */
    private static volatile BlockPos ghostPos = null;

    /** Whether the current ghost position overlaps an existing building. */
    private static volatile boolean overlapDetected = false;

    /** Whether the ghost preview is pinned to a fixed position (no longer follows the crosshair). */
    private static volatile boolean pinned = false;

    /** Available building slots received from server. */
    private static final List<BuildingSlot> buildingSlots =
            Collections.synchronizedList(new ArrayList<>());

    /** Number of 90° counter-clockwise rotations (0-3). 0 = original orientation. */
    private static volatile int rotationSteps = 0;

    private ProjectionClientState() {}

    // ── Projection mode ──

    public static boolean isProjecting() {
        return projecting;
    }

    /**
     * Clamp a slot index into {@code [0, size)}. Out-of-range (negative, or {@code >= size},
     * including any index when {@code size == 0}) folds back to 0. Package-private so the
     * selection-preservation logic can be unit-tested in isolation.
     */
    static int clampSlotIndex(int current, int size) {
        if (current < 0 || current >= size) return 0;
        return current;
    }

    /**
     * Enter projection mode. Called from {@code ProjectionEnterResponsePacket} client handler.
     * Saves current player state, enablestransl flight, stores building slots.
     */
    public static void enterProjection(BlockPos anchor, List<BuildingSlot> slots) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        bodyAnchor = anchor;

        // Server re-sends slots on every enter; merge, don't discard the selection.
        synchronized (buildingSlots) {
            buildingSlots.clear();
            buildingSlots.addAll(slots);
        }
        // Preserve selection across suspend/resume within a session: only clamp the
        // slot index into the (possibly changed) list; keep rotation and pin.
        selectedSlotIndex = clampSlotIndex(selectedSlotIndex, slots.size());
        // Drop a stale crosshair-follow position, but keep a pinned placement.
        if (!pinned) {
            ghostPos = null;
        }
        overlapDetected = false;

        projecting = true;

        SoundService.playUI(WandscapeSounds.PROJECTION_ENTER, 1.0f);

        Log.info(TAG, "[Projection] Entered placement mode. Body at {}, {} buildings (selection preserved)",
                anchor, slots.size());
    }

    /**
     * Exit projection mode. Called from client handler or flight controller.
     * Clears state without teleporting.
     */
    public static void exitProjection() {
        projecting = false;

        SoundService.playUI(WandscapeSounds.PROJECTION_EXIT, 1.0f);

        // Clear state
        bodyAnchor = null;
        selectedSlotIndex = 0;
        ghostPos = null;
        overlapDetected = false;
        rotationSteps = 0;
        pinned = false;
        synchronized (buildingSlots) {
            buildingSlots.clear();
        }

        Log.info(TAG, "[Projection] Exited projection mode");
    }

    /**
     * Suspend projection mode without clearing the selection. Used when temporarily
     * leaving BUILD (tab switch / G / ESC / panel close) so the player's chosen
     * building, rotation, pinned position and slot list survive re-entry within the
     * same session. Full clear is {@link #exitProjection()}, called only on disconnect.
     */
    public static void suspendProjection() {
        projecting = false;
        SoundService.playUI(WandscapeSounds.PROJECTION_EXIT, 1.0f);
        Log.info(TAG, "[Projection] Suspended placement mode (selection preserved)");
    }

    // ── Body anchor ──

    public static BlockPos getBodyAnchor() {
        return bodyAnchor;
    }

    // ── Building selection ──

    public static int getSelectedSlotIndex() {
        return selectedSlotIndex;
    }

    public static void setSelectedSlotIndex(int index) {
        selectedSlotIndex = index;
    }

    public static List<BuildingSlot> getBuildingSlots() {
        synchronized (buildingSlots) {
            return List.copyOf(buildingSlots);
        }
    }

    /**
     * Replace the building slot list in place (no state reset). Used by
     * {@code ProjectionSlotsRefreshPacket} to keep first-free badges accurate
     * after a placement claims a free build. No-op when not projecting.
     */
    public static void updateBuildingSlots(List<BuildingSlot> slots) {
        if (!projecting) return;
        synchronized (buildingSlots) {
            buildingSlots.clear();
            buildingSlots.addAll(slots);
        }
    }

    // ── Ghost position ──

    public static BlockPos getGhostPos() {
        return ghostPos;
    }

    public static void setGhostPos(BlockPos pos) {
        ghostPos = pos;
    }

    /** 当前选中的建筑配置；槽位为空或索引越界时返回 null。 */
    public static BuildingConfig getSelectedConfig() {
        List<BuildingSlot> slots = getBuildingSlots();
        int index = selectedSlotIndex;
        if (slots.isEmpty() || index < 0 || index >= slots.size()) return null;
        return BuildingConfigLoader.getInstance().get(slots.get(index).id());
    }

    /**
     * 把瞄准方块偏移到当前选中建筑旋转后 x/z 包围盒中心上：仅移动 x/z，y 保持瞄准位置。
     * 这样原点在角落（甚至包围盒外）的建筑也能以准心为中心放置。未选中建筑时原样返回。
     */
    public static BlockPos centerAnchor(BlockPos placePos) {
        BuildingConfig config = getSelectedConfig();
        if (config == null) return placePos;
        int[] c = BuildingCentering.rotatedCenterOffsets(config, rotationSteps);
        return new BlockPos(placePos.getX() - c[0], placePos.getY(), placePos.getZ() - c[1]);
    }

    // ── Overlap ──

    public static boolean isOverlapDetected() {
        return overlapDetected;
    }

    public static void setOverlapDetected(boolean overlapped) {
        overlapDetected = overlapped;
    }

    // ── Pinned ghost ──

    /** Whether the ghost preview is fixed to its current position. */
    public static boolean isPinned() {
        return pinned;
    }

    public static void setPinned(boolean fixed) {
        pinned = fixed;
    }

    // ── Rotation ──

    /** Current rotation steps (0-3, each = 90° CCW). */
    public static int getRotationSteps() {
        return rotationSteps;
    }

    /** Increment rotation by one step (90° CCW), wrapping at 4. */
    public static void rotate() {
        rotationSteps = (rotationSteps + 1) & 3;
    }

    /** Reset rotation to 0 (original orientation). */
    public static void resetRotation() {
        rotationSteps = 0;
    }

}
