package com.wsteam.wandscape.projection.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
     * Enter projection mode. Called from {@code ProjectionEnterResponsePacket} client handler.
     * Saves current player state, enablestransl flight, stores building slots.
     */
    public static void enterProjection(BlockPos anchor, List<BuildingSlot> slots) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        bodyAnchor = anchor;

        // Store building slots
        synchronized (buildingSlots) {
            buildingSlots.clear();
            buildingSlots.addAll(slots);
        }
        selectedSlotIndex = 0;
        ghostPos = null;
        overlapDetected = false;
        rotationSteps = 0;

        projecting = true;

        Log.info(TAG, "[Projection] Entered placement mode. Body at {}, {} buildings",
                anchor, slots.size());
    }

    /**
     * Exit projection mode. Called from client handler or flight controller.
     * Clears state without teleporting.
     */
    public static void exitProjection() {
        projecting = false;

        // Clear state
        bodyAnchor = null;
        selectedSlotIndex = 0;
        ghostPos = null;
        overlapDetected = false;
        rotationSteps = 0;
        synchronized (buildingSlots) {
            buildingSlots.clear();
        }

        Log.info(TAG, "[Projection] Exited projection mode");
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

    // ── Ghost position ──

    public static BlockPos getGhostPos() {
        return ghostPos;
    }

    public static void setGhostPos(BlockPos pos) {
        ghostPos = pos;
    }

    // ── Overlap ──

    public static boolean isOverlapDetected() {
        return overlapDetected;
    }

    public static void setOverlapDetected(boolean overlapped) {
        overlapDetected = overlapped;
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
