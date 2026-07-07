package com.wsteam.wandscape.overview.client;

import java.util.UUID;

import net.minecraft.core.BlockPos;

/**
 * Client-side static state holder for overview (bird's eye) mode.
 * Thread-safe via volatile fields.
 */
public final class OverviewClientState {

    private static volatile boolean active = false;
    private static double camX, camY, camZ;
    private static float camYaw, camPitch;

    /** Block position currently under the crosshair (may be null). */
    private static volatile BlockPos targetBlockPos = null;

    /** Building UUID at targetBlockPos (null = no building hit). */
    private static volatile UUID targetBuildingId = null;

    /** Saved player state on entry — used to reset camera reference on exit. */
    private static double prevX, prevY, prevZ;
    private static float prevYaw, prevPitch;

    /** Mouse state for rotation delta tracking. */
    static double lastMouseX, lastMouseY;

    private OverviewClientState() {}

    // ── Activation ──

    public static boolean isActive() {
        return active;
    }

    public static void enterOverview(double px, double py, double pz, float yaw, float pitch) {
        prevX = px;
        prevY = py;
        prevZ = pz;
        prevYaw = yaw;
        prevPitch = pitch;
        // Start 20 blocks above player, looking straight down
        camX = px;
        camY = py + 20;
        camZ = pz;
        camPitch = 90;
        camYaw = yaw;
        targetBlockPos = null;
        targetBuildingId = null;
        active = true;
    }

    public static void exitOverview() {
        active = false;
        camX = camY = camZ = 0;
        camYaw = camPitch = 0;
        targetBlockPos = null;
        targetBuildingId = null;
    }

    // ── Camera ──

    public static double getCamX() { return camX; }
    public static double getCamY() { return camY; }
    public static double getCamZ() { return camZ; }
    public static float getCamYaw() { return camYaw; }
    public static float getCamPitch() { return camPitch; }

    public static void setCamPosition(double x, double y, double z) {
        camX = x; camY = y; camZ = z;
    }

    public static void addCamRotation(float yawDelta, float pitchDelta) {
        camYaw += yawDelta;
        camPitch += pitchDelta;
        if (camPitch > 90) camPitch = 90;
        if (camPitch < -90) camPitch = -90;
    }

    // ── Target ──

    public static BlockPos getTargetBlockPos() { return targetBlockPos; }
    public static UUID getTargetBuildingId() { return targetBuildingId; }

    public static void setTarget(BlockPos pos, UUID buildingId) {
        targetBlockPos = pos;
        targetBuildingId = buildingId;
    }

    public static void clearTarget() {
        targetBlockPos = null;
        targetBuildingId = null;
    }
}
