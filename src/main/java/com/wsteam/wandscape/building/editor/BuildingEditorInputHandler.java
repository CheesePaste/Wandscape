package com.wsteam.wandscape.building.editor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Vector3f;
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

/**
 * World-click input for the building editor.
 *
 * <p>Flow:
 * <ol>
 *   <li><b>First click</b> (no anchor yet): raycast → set world anchor
 *       + editMin at the clicked block.</li>
 *   <li><b>After anchor set</b>: raycast against axis arrows.
 *       Left-drag along an axis → expand/shrink AABB.
 *       Middle-click → add/remove pattern blocks.</li>
 * </ol>
 *
 * <p>Called from {@link BuildingEditorController} only when ImGui
 * does NOT capture the mouse.
 */
public final class BuildingEditorInputHandler {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final double EDITOR_REACH = 128.0;

    private static boolean wasLeftDown = false;
    private static boolean wasMiddleDown = false;

    private static boolean registered = false;

    private BuildingEditorInputHandler() {}

    public static void register() {
        if (registered) return;
        registered = true;
        var bus = net.neoforged.neoforge.common.NeoForge.EVENT_BUS;
        bus.addListener(ClientTickEvent.Pre.class, BuildingEditorInputHandler::onClientTickPre);
        LOGGER.info("[BuildEditor] Input handler registered");
    }

    static void onClientTickPre(ClientTickEvent.Pre event) {
        if (!BuildingEditorClientState.isEditing()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        while (mc.options.keyAttack.consumeClick()) {}
        while (mc.options.keyUse.consumeClick()) {}
    }

    // ── Main click handler ──

    public static void handleClicks(Minecraft mc, long window) {
        if (!BuildingEditorClientState.isEditing()) return;

        boolean leftDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        boolean middleDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_MIDDLE) == GLFW.GLFW_PRESS;
        boolean shiftDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;

        boolean leftClicked = leftDown && !wasLeftDown;
        boolean middleClicked = middleDown && !wasMiddleDown;

        // ── Middle click: add/remove pattern block ──
        if (middleClicked) {
            if (shiftDown) addBlockToPattern(mc);
            else removeBlockFromPattern(mc);
        }

        // ── Hover detection for axis arrows (always runs) ──
        updateAxisHover(mc);

        // ── Left click / drag ──
        boolean hasAnchor = BuildingEditorClientState.getWorldAnchor() != null;

        if (leftClicked && !hasAnchor) {
            // First click: set anchor + min at hit block
            setAnchorAtCrosshair(mc);
        } else if (leftClicked && hasAnchor) {
            // After anchor: try to start dragging an axis
            startAxisDrag(mc);
        }

        // Continue drag
        if (BuildingEditorClientState.isDragging() && leftDown) {
            continueAxisDrag(mc);
        }

        // Drag release
        if (!leftDown && BuildingEditorClientState.isDragging()) {
            finishAxisDrag();
        }

        wasLeftDown = leftDown;
        wasMiddleDown = middleDown;
    }

    // ── Axis hover ──

    private static void updateAxisHover(Minecraft mc) {
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 origin = camera.getPosition();
        Vec3 look = new Vec3(camera.getLookVector().x(),
                camera.getLookVector().y(), camera.getLookVector().z());

        BuildingEditorClientState.AxisDrag hovered = BuildingEditorAxisRenderer.hitTestAxis(origin, look);
        BuildingEditorClientState.setHoveredAxis(hovered);
    }

    // ── First click: anchor ──

    private static void setAnchorAtCrosshair(Minecraft mc) {
        BlockPos hitPos = raycastBlockFace(mc);
        if (hitPos == null) return;

        BuildingEditorClientState.setWorldAnchor(hitPos);
        BuildingEditorClientState.setAnchorOffset(BlockOffset.of(0, 0, 0));
        BuildingEditorClientState.setEditMin(BlockOffset.of(0, 0, 0));

        mc.player.displayClientMessage(
                Component.literal("[BuildEditor] §aAnchor at §f(" +
                        hitPos.getX() + ", " + hitPos.getY() + ", " + hitPos.getZ() +
                        ") §7— drag axes to define AABB"), true);
    }

    /** ImGui button: set anchor at current crosshair. */
    public static void setAnchorAtCrosshair() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) setAnchorAtCrosshair(mc);
    }

    /** ImGui button: snap max to current crosshair. */
    public static void snapMax() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        BlockPos hitPos = raycastToBlock(mc);
        if (hitPos == null) return;
        BlockPos anchor = BuildingEditorClientState.getWorldAnchor();
        if (anchor == null) return;
        BlockOffset rel = worldToRelative(hitPos, anchor);
        BuildingEditorClientState.setEditMax(rel);
        scanBlocks(mc);
        mc.player.displayClientMessage(
                Component.literal("[BuildEditor] §6Max → §f" + rel.toKey()), true);
    }

    // ── Axis drag ──

    private static void startAxisDrag(Minecraft mc) {
        BuildingEditorClientState.AxisDrag hovered = BuildingEditorClientState.getHoveredAxis();
        if (hovered == null) return;

        BlockPos hitPos = raycastBlockFace(mc);
        BlockPos worldMin = BuildingEditorClientState.getWorldMin();
        BlockOffset curMin = BuildingEditorClientState.getEditMin();
        BlockOffset curMax = BuildingEditorClientState.getEditMax();
        if (curMax == null) curMax = BlockOffset.of(0, 0, 0);

        BuildingEditorClientState.setDraggingAxis(hovered);
        BuildingEditorClientState.setDragStartWorld(hitPos != null ? hitPos : worldMin != null ? worldMin : BlockPos.ZERO);
        BuildingEditorClientState.setDragStartMin(curMin);
        BuildingEditorClientState.setDragStartMax(curMax);
    }

    private static void continueAxisDrag(Minecraft mc) {
        BuildingEditorClientState.AxisDrag axis = BuildingEditorClientState.getDraggingAxis();
        if (axis == null) return;

        BlockPos hitPos = raycastToBlock(mc);
        if (hitPos == null) return; // dragging over empty space — keep previous

        BlockPos anchor = BuildingEditorClientState.getWorldAnchor();
        if (anchor == null) return;

        BlockOffset rel = worldToRelative(hitPos, anchor);
        BlockOffset curMin = BuildingEditorClientState.getDragStartMin();
        BlockOffset curMax = BuildingEditorClientState.getDragStartMax();

        // Snap hit to the dragged axis: only the dragged axis component matters
        int x = curMin.x(), y = curMin.y(), z = curMin.z();
        int mx = curMax.x(), my = curMax.y(), mz = curMax.z();

        switch (axis) {
            case X_POS -> mx = Math.max(rel.x(), 0);
            case X_NEG -> x = Math.min(rel.x(), 0);
            case Y_POS -> my = Math.max(rel.y(), 0);
            case Y_NEG -> y = Math.min(rel.y(), 0);
            case Z_POS -> mz = Math.max(rel.z(), 0);
            case Z_NEG -> z = Math.min(rel.z(), 0);
        }

        BuildingEditorClientState.setEditMin(BlockOffset.of(x, y, z));
        BuildingEditorClientState.setEditMax(BlockOffset.of(mx, my, mz));

        scanBlocks(mc);
    }

    private static void finishAxisDrag() {
        BuildingEditorClientState.AxisDrag axis = BuildingEditorClientState.getDraggingAxis();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && axis != null) {
            BlockOffset min = BuildingEditorClientState.getEditMin();
            BlockOffset max = BuildingEditorClientState.getEditMax();
            mc.player.displayClientMessage(
                    Component.literal("[BuildEditor] §6AABB: §f[" +
                            min.toKey() + "] → [" + max.toKey() + "]"), true);
        }
        BuildingEditorClientState.setDraggingAxis(null);
        BuildingEditorClientState.setDragStartWorld(null);
    }

    // ── Pattern edit ──

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
            mc.player.displayClientMessage(
                    Component.literal("[BuildEditor] §b+ pattern: §f" + rel.toKey()), true);
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
            mc.player.displayClientMessage(
                    Component.literal("[BuildEditor] §c- pattern: §f" + rel.toKey()), true);
        }
    }

    // ── Block scanning ──

    public static void scanBlocks(Minecraft mc) {
        if (mc.level == null) return;
        BlockPos worldMin = BuildingEditorClientState.getWorldMin();
        BlockPos worldMax = BuildingEditorClientState.getWorldMax();
        BlockPos anchor = BuildingEditorClientState.getWorldAnchor();
        if (worldMin == null || worldMax == null || anchor == null) return;

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
    }

    // ── Utility ──

    private static BlockPos raycastToBlock(Minecraft mc) {
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 origin = camera.getPosition();
        Vec3 look = new Vec3(camera.getLookVector().x(),
                camera.getLookVector().y(), camera.getLookVector().z());
        var ctx = new net.minecraft.world.level.ClipContext(
                origin, origin.add(look.scale(EDITOR_REACH)),
                net.minecraft.world.level.ClipContext.Block.OUTLINE,
                net.minecraft.world.level.ClipContext.Fluid.NONE, mc.player);
        BlockHitResult hit = mc.level.clip(ctx);
        return hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : null;
    }

    /** Raycast and return the block position ADJACENT to the hit face (like placing a block). */
    private static BlockPos raycastBlockFace(Minecraft mc) {
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 origin = camera.getPosition();
        Vec3 look = new Vec3(camera.getLookVector().x(),
                camera.getLookVector().y(), camera.getLookVector().z());
        var ctx = new net.minecraft.world.level.ClipContext(
                origin, origin.add(look.scale(EDITOR_REACH)),
                net.minecraft.world.level.ClipContext.Block.OUTLINE,
                net.minecraft.world.level.ClipContext.Fluid.NONE, mc.player);
        BlockHitResult hit = mc.level.clip(ctx);
        if (hit.getType() == HitResult.Type.BLOCK) {
            return hit.getBlockPos().relative(hit.getDirection());
        }
        return null;
    }

    private static BlockOffset worldToRelative(BlockPos pos, BlockPos anchor) {
        return BlockOffset.of(pos.getX() - anchor.getX(), pos.getY() - anchor.getY(), pos.getZ() - anchor.getZ());
    }
}
