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
import net.minecraft.client.gui.Font;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Handles mouse+keyboard input for the building editor.
 *
 * <p>Two regimes:
 * <ol>
 *   <li><b>Panel clicks</b>: mouse over the overlay panel → fields get focus, buttons fire.</li>
 *   <li><b>World clicks</b>: mouse over the 3D world → set min/max/anchor/drag handles.</li>
 * </ol>
 *
 * <p>Keyboard input is routed to the focused text field when one is active.
 */
public final class BuildingEditorInputHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final double EDITOR_REACH = 128.0;

    // Input edge state
    private static boolean wasLeftDown = false;
    private static boolean wasRightDown = false;
    private static boolean wasMiddleDown = false;

    // Keyboard edge state (for repeating text input handled in controller)
    private static final StringBuilder charBuf = new StringBuilder();

    private static boolean registered = false;

    private BuildingEditorInputHandler() {}

    public static void register() {
        if (registered) return;
        registered = true;
        var bus = net.neoforged.neoforge.common.NeoForge.EVENT_BUS;
        bus.addListener(ClientTickEvent.Pre.class, BuildingEditorInputHandler::onClientTickPre);
        LOGGER.info("[BuildEditor] Input handler registered");
    }

    // ── Pre-tick: consume vanilla before it sees clicks ──

    static void onClientTickPre(ClientTickEvent.Pre event) {
        if (!BuildingEditorClientState.isEditing()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        // No screen check — overlay runs without Screen, so we always consume
        while (mc.options.keyAttack.consumeClick()) {}
        while (mc.options.keyUse.consumeClick()) {}
    }

    // ── Main click handler (called from Controller Post tick) ──

    public static void handleClicks(Minecraft mc, long window) {
        if (!BuildingEditorClientState.isEditing()) return;

        boolean leftDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        boolean rightDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
        boolean middleDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_MIDDLE) == GLFW.GLFW_PRESS;
        boolean shiftDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;

        boolean leftClicked = leftDown && !wasLeftDown;
        boolean rightClicked = rightDown && !wasRightDown;
        boolean middleClicked = middleDown && !wasMiddleDown;

        // Where is the mouse?
        double mx = mc.mouseHandler.xpos();
        double my = mc.mouseHandler.ypos();
        // Convert window coords → gui-scaled coords
        int guiScale = (int) mc.getWindow().getGuiScale();
        int guiX = (int) (mx / guiScale);
        int guiY = (int) (my / guiScale);

        // Hover tracking for buttons
        if (BuildingEditorClientState.isScreenVisible()) {
            BuildingEditorOverlay.HitResult hit = BuildingEditorOverlay.hitTest(guiX, guiY);
            if (hit != null && hit.isButton()) {
                BuildingEditorClientState.setHoveredButton(hit.fieldId());
            } else {
                BuildingEditorClientState.setHoveredButton(null);
            }
        }

        // Priority: if mouse is over the panel, handle panel interaction
        boolean mouseOverPanel = BuildingEditorClientState.isScreenVisible()
                && guiX >= BuildingEditorOverlay.panelLeft
                && guiX <= BuildingEditorOverlay.panelLeft + BuildingEditorOverlay.PANEL_W
                && guiY >= BuildingEditorOverlay.panelTop
                && guiY <= BuildingEditorOverlay.panelTop + BuildingEditorOverlay.panelHeight;

        if (leftClicked && mouseOverPanel) {
            handlePanelLeftClick(guiX, guiY);
            wasLeftDown = leftDown;
            wasRightDown = rightDown;
            wasMiddleDown = middleDown;
            return; // Don't also do world interaction
        }

        // Panel click with right (cycle category)
        if (rightClicked && mouseOverPanel) {
            BuildingEditorClientState.setFocusedField(null);
            wasLeftDown = leftDown;
            wasRightDown = rightDown;
            wasMiddleDown = middleDown;
            return;
        }

        // ── World interaction (not over panel) ──

        if (leftClicked) {
            // If a text field was focused, clicking world defocuses it
            BuildingEditorClientState.setFocusedField(null);

            if (!startHandleDrag(mc)) {
                if (shiftDown) {
                    setAnchorAtCrosshair(mc);
                } else {
                    setMinAtCrosshair(mc);
                }
            }
        }

        if (rightClicked) {
            BuildingEditorClientState.setFocusedField(null);
            setMaxAtCrosshair(mc);
        }

        if (middleClicked) {
            BuildingEditorClientState.setFocusedField(null);
            if (shiftDown) {
                addBlockToPattern(mc);
            } else {
                removeBlockFromPattern(mc);
            }
        }

        // Drag release
        if (!leftDown && BuildingEditorClientState.isDragging()) {
            BuildingEditorClientState.setActiveDragHandle(null);
            BuildingEditorClientState.setDragStartPos(null);
        }

        if (BuildingEditorClientState.isDragging() && leftDown) {
            continueHandleDrag(mc);
        }

        wasLeftDown = leftDown;
        wasRightDown = rightDown;
        wasMiddleDown = middleDown;
    }

    // ── Panel interaction ──

    private static void handlePanelLeftClick(int guiX, int guiY) {
        BuildingEditorOverlay.HitResult hit = BuildingEditorOverlay.hitTest(guiX, guiY);
        if (hit == null) return;

        if (hit.isButton()) {
            handleButtonClick(hit.fieldId());
        } else if (hit.isField()) {
            BuildingEditorClientState.setFocusedField(hit.fieldId());
        } else {
            BuildingEditorClientState.setFocusedField(null);
        }
    }

    private static void handleButtonClick(String btnId) {
        BuildingEditorClientState.setFocusedField(null);
        switch (btnId) {
            case "exportBtn" -> BuildingEditorController.doExport();
            case "previewBtn" -> {
                boolean sp = !BuildingEditorClientState.isShowPreview();
                BuildingEditorClientState.setShowPreview(sp);
                if (sp) {
                    BuildingEditorClientState.setPreviewJson(
                            BuildingEditorClientState.buildExportJson());
                }
            }
            case "exitBtn" -> BuildingEditorController.doExit();
        }
    }

    // ── Keyboard input for focused fields ──

    /**
     * Process a key press for the currently focused text field.
     * Called from Controller tick.
     *
     * @param keyCode   GLFW key code
     * @param modifiers GLFW modifier bits
     */
    public static void handleKeyPress(int keyCode, int modifiers) {
        String field = BuildingEditorClientState.getFocusedField();
        if (field == null) return;

        boolean ctrl = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;

        if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_ENTER
                || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            BuildingEditorClientState.setFocusedField(null);
            return;
        }

        if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
            applyToField(field, "BACKSPACE");
            return;
        }

        // For GLFW, we get raw key codes. Map common printable chars.
        String ch = glfwKeyToChar(keyCode, modifiers);
        if (ch != null) {
            applyToField(field, ch);
        }
    }

    private static void applyToField(String field, String change) {
        switch (field) {
            case "id" -> BuildingEditorClientState.setBuildingId(
                    applyChange(BuildingEditorClientState.getBuildingId(), change, false));
            case "name" -> BuildingEditorClientState.setDisplayName(
                    applyChange(BuildingEditorClientState.getDisplayName(), change, false));
            case "category" -> {
                // Cycle through categories when editing
                List<String> cats = List.of("basic", "node", "storage", "workstation",
                        "crafting_station", "potion_station", "tavern", "shop", "service", "decoration", "wonder");
                String cur = BuildingEditorClientState.getCategory();
                int idx = cats.indexOf(cur);
                if ("BACKSPACE".equals(change)) {
                    idx = (idx - 1 + cats.size()) % cats.size();
                } else {
                    idx = (idx + 1) % cats.size();
                }
                BuildingEditorClientState.setCategory(cats.get(idx));
            }
            case "blueprint" -> BuildingEditorClientState.setBlueprintId(
                    applyChange(BuildingEditorClientState.getBlueprintId(), change, false));

            // Int fields
            case "comfort" -> BuildingEditorClientState.setComfort(
                    applyIntChange(BuildingEditorClientState.getComfort(), change));
            case "magic" -> BuildingEditorClientState.setMagic(
                    applyIntChange(BuildingEditorClientState.getMagic(), change));
            case "wonder" -> BuildingEditorClientState.setWonder(
                    applyIntChange(BuildingEditorClientState.getWonder(), change));
            case "queueCapacity" -> BuildingEditorClientState.setQueueCapacity(
                    applyIntChange(BuildingEditorClientState.getQueueCapacity(), change));
            case "unlockComfort" -> BuildingEditorClientState.setUnlockMinComfort(
                    applyIntChange(BuildingEditorClientState.getUnlockMinComfort(), change));
            case "unlockMagic" -> BuildingEditorClientState.setUnlockMinMagic(
                    applyIntChange(BuildingEditorClientState.getUnlockMinMagic(), change));
            case "unlockWonder" -> BuildingEditorClientState.setUnlockMinWonder(
                    applyIntChange(BuildingEditorClientState.getUnlockMinWonder(), change));
            case "interactRadius" -> BuildingEditorClientState.setInteractionRadius(
                    applyIntChange(BuildingEditorClientState.getInteractionRadius(), change));
            case "maintInterval" -> BuildingEditorClientState.setMaintenanceIntervalTicks(
                    applyIntChange(BuildingEditorClientState.getMaintenanceIntervalTicks(), change));
        }
    }

    private static String applyChange(String current, String change, boolean isInt) {
        if ("BACKSPACE".equals(change)) {
            if (current.isEmpty()) return current;
            return current.substring(0, current.length() - 1);
        }
        return current + change;
    }

    private static int applyIntChange(int current, String change) {
        if ("BACKSPACE".equals(change)) {
            return current / 10;
        }
        try {
            int digit = Integer.parseInt(change);
            // Clamp to reasonable range
            long result = (long) current * 10 + digit;
            if (result > Integer.MAX_VALUE) return current;
            return (int) result;
        } catch (NumberFormatException e) {
            return current;
        }
    }

    /**
     * Map GLFW key code + modifiers to a character string.
     * Simplified — covers ASCII alphanumerics and common symbols.
     */
    private static String glfwKeyToChar(int keyCode, int modifiers) {
        boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;

        if (keyCode >= GLFW.GLFW_KEY_A && keyCode <= GLFW.GLFW_KEY_Z) {
            char c = (char) ('A' + (keyCode - GLFW.GLFW_KEY_A));
            return String.valueOf(shift ? c : Character.toLowerCase(c));
        }
        if (keyCode >= GLFW.GLFW_KEY_0 && keyCode <= GLFW.GLFW_KEY_9) {
            if (shift) {
                return String.valueOf(")!@#$%^&*(".charAt(keyCode - GLFW.GLFW_KEY_0));
            }
            return String.valueOf((char) ('0' + (keyCode - GLFW.GLFW_KEY_0)));
        }
        if (keyCode == GLFW.GLFW_KEY_MINUS) return shift ? "_" : "-";
        if (keyCode == GLFW.GLFW_KEY_SPACE) return " ";
        if (keyCode == GLFW.GLFW_KEY_PERIOD) return ".";
        if (keyCode == GLFW.GLFW_KEY_SLASH) return "/";
        if (keyCode == GLFW.GLFW_KEY_COMMA) return ",";
        if (keyCode == GLFW.GLFW_KEY_SEMICOLON) return shift ? ":" : ";";

        return null;
    }

    // ═══════════════════════════════════════════════════════════════
    // ── World interaction (unchanged from original) ──
    // ═══════════════════════════════════════════════════════════════

    private static void setMinAtCrosshair(Minecraft mc) {
        BlockPos hitPos = raycastToBlock(mc);
        if (hitPos == null) return;

        BlockPos anchor = BuildingEditorClientState.getWorldAnchor();
        if (anchor == null) {
            BuildingEditorClientState.setWorldAnchor(hitPos);
            BuildingEditorClientState.setAnchorOffset(BlockOffset.of(0, 0, 0));
            BuildingEditorClientState.setEditMin(BlockOffset.of(0, 0, 0));
            mc.player.displayClientMessage(
                    Component.literal("[BuildEditor] §aAnchor + Min at §f(" +
                            hitPos.getX() + ", " + hitPos.getY() + ", " + hitPos.getZ() + ")"), true);
        } else {
            BlockOffset rel = worldToRelative(hitPos, anchor);
            BuildingEditorClientState.setEditMin(rel);
            mc.player.displayClientMessage(
                    Component.literal("[BuildEditor] §aMin → §f" + rel.toKey()), true);
        }
        scanBlocks(mc);
    }

    private static void setMaxAtCrosshair(Minecraft mc) {
        BlockPos hitPos = raycastToBlock(mc);
        if (hitPos == null) return;
        BlockPos anchor = BuildingEditorClientState.getWorldAnchor();
        if (anchor == null) return;
        BlockOffset rel = worldToRelative(hitPos, anchor);
        BuildingEditorClientState.setEditMax(rel);
        mc.player.displayClientMessage(
                Component.literal("[BuildEditor] §6Max → §f" + rel.toKey()), true);
        scanBlocks(mc);
    }

    private static void setAnchorAtCrosshair(Minecraft mc) {
        BlockPos hitPos = raycastToBlock(mc);
        if (hitPos == null) return;
        BlockPos oldAnchor = BuildingEditorClientState.getWorldAnchor();
        if (oldAnchor != null) {
            BlockOffset newAnchorOff = BlockOffset.of(
                    hitPos.getX() - oldAnchor.getX(),
                    hitPos.getY() - oldAnchor.getY(),
                    hitPos.getZ() - oldAnchor.getZ());
            BuildingEditorClientState.setAnchorOffset(newAnchorOff);
            mc.player.displayClientMessage(
                    Component.literal("[BuildEditor] §eAnchor → §f" + newAnchorOff.toKey()), true);
        }
    }

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

    private static boolean startHandleDrag(Minecraft mc) {
        BlockPos hitPos = raycastToBlock(mc);
        if (hitPos == null) return false;
        BuildingEditorClientState.DragHandle handle = findHandleAt(hitPos);
        if (handle == null) return false;
        BuildingEditorClientState.setActiveDragHandle(handle);
        BuildingEditorClientState.setDragStartPos(hitPos);
        return true;
    }

    private static void continueHandleDrag(Minecraft mc) {
        BlockPos hitPos = raycastToBlock(mc);
        if (hitPos == null) return;
        BuildingEditorClientState.DragHandle handle = BuildingEditorClientState.getActiveDragHandle();
        if (handle == null) return;
        BlockPos anchor = BuildingEditorClientState.getWorldAnchor();
        BlockPos min = BuildingEditorClientState.getWorldMin();
        BlockPos max = BuildingEditorClientState.getWorldMax();
        if (anchor == null || min == null || max == null) return;
        BlockOffset rel = worldToRelative(hitPos, anchor);
        BlockOffset cMin = BuildingEditorClientState.getEditMin();
        BlockOffset cMax = BuildingEditorClientState.getEditMax();

        switch (handle) {
            case ANCHOR -> BuildingEditorClientState.setAnchorOffset(rel);
            case CORNER_NNN -> BuildingEditorClientState.setEditMin(rel);
            case CORNER_PPP -> BuildingEditorClientState.setEditMax(rel);
            case CORNER_PNN -> BuildingEditorClientState.setEditMin(BlockOffset.of(rel.x(), cMin.y(), cMin.z()));
            case CORNER_NPN -> BuildingEditorClientState.setEditMin(BlockOffset.of(cMin.x(), rel.y(), cMin.z()));
            case CORNER_NNP -> BuildingEditorClientState.setEditMin(BlockOffset.of(cMin.x(), cMin.y(), rel.z()));
            case CORNER_PNP -> BuildingEditorClientState.setEditMin(BlockOffset.of(rel.x(), cMin.y(), rel.z()));
            case CORNER_PPN -> BuildingEditorClientState.setEditMin(BlockOffset.of(rel.x(), rel.y(), cMin.z()));
            case CORNER_NPP -> BuildingEditorClientState.setEditMin(BlockOffset.of(cMin.x(), rel.y(), rel.z()));
            case FACE_NX   -> BuildingEditorClientState.setEditMin(BlockOffset.of(rel.x(), cMin.y(), cMin.z()));
            case FACE_PX   -> BuildingEditorClientState.setEditMax(BlockOffset.of(rel.x(), cMax.y(), cMax.z()));
            case FACE_NY   -> BuildingEditorClientState.setEditMin(BlockOffset.of(cMin.x(), rel.y(), cMin.z()));
            case FACE_PY   -> BuildingEditorClientState.setEditMax(BlockOffset.of(cMax.x(), rel.y(), cMax.z()));
            case FACE_NZ   -> BuildingEditorClientState.setEditMin(BlockOffset.of(cMin.x(), cMin.y(), rel.z()));
            case FACE_PZ   -> BuildingEditorClientState.setEditMax(BlockOffset.of(cMax.x(), cMax.y(), rel.z()));
        }
        scanBlocks(mc);
    }

    private static BuildingEditorClientState.DragHandle findHandleAt(BlockPos worldPos) {
        BlockPos min = BuildingEditorClientState.getWorldMin();
        BlockPos max = BuildingEditorClientState.getWorldMax();
        if (min == null || max == null) return null;
        double threshold = 1.5;
        if (distance(worldPos, min) < threshold) return BuildingEditorClientState.DragHandle.CORNER_NNN;
        if (distance(worldPos, new BlockPos(max.getX(), min.getY(), min.getZ())) < threshold) return BuildingEditorClientState.DragHandle.CORNER_PNN;
        if (distance(worldPos, new BlockPos(min.getX(), max.getY(), min.getZ())) < threshold) return BuildingEditorClientState.DragHandle.CORNER_NPN;
        if (distance(worldPos, new BlockPos(max.getX(), max.getY(), min.getZ())) < threshold) return BuildingEditorClientState.DragHandle.CORNER_PPN;
        if (distance(worldPos, new BlockPos(min.getX(), min.getY(), max.getZ())) < threshold) return BuildingEditorClientState.DragHandle.CORNER_NNP;
        if (distance(worldPos, new BlockPos(max.getX(), min.getY(), max.getZ())) < threshold) return BuildingEditorClientState.DragHandle.CORNER_PNP;
        if (distance(worldPos, new BlockPos(min.getX(), max.getY(), max.getZ())) < threshold) return BuildingEditorClientState.DragHandle.CORNER_NPP;
        if (distance(worldPos, max) < threshold) return BuildingEditorClientState.DragHandle.CORNER_PPP;
        BlockPos mid = new BlockPos((min.getX()+max.getX())/2, (min.getY()+max.getY())/2, (min.getZ()+max.getZ())/2);
        if (distance(worldPos, new BlockPos(min.getX(), mid.getY(), mid.getZ())) < threshold) return BuildingEditorClientState.DragHandle.FACE_NX;
        if (distance(worldPos, new BlockPos(max.getX(), mid.getY(), mid.getZ())) < threshold) return BuildingEditorClientState.DragHandle.FACE_PX;
        if (distance(worldPos, new BlockPos(mid.getX(), min.getY(), mid.getZ())) < threshold) return BuildingEditorClientState.DragHandle.FACE_NY;
        if (distance(worldPos, new BlockPos(mid.getX(), max.getY(), mid.getZ())) < threshold) return BuildingEditorClientState.DragHandle.FACE_PY;
        if (distance(worldPos, new BlockPos(mid.getX(), mid.getY(), min.getZ())) < threshold) return BuildingEditorClientState.DragHandle.FACE_NZ;
        if (distance(worldPos, new BlockPos(mid.getX(), mid.getY(), max.getZ())) < threshold) return BuildingEditorClientState.DragHandle.FACE_PZ;
        BlockPos anchor = BuildingEditorClientState.getWorldAnchor();
        if (anchor != null && distance(worldPos, anchor) < threshold) return BuildingEditorClientState.DragHandle.ANCHOR;
        return null;
    }

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
        LOGGER.info("[BuildEditor] Scanned {} blocks ({} types)",
                pattern.size(), mapping.values().stream().distinct().count());
    }

    private static BlockPos raycastToBlock(Minecraft mc) {
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 origin = camera.getPosition();
        Vec3 lookVec = new Vec3(camera.getLookVector().x(),
                camera.getLookVector().y(), camera.getLookVector().z());
        var clipCtx = new net.minecraft.world.level.ClipContext(
                origin, origin.add(lookVec.scale(EDITOR_REACH)),
                net.minecraft.world.level.ClipContext.Block.OUTLINE,
                net.minecraft.world.level.ClipContext.Fluid.NONE, mc.player);
        BlockHitResult hit = mc.level.clip(clipCtx);
        return hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : null;
    }

    private static BlockOffset worldToRelative(BlockPos pos, BlockPos anchor) {
        return BlockOffset.of(pos.getX() - anchor.getX(), pos.getY() - anchor.getY(), pos.getZ() - anchor.getZ());
    }

    private static double distance(BlockPos a, BlockPos b) {
        return Math.sqrt(a.distSqr(b));
    }
}
