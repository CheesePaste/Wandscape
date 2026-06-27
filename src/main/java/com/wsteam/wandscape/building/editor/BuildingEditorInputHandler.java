package com.wsteam.wandscape.building.editor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.building.data.BlockOffset;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;

public final class BuildingEditorInputHandler {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final double EDITOR_REACH = 128.0;

    private static boolean wasLeftDown = false;
    private static boolean wasMiddleDown = false;
    private static boolean registered = false;

    // Heartbeat counter — log every N frames to confirm the handler is alive
    private static int tickCounter = 0;
    private static int lastHoverLog = -1;
    private static int lastDragLog = -1;

    private BuildingEditorInputHandler() {}

    public static void register() {
        if (registered) return;
        registered = true;
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS
                .addListener(ClientTickEvent.Pre.class, BuildingEditorInputHandler::onClientTickPre);
        LOGGER.info("[BuildEditor] InputHandler registered");
    }

    static void onClientTickPre(ClientTickEvent.Pre event) {
        if (!BuildingEditorClientState.isEditing()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        // Consume vanilla clicks so MC doesn't see them
        while (mc.options.keyAttack.consumeClick()) {}
        while (mc.options.keyUse.consumeClick()) {}
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Main tick (called from Controller Post tick) ──
    // ═══════════════════════════════════════════════════════════════

    public static void handleClicks(Minecraft mc, long window) {
        if (!BuildingEditorClientState.isEditing()) return;

        tickCounter++;
        boolean heartbeat = (tickCounter % 40 == 0);

        boolean leftDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        boolean middleDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_MIDDLE) == GLFW.GLFW_PRESS;

        boolean leftClicked = leftDown && !wasLeftDown;
        boolean leftReleased = !leftDown && wasLeftDown;

        // ── Is it dragging? (separate from clicked so we can log) ──
        boolean currentlyDragging = BuildingEditorClientState.isDragging();

        if (heartbeat) {
            LOGGER.info("[BuildEditor] tick={} left={} dragging={} hovered={} anchor={}",
                    tickCounter, leftDown, currentlyDragging,
                    BuildingEditorClientState.getHoveredAxis(),
                    BuildingEditorClientState.getWorldAnchor());
        }

        // ── Middle click: pattern ──
        boolean middleClicked = middleDown && !wasMiddleDown;
        if (middleClicked) {
            LOGGER.info("[BuildEditor] MIDDLE CLICK");
            boolean shiftDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
                    || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
            if (shiftDown) addBlockToPattern(mc);
            else removeBlockFromPattern(mc);
        }

        // ── Axis hover (always) ──
        doAxisHover(mc);

        // ── Left clicked: start drag if hovering an axis ──
        if (leftClicked) {
            LOGGER.info("[BuildEditor] LEFT CLICK — hovered={} anchor={}",
                    BuildingEditorClientState.getHoveredAxis(),
                    BuildingEditorClientState.getWorldAnchor());
            if (!startAxisDragIfHovered(mc)) {
                LOGGER.info("[BuildEditor] LEFT CLICK — no hovered axis, ignoring");
            }
        }

        // ── Continue drag ──
        if (currentlyDragging && leftDown) {
            if (tickCounter - lastDragLog > 10) {
                LOGGER.info("[BuildEditor] DRAG tick={} axis={}", tickCounter,
                        BuildingEditorClientState.getDraggingAxis());
                lastDragLog = tickCounter;
            }
            continueAxisDrag(mc);
        }

        // ── Drag release ──
        if (leftReleased && currentlyDragging) {
            LOGGER.info("[BuildEditor] LEFT RELEASE — finishing drag");
            finishAxisDrag();
        }

        wasLeftDown = leftDown;
        wasMiddleDown = middleDown;
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Screen → world ray ──
    // ═══════════════════════════════════════════════════════════════

    private static Vec3 getMouseWorldRay(Minecraft mc) {
        long window = mc.getWindow().getWindow();
        // GLFW gives physical pixels; must account for GUI scale factor to get framebuffer-relative NDC
        double guiScale = mc.getWindow().getGuiScale();
        double[] mx = new double[1], my = new double[1];
        GLFW.glfwGetCursorPos(window, mx, my);
        int w = (int) (mc.getWindow().getWidth());
        int h = (int) (mc.getWindow().getHeight());

        // NDC using framebuffer (pixel) coords — no GUI scale factor here
        double ndcX = (2.0 * mx[0] / w - 1.0);
        double ndcY = (1.0 - 2.0 * my[0] / h);

        Camera cam = mc.gameRenderer.getMainCamera();
        org.joml.Vector3f jLook = cam.getLookVector();
        org.joml.Vector3f jUp   = cam.getUpVector();
        org.joml.Vector3f jLeft = cam.getLeftVector();
        Vec3 forward = new Vec3(jLook.x, jLook.y, jLook.z);
        Vec3 camUp   = new Vec3(jUp.x,   jUp.y,   jUp.z);
        Vec3 camLeft = new Vec3(jLeft.x, jLeft.y, jLeft.z);

        double fovDeg = mc.options.fov().get();
        double fovRad = Math.toRadians(fovDeg);
        double aspect = (double) w / Math.max(h, 1);
        double tanHalfFov = Math.tan(fovRad * 0.5);
        double hExtent = tanHalfFov * aspect;
        double vExtent = tanHalfFov;

        return forward
                .add(camLeft.scale(ndcX * hExtent))
                .add(camUp.scale(ndcY * vExtent))
                .normalize();
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Axis hover ──
    // ═══════════════════════════════════════════════════════════════

    private static void doAxisHover(Minecraft mc) {
        Camera cam = mc.gameRenderer.getMainCamera();
        Vec3 origin = cam.getPosition();
        Vec3 ray = getMouseWorldRay(mc);
        BuildingEditorClientState.AxisDrag hovered =
                BuildingEditorAxisRenderer.hitTestAxis(origin, ray);
        BuildingEditorClientState.AxisDrag prev = BuildingEditorClientState.getHoveredAxis();
        BuildingEditorClientState.setHoveredAxis(hovered);

        if (hovered != prev && tickCounter - lastHoverLog > 20) {
            LOGGER.info("[BuildEditor] HOVER: {} -> {}", prev, hovered);
            lastHoverLog = tickCounter;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Axis drag ──
    // ═══════════════════════════════════════════════════════════════

    // Drag state
    private static Vec3 dragSavedHitCamPlane = null; // first mouse→plane hit in world-space
    private static BlockOffset dragSavedMin = null;
    private static BlockOffset dragSavedMax = null;

    /** Project a screen ray onto a plane through 'planeOrigin' that faces the camera. */
    private static Vec3 hitCameraPlane(Vec3 camPos, Vec3 camDir, Vec3 rayD, Vec3 planeOrigin) {
        double denom = rayD.dot(camDir);
        if (Math.abs(denom) < 1e-6) return null;
        double t = planeOrigin.subtract(camPos).dot(camDir) / denom;
        if (t < 0) return null;
        return camPos.add(rayD.scale(t));
    }

    private static boolean startAxisDragIfHovered(Minecraft mc) {
        BuildingEditorClientState.AxisDrag hovered = BuildingEditorClientState.getHoveredAxis();
        if (hovered == null) return false;
        BlockPos anchor = BuildingEditorClientState.getWorldAnchor();
        if (anchor == null) return false;

        BlockOffset curMin = BuildingEditorClientState.getEditMin();
        BlockOffset curMax = BuildingEditorClientState.getEditMax();
        if (curMin == null) curMin = BlockOffset.of(0, 0, 0);
        if (curMax == null) curMax = BlockOffset.of(0, 0, 0);

        // Plane through AABB corner that faces the camera
        BlockPos worldMin = BuildingEditorClientState.getWorldMin();
        if (worldMin == null) worldMin = anchor;
        Vec3 planeOrigin = new Vec3(worldMin.getX() + 0.5, worldMin.getY() + 0.5, worldMin.getZ() + 0.5);

        Vec3 camPos = mc.gameRenderer.getMainCamera().getPosition();
        Vec3 camDir = getMouseWorldRay(mc);
        Vec3 camForward = new Vec3(
                mc.gameRenderer.getMainCamera().getLookVector().x,
                mc.gameRenderer.getMainCamera().getLookVector().y,
                mc.gameRenderer.getMainCamera().getLookVector().z);

        Vec3 hitWorld = hitCameraPlane(camPos, camForward, camDir, planeOrigin);
        if (hitWorld == null) return false;

        BuildingEditorClientState.setDraggingAxis(hovered);
        dragSavedHitCamPlane = hitWorld;
        dragSavedMin = curMin;
        dragSavedMax = curMax;
        LOGGER.info("[BuildEditor] DRAG START: axis={} savedHit=({},{},{}) min=({},{},{}) max=({},{},{})",
                hovered, hitWorld.x, hitWorld.y, hitWorld.z,
                curMin.x(), curMin.y(), curMin.z(),
                curMax.x(), curMax.y(), curMax.z());
        return true;
    }

    private static void continueAxisDrag(Minecraft mc) {
        BuildingEditorClientState.AxisDrag axis = BuildingEditorClientState.getDraggingAxis();
        if (axis == null || dragSavedHitCamPlane == null || dragSavedMin == null || dragSavedMax == null) return;

        BlockPos worldMin = BuildingEditorClientState.getWorldMin();
        BlockPos anchor = BuildingEditorClientState.getWorldAnchor();
        if (anchor == null) return;
        if (worldMin == null) worldMin = anchor;
        Vec3 planeOrigin = new Vec3(worldMin.getX() + 0.5, worldMin.getY() + 0.5, worldMin.getZ() + 0.5);

        Vec3 camPos = mc.gameRenderer.getMainCamera().getPosition();
        Vec3 camDir = getMouseWorldRay(mc);
        Vec3 camForward = new Vec3(
                mc.gameRenderer.getMainCamera().getLookVector().x,
                mc.gameRenderer.getMainCamera().getLookVector().y,
                mc.gameRenderer.getMainCamera().getLookVector().z);

        Vec3 nowHit = hitCameraPlane(camPos, camForward, camDir, planeOrigin);
        if (nowHit == null) return;

        // How much has the mouse moved in world space on the camera plane?
        Vec3 mouseDelta = nowHit.subtract(dragSavedHitCamPlane);

        // Project onto the dragged axis's world direction
        Vec3 axisWorldDir = switch (axis) {
            case X_POS -> new Vec3( 1, 0, 0);
            case X_NEG -> new Vec3(-1, 0, 0);
            case Y_POS -> new Vec3( 0, 1, 0);
            case Y_NEG -> new Vec3( 0,-1, 0);
            case Z_POS -> new Vec3( 0, 0, 1);
            case Z_NEG -> new Vec3( 0, 0,-1);
        };
        double proj = mouseDelta.dot(axisWorldDir);
        int delta = (int) Math.round(proj);

        if (delta == 0) return;

        int x = dragSavedMin.x(), y = dragSavedMin.y(), z = dragSavedMin.z();
        int mx = dragSavedMax.x(), my = dragSavedMax.y(), mz = dragSavedMax.z();

        switch (axis) {
            case X_POS -> mx = Math.max(0, dragSavedMax.x() + delta);
            case X_NEG -> x = Math.min(0, dragSavedMin.x() - delta);
            case Y_POS -> my = Math.max(0, dragSavedMax.y() + delta);
            case Y_NEG -> y = Math.min(0, dragSavedMin.y() - delta);
            case Z_POS -> mz = Math.max(0, dragSavedMax.z() - delta); // screen-x maps to world-z inverted
            case Z_NEG -> z = Math.min(0, dragSavedMin.z() + delta);
        }

        LOGGER.info("[BuildEditor] DRAG: axis={} delta={} mouseDelta=({},{},{}) min=({},{},{}) max=({},{},{})",
                axis, delta, mouseDelta.x, mouseDelta.y, mouseDelta.z, x, y, z, mx, my, mz);
        BuildingEditorClientState.setEditMin(BlockOffset.of(x, y, z));
        BuildingEditorClientState.setEditMax(BlockOffset.of(mx, my, mz));
        scanBlocks(mc);
    }

    private static void finishAxisDrag() {
        BuildingEditorClientState.AxisDrag axis = BuildingEditorClientState.getDraggingAxis();
        BlockOffset min = BuildingEditorClientState.getEditMin();
        BlockOffset max = BuildingEditorClientState.getEditMax();
        if (min != null && max != null) {
            LOGGER.info("[BuildEditor] DRAG END: axis={} final min=({},{},{}) max=({},{},{})",
                    axis, min.x(), min.y(), min.z(), max.x(), max.y(), max.z());
        }
        BuildingEditorClientState.setDraggingAxis(null);
        dragSavedHitCamPlane = null;
        dragSavedMin = null;
        dragSavedMax = null;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && min != null && max != null) {
            mc.player.displayClientMessage(
                    Component.literal("[BuildEditor] §6AABB: §f[" +
                            min.toKey() + "] -> [" + max.toKey() + "]"), true);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ── ImGui button public entries ──
    // ═══════════════════════════════════════════════════════════════

    public static void setAnchorAtCrosshair() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null || mc.player == null) return;
        BlockPos hit = raycastToBlock(mc);
        if (hit == null) {
            mc.player.displayClientMessage(Component.literal("[BuildEditor] §cNo block in crosshair"), true);
            return;
        }
        BuildingEditorClientState.setWorldAnchor(hit);
        BuildingEditorClientState.setAnchorOffset(BlockOffset.of(0, 0, 0));
        BuildingEditorClientState.setEditMin(BlockOffset.of(0, 0, 0));
        BuildingEditorClientState.setEditMax(BlockOffset.of(0, 0, 0));
        LOGGER.info("[BuildEditor] ANCHOR SET at {}", hit);
    }

    public static void snapMax() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null || mc.player == null) return;
        BlockPos anchor = BuildingEditorClientState.getWorldAnchor();
        if (anchor == null) {
            mc.player.displayClientMessage(Component.literal("[BuildEditor] §cSet anchor first"), true);
            return;
        }
        BlockPos hit = raycastToBlock(mc);
        if (hit == null) return;
        BlockOffset rel = worldToRelative(hit, anchor);
        BlockOffset curMin = BuildingEditorClientState.getEditMin();
        BlockOffset curMax = BuildingEditorClientState.getEditMax();
        int mx = Math.max(curMax != null ? curMax.x() : 0, rel.x());
        int my = Math.max(curMax != null ? curMax.y() : 0, rel.y());
        int mz = Math.max(curMax != null ? curMax.z() : 0, rel.z());
        BuildingEditorClientState.setEditMax(BlockOffset.of(mx, my, mz));
        scanBlocks(mc);
    }

    public static void scanNow() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) scanBlocks(mc);
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Pattern edit ──
    // ═══════════════════════════════════════════════════════════════

    private static void addBlockToPattern(Minecraft mc) {
        BlockPos hitPos = raycastToBlock(mc);
        if (hitPos == null) return;
        BlockPos anchor = BuildingEditorClientState.getWorldAnchor();
        if (anchor == null) return;
        BlockOffset rel = worldToRelative(hitPos, anchor);
        List<BlockOffset> pattern = new ArrayList<>(BuildingEditorClientState.getPattern());
        if (!pattern.contains(rel)) {
            pattern.add(rel);
            BuildingEditorClientState.setPattern(pattern);
            BlockState state = mc.level.getBlockState(hitPos);
            if (!state.isAir()) {
                String blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(
                        state.getBlock()).toString();
                Map<String, String> bm = new HashMap<>(BuildingEditorClientState.getBlockMapping());
                bm.put(rel.toKey(), blockId);
                BuildingEditorClientState.setBlockMapping(bm);
            }
        }
    }

    private static void removeBlockFromPattern(Minecraft mc) {
        BlockPos hitPos = raycastToBlock(mc);
        if (hitPos == null) return;
        BlockPos anchor = BuildingEditorClientState.getWorldAnchor();
        if (anchor == null) return;
        BlockOffset rel = worldToRelative(hitPos, anchor);
        List<BlockOffset> pattern = new ArrayList<>(BuildingEditorClientState.getPattern());
        if (pattern.remove(rel)) {
            BuildingEditorClientState.setPattern(pattern);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Block scanning ──
    // ═══════════════════════════════════════════════════════════════

    public static void scanBlocks(Minecraft mc) {
        if (mc.level == null) return;
        BlockPos worldMin = BuildingEditorClientState.getWorldMin();
        BlockPos worldMax = BuildingEditorClientState.getWorldMax();
        BlockPos anchor = BuildingEditorClientState.getWorldAnchor();
        if (worldMin == null || worldMax == null || anchor == null) {
            LOGGER.info("[BuildEditor] scanBlocks: SKIP wMin={} wMax={} anchor={}", worldMin, worldMax, anchor);
            return;
        }

        List<BlockOffset> pattern = new ArrayList<>();
        Map<String, String> mapping = new HashMap<>();
        int minX = Math.min(worldMin.getX(), worldMax.getX());
        int minY = Math.min(worldMin.getY(), worldMax.getY());
        int minZ = Math.min(worldMin.getZ(), worldMax.getZ());
        int maxX = Math.max(worldMin.getX(), worldMax.getX());
        int maxY = Math.max(worldMin.getY(), worldMax.getY());
        int maxZ = Math.max(worldMin.getZ(), worldMax.getZ());

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = mc.level.getBlockState(pos);
                    if (state.isAir()) continue;
                    BlockOffset rel = worldToRelative(pos, anchor);
                    pattern.add(rel);
                    String blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(
                            state.getBlock()).toString();
                    mapping.put(rel.toKey(), blockId);
                }
            }
        }
        BuildingEditorClientState.setPattern(pattern);
        BuildingEditorClientState.setBlockMapping(mapping);
        LOGGER.info("[BuildEditor] scanBlocks: {} blocks, {} types", pattern.size(),
                mapping.values().stream().distinct().count());
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Utility ──
    // ═══════════════════════════════════════════════════════════════

    private static BlockPos raycastToBlock(Minecraft mc) {
        Camera cam = mc.gameRenderer.getMainCamera();
        Vec3 origin = cam.getPosition();
        Vec3 look = new Vec3(cam.getLookVector().x, cam.getLookVector().y, cam.getLookVector().z());
        var ctx = new net.minecraft.world.level.ClipContext(
                origin, origin.add(look.scale(EDITOR_REACH)),
                net.minecraft.world.level.ClipContext.Block.OUTLINE,
                net.minecraft.world.level.ClipContext.Fluid.NONE, mc.player);
        BlockHitResult hit = mc.level.clip(ctx);
        return hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : null;
    }

    private static BlockOffset worldToRelative(BlockPos pos, BlockPos anchor) {
        return BlockOffset.of(pos.getX() - anchor.getX(), pos.getY() - anchor.getY(), pos.getZ() - anchor.getZ());
    }
}
