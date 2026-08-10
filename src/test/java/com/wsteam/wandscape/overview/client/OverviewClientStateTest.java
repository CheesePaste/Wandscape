package com.wsteam.wandscape.overview.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.world.phys.Vec3;

/**
 * Lifecycle tests for {@link OverviewClientState}'s aerial camera cache.
 *
 * <p>Covers the split between the <b>camera-position cache</b> (camX/Y/Z/yaw/pitch +
 * {@code aerialCacheValid}, survives enter/exit across a whole session, cleared only by
 * {@code hardReset}) and the <b>player-rotation snapshot</b> (prevYaw/prevPitch, re-sampled
 * on every enter). The first entry computes a 45° chase-cam default; subsequent entries
 * restore the cached (flown) camera. {@code SoundService.playUI} early-returns on an unbound
 * sound and {@code Vec3.directionFromRotation} is pure math, so these run without a MC runtime.
 */
@DisplayName("OverviewClientState 空中相机缓存生命周期")
class OverviewClientStateTest {

    private static final double CHASE = 14.0;

    @BeforeEach
    void resetState() {
        // hardReset fully clears static state (incl. the cache flag) so tests stay isolated.
        OverviewClientState.hardReset();
    }

    @Test
    @DisplayName("首次 enterOverview 计算 45° 后上方默认并置缓存有效")
    void firstEntryComputesChaseDefault() {
        float yaw = 90f;
        OverviewClientState.enterOverview(0, 0, 0, yaw, 0f);

        assertTrue(OverviewClientState.isActive(), "active after enter");
        assertTrue(OverviewClientState.isAerialCacheValid(), "aerial cache marked valid on first entry");

        Vec3 fwd = Vec3.directionFromRotation(0f, yaw); // 玩家水平前向
        assertEquals(0 - fwd.x * CHASE, OverviewClientState.getCamX(), 1e-6, "camX behind player");
        assertEquals(0 + CHASE, OverviewClientState.getCamY(), 1e-6, "camY above player");
        assertEquals(0 - fwd.z * CHASE, OverviewClientState.getCamZ(), 1e-6, "camZ behind player");
        assertEquals(yaw, OverviewClientState.getCamYaw(), 1e-6, "camYaw matches player yaw");
        assertEquals(45f, OverviewClientState.getCamPitch(), 1e-6, "camPitch is 45° (not straight down)");
    }

    @Test
    @DisplayName("原地（未走远）重开 → 命中相机缓存，但刷新玩家旋转快照")
    void suspendExitPreservesCameraCacheWhenNear() {
        OverviewClientState.enterOverview(0, 0, 0, 0f, 0f); // 计算默认 + 缓存锚点 (0,0)
        // 模拟飞行到一个自定义相机位置/朝向
        OverviewClientState.setCamPosition(100, 200, 300);
        OverviewClientState.addCamRotation(30f, -20f); // yaw 0→30, pitch 45→25
        float flownYaw = OverviewClientState.getCamYaw();
        float flownPitch = OverviewClientState.getCamPitch();

        OverviewClientState.exitOverview(); // suspend：保留 cam 与 aerialCacheValid

        assertFalse(OverviewClientState.isActive(), "inactive after exit");
        assertTrue(OverviewClientState.isAerialCacheValid(), "camera cache survives exit");

        // 在锚点附近重开（距 (0,0) ≈1.4 格，远小于失效阈值）→ 命中缓存
        OverviewClientState.enterOverview(1, 0, 1, 123f, -10f);

        assertTrue(OverviewClientState.isActive(), "re-entered active");
        // 相机缓存命中：cam 字段原样保留
        assertEquals(100, OverviewClientState.getCamX(), 1e-6, "cached camX preserved");
        assertEquals(200, OverviewClientState.getCamY(), 1e-6, "cached camY preserved");
        assertEquals(300, OverviewClientState.getCamZ(), 1e-6, "cached camZ preserved");
        assertEquals(flownYaw, OverviewClientState.getCamYaw(), 1e-6, "cached camYaw preserved");
        assertEquals(flownPitch, OverviewClientState.getCamPitch(), 1e-6, "cached camPitch preserved");
        // 但玩家旋转快照每次刷新为新入参（冻结基准不跨会话缓存）
        assertEquals(123f, OverviewClientState.getPrevYaw(), 1e-6, "prevYaw re-sampled on enter");
        assertEquals(-10f, OverviewClientState.getPrevPitch(), 1e-6, "prevPitch re-sampled on enter");
    }

    @Test
    @DisplayName("玩家走远后重开 → 缓存失效，按新位置重算 45° 默认")
    void cacheInvalidatesWhenPlayerMovedFar() {
        OverviewClientState.enterOverview(0, 0, 0, 0f, 0f); // 缓存锚点 (0,0)
        OverviewClientState.setCamPosition(100, 200, 300);  // 飞到一个位置
        OverviewClientState.exitOverview();                 // suspend 保留缓存
        assertTrue(OverviewClientState.isAerialCacheValid(), "cache valid after exit");

        // 走远后重开（水平位移 50,50 ≈ 70 格，远超失效阈值）→ 缓存失效、重算默认
        OverviewClientState.enterOverview(50, 0, 50, 0f, 0f);

        Vec3 fwd = Vec3.directionFromRotation(0f, 0f);
        assertEquals(50 - fwd.x * CHASE, OverviewClientState.getCamX(), 1e-6, "camX recomputed at new pos");
        assertEquals(0 + CHASE, OverviewClientState.getCamY(), 1e-6, "camY recomputed at new pos");
        assertEquals(50 - fwd.z * CHASE, OverviewClientState.getCamZ(), 1e-6, "camZ recomputed at new pos");
        assertEquals(45f, OverviewClientState.getCamPitch(), 1e-6, "camPitch back to 45° default");
    }

    @Test
    @DisplayName("hardReset 清空相机缓存，下次 enter 重算默认")
    void hardResetClearsCache() {
        OverviewClientState.enterOverview(0, 0, 0, 0f, 0f); // cache valid
        OverviewClientState.setCamPosition(50, 60, 70);     // 自定义飞行位置

        OverviewClientState.hardReset();

        assertFalse(OverviewClientState.isActive(), "inactive after hardReset");
        assertFalse(OverviewClientState.isAerialCacheValid(), "cache invalidated by hardReset");

        // 重新进入：重算 45° 默认，而非沿用飞行的 50/60/70
        float yaw = 0f;
        OverviewClientState.enterOverview(10, 20, 30, yaw, 0f);

        Vec3 fwd = Vec3.directionFromRotation(0f, yaw);
        assertEquals(10 - fwd.x * CHASE, OverviewClientState.getCamX(), 1e-6, "camX recomputed after hardReset");
        assertEquals(20 + CHASE, OverviewClientState.getCamY(), 1e-6, "camY recomputed after hardReset");
        assertEquals(30 - fwd.z * CHASE, OverviewClientState.getCamZ(), 1e-6, "camZ recomputed after hardReset");
        assertEquals(45f, OverviewClientState.getCamPitch(), 1e-6, "camPitch back to 45° default");
    }
}
