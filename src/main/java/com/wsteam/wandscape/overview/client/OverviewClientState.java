package com.wsteam.wandscape.overview.client;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Client-side static state holder for overview (bird's eye) mode.
 * Thread-safe via volatile fields.
 */
public final class OverviewClientState {

    /** 首次进入空中视角的默认追逐相机几何：后上方 45° 斜视（水平距离=垂直距离）。 */
    private static final double CHASE_HORIZ = 14.0;
    private static final double CHASE_VERT = 14.0;
    private static final float CHASE_PITCH = 45.0f;

    private static volatile boolean active = false;
    private static double camX, camY, camZ;
    private static float camYaw, camPitch;

    /** 相机位置缓存是否有效：跨 enter/exit 保留整个会话，仅 {@link #hardReset()}（断开连接）清零。 */
    private static volatile boolean aerialCacheValid = false;

    /** Block position currently under the crosshair (may be null). */
    private static volatile BlockPos targetBlockPos = null;

    /** Building UUID at targetBlockPos (null = no building hit). */
    private static volatile UUID targetBuildingId = null;

    /** Saved player state on entry — used to reset camera reference on exit. */
    private static double prevX, prevY, prevZ;
    private static float prevYaw, prevPitch;

    /** Mouse state for rotation delta tracking. */
    static double lastMouseX, lastMouseY;

    /** Entity ID currently under the crosshair (-1 = none). */
    private static volatile int targetEntityId = -1;

    private OverviewClientState() {}

    // ── Activation ──

    public static boolean isActive() {
        return active;
    }

    public static void enterOverview(double px, double py, double pz, float yaw, float pitch) {
        // 玩家旋转/位置快照：每次进入都刷新（既作每帧冻结基准，也作首次默认位置基准）
        prevX = px;
        prevY = py;
        prevZ = pz;
        prevYaw = yaw;
        prevPitch = pitch;

        if (!aerialCacheValid) {
            // 首次进入：角色后上方 45° 斜视（能看到地平线 + 玩家背影），取代旧的正上方、视角正下
            Vec3 fwd = Vec3.directionFromRotation(0f, yaw); // 玩家水平前向
            camX = px - fwd.x * CHASE_HORIZ;
            camZ = pz - fwd.z * CHASE_HORIZ;
            camY = py + CHASE_VERT;
            camYaw = yaw;            // 与玩家同朝向 → 看到玩家背影
            camPitch = CHASE_PITCH;  // 俯视 45°
            aerialCacheValid = true;
        }
        // 缓存有效时：camX/Y/Z/yaw/pitch 原样保留（用户上次飞到的位置）

        targetBlockPos = null;
        targetBuildingId = null;
        targetEntityId = -1;
        active = true;
    }

    /**
     * 暂停空中视角（suspend 语义）：只落下 active 标志 + 清瞬态准星目标。
     * 保留 camX/Y/Z/yaw/pitch 与 aerialCacheValid（相机位置缓存），下次进入复用。
     * 仅 {@link #hardReset()}（断开连接）才清缓存。
     */
    public static void exitOverview() {
        active = false;
        targetBlockPos = null;
        targetBuildingId = null;
        targetEntityId = -1;
    }

    /**
     * 硬重置：清零全部状态含相机位置缓存。仅由 {@code WandscapePanelState.reset()}
     * （客户端断开连接）调用，防止上一世界的视角状态泄漏到下一世界。
     */
    public static void hardReset() {
        active = false;
        camX = camY = camZ = 0;
        camYaw = camPitch = 0;
        prevX = prevY = prevZ = 0;
        prevYaw = prevPitch = 0;
        targetBlockPos = null;
        targetBuildingId = null;
        targetEntityId = -1;
        aerialCacheValid = false;
    }

    // ── Camera ──

    public static double getCamX() { return camX; }
    public static double getCamY() { return camY; }
    public static double getCamZ() { return camZ; }
    public static float getCamYaw() { return camYaw; }
    public static float getCamPitch() { return camPitch; }

    /** 进入空中视角时的玩家旋转快照（每帧冻结基准）。 */
    public static float getPrevYaw() { return prevYaw; }
    public static float getPrevPitch() { return prevPitch; }

    public static boolean isAerialCacheValid() { return aerialCacheValid; }

    public static void setCamPosition(double x, double y, double z) {
        camX = x; camY = y; camZ = z;
    }

    public static void addCamRotation(float yawDelta, float pitchDelta) {
        camYaw += yawDelta;
        camPitch += pitchDelta;
        if (camPitch > 90) camPitch = 90;
        if (camPitch < -90) camPitch = -90;
    }

    // ── Target (building) ──

    public static BlockPos getTargetBlockPos() { return targetBlockPos; }
    public static UUID getTargetBuildingId() { return targetBuildingId; }

    public static void setTarget(BlockPos pos, UUID buildingId) {
        targetBlockPos = pos;
        targetBuildingId = buildingId;
        // Building target takes priority — clear entity target
        targetEntityId = -1;
    }

    public static void clearTarget() {
        targetBlockPos = null;
        targetBuildingId = null;
    }

    // ── Target (entity) ──

    public static int getTargetEntityId() { return targetEntityId; }

    public static void setTargetEntity(int entityId) {
        targetEntityId = entityId;
        // Entity target takes priority — clear building target
        targetBlockPos = null;
        targetBuildingId = null;
    }

    public static void clearTargetEntity() {
        targetEntityId = -1;
    }
}
