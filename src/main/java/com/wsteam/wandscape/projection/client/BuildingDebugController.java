package com.wsteam.wandscape.projection.client;

import com.wsteam.wandscape.projection.network.BuildingDebugRequestPacket;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.common.NeoForge;
import com.wsteam.wandscape.shared.log.Log;

import javax.annotation.Nullable;

/**
 * Standalone debug-inspect mode controller.
 *
 * <p>When active (G key toggled), continuously raycasts from the camera
 * and sends {@link BuildingDebugRequestPacket} to the server when the
 * looked-at block position changes. The server responds with building
 * data, which is rendered as a small translucent HUD overlay by
 * {@link BuildingDebugOverlay}.
 *
 * <p>No left-click needed — just look at a building to see its info.
 */
public final class BuildingDebugController {

    private static final String TAG = "BuildingDebugController";
    private static final double DEBUG_REACH = 64.0;
    /** Minimum interval between packets in ms to avoid spam when sweeping view. */
    private static final long MIN_REQUEST_INTERVAL_MS = 200;

    private static boolean registered = false;

    private BuildingDebugController() {}

    public static void register() {
        if (registered) return;
        registered = true;
        var bus = NeoForge.EVENT_BUS;
        bus.addListener(ClientTickEvent.Post.class, BuildingDebugController::onClientTickPost);
        Log.info(TAG, "[Debug] Controller registered (auto-raycast mode)");
    }

    public static void toggle() {
        boolean newActive = !BuildingDebugClientState.isActive();
        BuildingDebugClientState.setActive(newActive);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(
                    Component.literal("[Debug] " + (newActive ? "§aON §f— look at buildings to inspect" : "§cOFF")),
                    false);
        }
    }

    // ── Post-tick: auto raycast ───────────────────────────────────────────

    static void onClientTickPost(ClientTickEvent.Post event) {
        if (!BuildingDebugClientState.isActive()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        if (mc.screen != null) return; // don't raycast when another screen is open

        BlockPos hitPos = raycastBuildingPos(mc);

        if (hitPos == null) {
            // Looking at sky or non-block — clear overlay
            if (BuildingDebugClientState.getLastRequestedPos() != null) {
                Log.info(TAG, "[Debug] Look-away — clearing overlay (was pos={})",
                        BuildingDebugClientState.getLastRequestedPos());
            }
            BuildingDebugClientState.setLastRequestedPos(null);
            BuildingDebugClientState.clearCachedData();
            return;
        }

        BlockPos lastPos = BuildingDebugClientState.getLastRequestedPos();
        long now = System.currentTimeMillis();

        // Same position — nothing to do (already requested or response cached)
        if (hitPos.equals(lastPos)) return;

        // Different position — send new request
        long lastTime = BuildingDebugClientState.getLastRequestTime();
        if (now - lastTime < MIN_REQUEST_INTERVAL_MS) return; // rate limit

        BuildingDebugClientState.setLastRequestedPos(hitPos);
        BuildingDebugClientState.setLastRequestTime(now);
        BuildingDebugClientState.clearCachedData(); // clear old data while waiting

        PacketDistributor.sendToServer(new BuildingDebugRequestPacket(hitPos));
        Log.info(TAG, "[Debug] Sent request: pos={} prevPos={}", hitPos, lastPos);
    }

    // ── Raycast ─────────────────────────────────────────────────────────────

    @Nullable
    private static BlockPos raycastBuildingPos(Minecraft mc) {
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 origin = camera.getPosition();
        Vec3 lookVec = new Vec3(camera.getLookVector().x(),
                camera.getLookVector().y(), camera.getLookVector().z());

        ClipContext ctx = new ClipContext(
                origin, origin.add(lookVec.scale(DEBUG_REACH)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player);
        BlockHitResult hit = mc.level.clip(ctx);

        if (hit.getType() != HitResult.Type.BLOCK) return null;
        return hit.getBlockPos();
    }
}
