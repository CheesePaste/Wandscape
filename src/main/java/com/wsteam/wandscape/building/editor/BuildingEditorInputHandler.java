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
        double[] mx = new double[1], my = new double[1];
        GLFW.glfwGetCursorPos(window, mx, my);
        int w = mc.getWindow().getWidth();
        int h = mc.getWindow().getHeight();

        // 将鼠标坐标转换为标准化设备坐标 (NDC) [-1, 1]
        float ndcX = (float) (2.0 * mx[0] / w - 1.0);
        float ndcY = (float) (1.0 - 2.0 * my[0] / h);

        Camera cam = mc.gameRenderer.getMainCamera();

        // 【关键修复 1】：获取当前帧真实的动态 FOV（考虑了疾跑、飞行等视角形变）
        float fov = (float) mc.options.fov().get();
        float fovRad = (float) Math.toRadians(fov);
        float aspect = (float) w / Math.max(h, 1);
        float tanHalfFov = (float) Math.tan(fovRad * 0.5f);

        // 获取摄像机空间的三个正交基底向量
        org.joml.Vector3f jLook = cam.getLookVector();
        org.joml.Vector3f jUp   = cam.getUpVector();
        org.joml.Vector3f jLeft = cam.getLeftVector();

        Vec3 forward = new Vec3(jLook.x, jLook.y, jLook.z);
        Vec3 up      = new Vec3(jUp.x,   jUp.y,   jUp.z);
        Vec3 right   = new Vec3(jLeft.x, jLeft.y, jLeft.z).scale(-1.0); // 右向量是左向量的相反数

        // 构建完美的 3D 世界射线：前向 + 右向拉伸 + 上向拉伸
        return forward
                .add(right.scale(ndcX * tanHalfFov * aspect))
                .add(up.scale(ndcY * tanHalfFov))
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

    // 拖拽状态记录
    private static double dragStartAxisValue = 0; // 鼠标按下时，射线在 3D 轴上的精确投影位置
    private static BlockOffset dragSavedMin = null;
    private static BlockOffset dragSavedMax = null;

    /**
     * 数学核心：求 鼠标射线(rayOrigin + t*rayDir) 与 拖拽轴(axisOrigin + u*axisDir) 的最近点。
     * 返回的值是在拖拽轴上的位置 (u)。
     */
    private static double getClosestPointOnAxis(Vec3 rayOrigin, Vec3 rayDir, Vec3 axisOrigin, Vec3 axisDir) {
        Vec3 w0 = rayOrigin.subtract(axisOrigin);
        double a = axisDir.dot(axisDir); // 理论上是 1
        double b = axisDir.dot(rayDir);
        double c = rayDir.dot(rayDir);   // 理论上是 1
        double d = axisDir.dot(w0);
        double e = rayDir.dot(w0);

        double denom = a * c - b * b;
        if (Math.abs(denom) < 1e-6) {
            return 0; // 视线与轴完全平行，无法计算
        }
        // 返回在 axisDir 轴上的坐标投影值
        return (b * e - c * d) / denom;
    }

    private static boolean startAxisDragIfHovered(Minecraft mc) {
        BuildingEditorClientState.AxisDrag hovered = BuildingEditorClientState.getHoveredAxis();
        if (hovered == null) return false;

        BlockPos worldAnchor = BuildingEditorClientState.getWorldAnchor();
        if (worldAnchor == null) return false;

        // 获取拖拽基准点
        BlockPos basePos = (hovered.name().endsWith("_POS"))
                ? (BuildingEditorClientState.getWorldMin() != null ? BuildingEditorClientState.getWorldMin() : worldAnchor)
                : BuildingEditorClientState.getWorldMax();
        if (basePos == null) return false;

        Vec3 axisOrigin = new Vec3(basePos.getX() + 0.5, basePos.getY() + 0.5, basePos.getZ() + 0.5);
        Vec3 axisDir = getAxisWorldDir(hovered);

        Vec3 camPos = mc.gameRenderer.getMainCamera().getPosition();
        Vec3 camDir = getMouseWorldRay(mc);

        // 计算初始按下的精确位置
        dragStartAxisValue = getClosestPointOnAxis(camPos, camDir, axisOrigin, axisDir);

        BlockOffset curMin = BuildingEditorClientState.getEditMin();
        BlockOffset curMax = BuildingEditorClientState.getEditMax();
        if (curMin == null) curMin = BlockOffset.of(0, 0, 0);
        if (curMax == null) curMax = BlockOffset.of(0, 0, 0);

        dragSavedMin = curMin;
        dragSavedMax = curMax;
        BuildingEditorClientState.setDraggingAxis(hovered);

        return true;
    }

    private static void continueAxisDrag(Minecraft mc) {
        BuildingEditorClientState.AxisDrag axis = BuildingEditorClientState.getDraggingAxis();
        if (axis == null || dragSavedMin == null || dragSavedMax == null) return;

        BlockPos worldAnchor = BuildingEditorClientState.getWorldAnchor();
        if (worldAnchor == null) return;

        BlockPos basePos = (axis.name().endsWith("_POS"))
                ? (BuildingEditorClientState.getWorldMin() != null ? BuildingEditorClientState.getWorldMin() : worldAnchor)
                : BuildingEditorClientState.getWorldMax();

        Vec3 axisOrigin = new Vec3(basePos.getX() + 0.5, basePos.getY() + 0.5, basePos.getZ() + 0.5);
        Vec3 axisDir = getAxisWorldDir(axis);

        Vec3 camPos = mc.gameRenderer.getMainCamera().getPosition();
        Vec3 camDir = getMouseWorldRay(mc);

        // 计算当前鼠标对应在轴上的位置
        double currentAxisValue = getClosestPointOnAxis(camPos, camDir, axisOrigin, axisDir);

        // 相对位移量（四舍五入到整格）
        int delta = (int) Math.round(currentAxisValue - dragStartAxisValue);
        if (delta == 0) return;

        int x = dragSavedMin.x(), y = dragSavedMin.y(), z = dragSavedMin.z();
        int mx = dragSavedMax.x(), my = dragSavedMax.y(), mz = dragSavedMax.z();

        // 逻辑更新：不允许 Min > Max
        switch (axis) {
            case X_POS -> mx = Math.max(x, dragSavedMax.x() + delta);
            case X_NEG -> x = Math.min(mx, dragSavedMin.x() - delta); // 注意方向：鼠标沿着 X_NEG(-1,0,0) 移动了 delta，所以坐标减去 delta
            case Y_POS -> my = Math.max(y, dragSavedMax.y() + delta);
            case Y_NEG -> y = Math.min(my, dragSavedMin.y() - delta);
            case Z_POS -> mz = Math.max(z, dragSavedMax.z() + delta);
            case Z_NEG -> z = Math.min(mz, dragSavedMin.z() - delta);
        }

        BuildingEditorClientState.setEditMin(BlockOffset.of(x, y, z));
        BuildingEditorClientState.setEditMax(BlockOffset.of(mx, my, mz));

        // 可选：为了性能，拖拽时可以先不 scanBlocks，松开时再 scan。但如果方块不多，实时 scan 也行。
        scanBlocks(mc);
    }

    private static void finishAxisDrag() {
        BuildingEditorClientState.setDraggingAxis(null);
        dragSavedMin = null;
        dragSavedMax = null;
    }

    private static Vec3 getAxisWorldDir(BuildingEditorClientState.AxisDrag axis) {
        return switch (axis) {
            case X_POS -> new Vec3( 1, 0, 0);
            case X_NEG -> new Vec3(-1, 0, 0);
            case Y_POS -> new Vec3( 0, 1, 0);
            case Y_NEG -> new Vec3( 0,-1, 0);
            case Z_POS -> new Vec3( 0, 0, 1);
            case Z_NEG -> new Vec3( 0, 0,-1);
        };
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
