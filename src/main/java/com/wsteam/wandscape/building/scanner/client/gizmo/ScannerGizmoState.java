package com.wsteam.wandscape.building.scanner.client.gizmo;

import com.wsteam.wandscape.building.data.BlockOffset;
import com.wsteam.wandscape.building.scanner.CreativeScannerBlockEntity;
import com.wsteam.wandscape.building.scanner.ScannerBlockEntity;
import com.wsteam.wandscape.building.scanner.client.CreativeScannerScreen;
import com.wsteam.wandscape.building.scanner.client.ScannerScreen;
import com.wsteam.wandscape.building.scanner.network.ScannerSyncPacket;
import com.wsteam.wandscape.shared.log.Log;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client-side state manager for the 3D Building Scanner Visual Adjuster (Gizmo Editor).
 */
public final class ScannerGizmoState {
    private static final String TAG = "ScannerGizmoState";

    public enum Anchor {
        MIN("Min 最小角点", 0xFF00E5FF),
        MAX("Max 最大角点", 0xFFFFD700);

        private final String label;
        private final int color;

        Anchor(String label, int color) {
            this.label = label;
            this.color = color;
        }

        public String getLabel() { return label; }
        public int getColor() { return color; }
    }

    public enum AxisDrag {
        NONE,
        X_POS, X_NEG,
        Y_POS, Y_NEG,
        Z_POS, Z_NEG
    }

    private static boolean active = false;
    private static CreativeScannerBlockEntity scanner = null;

    private static BlockOffset initialMin = BlockOffset.of(0, 0, 0);
    private static BlockOffset initialMax = BlockOffset.of(0, 0, 0);
    private static BlockOffset currentMin = BlockOffset.of(0, 0, 0);
    private static BlockOffset currentMax = BlockOffset.of(0, 0, 0);

    private static Anchor selectedAnchor = Anchor.MAX;
    private static AxisDrag hoveredAxis = AxisDrag.NONE;
    private static AxisDrag draggingAxis = AxisDrag.NONE;

    private static BlockOffset dragStartOffset = BlockOffset.of(0, 0, 0);
    private static Vec3 dragStartAxisOrigin = null;
    private static double dragStartAxisValue = 0.0;

    // Toast popup message
    private static String toastMessage = null;
    private static int toastColor = 0xFF55FF55;
    private static long toastExpireMs = 0;

    private ScannerGizmoState() {}

    public static boolean isActive() {
        return active;
    }

    public static CreativeScannerBlockEntity getScanner() {
        return scanner;
    }

    public static BlockOffset getCurrentMin() {
        return currentMin;
    }

    public static BlockOffset getCurrentMax() {
        return currentMax;
    }

    public static Anchor getSelectedAnchor() {
        return selectedAnchor;
    }

    public static void setSelectedAnchor(Anchor a) {
        selectedAnchor = a;
        showToast(com.wsteam.wandscape.shared.ui.I18n.string("gui.wandscape.gizmo.toast_anchor_switched", "已切换编辑锚点: %s", a.getLabel()), a.getColor());
    }

    public static void toggleAnchor() {
        setSelectedAnchor(selectedAnchor == Anchor.MIN ? Anchor.MAX : Anchor.MIN);
    }

    public static AxisDrag getHoveredAxis() {
        return hoveredAxis;
    }

    public static void setHoveredAxis(AxisDrag a) {
        hoveredAxis = a;
    }

    public static AxisDrag getDraggingAxis() {
        return draggingAxis;
    }

    public static void setDraggingAxis(AxisDrag a) {
        draggingAxis = a;
    }

    public static boolean isDragging() {
        return draggingAxis != AxisDrag.NONE;
    }

    public static BlockOffset getDragStartOffset() {
        return dragStartOffset;
    }

    public static Vec3 getDragStartAxisOrigin() {
        return dragStartAxisOrigin;
    }

    public static double getDragStartAxisValue() {
        return dragStartAxisValue;
    }

    public static void setDragStartState(BlockOffset offset, Vec3 origin, double val) {
        dragStartOffset = offset;
        dragStartAxisOrigin = origin;
        dragStartAxisValue = val;
    }

    // ── Dimension & Volume ──

    public static int getWidth() {
        return Math.abs(currentMax.x() - currentMin.x()) + 1;
    }

    public static int getHeight() {
        return Math.abs(currentMax.y() - currentMin.y()) + 1;
    }

    public static int getDepth() {
        return Math.abs(currentMax.z() - currentMin.z()) + 1;
    }

    public static long getVolume() {
        return (long) getWidth() * getHeight() * getDepth();
    }

    // ── Toast Messages ──

    public static void showToast(String msg, int color) {
        toastMessage = msg;
        toastColor = color;
        toastExpireMs = System.currentTimeMillis() + 3000L;
    }

    public static String getToastMessage() {
        if (toastMessage == null || System.currentTimeMillis() > toastExpireMs) {
            toastMessage = null;
            return null;
        }
        return toastMessage;
    }

    public static int getToastColor() {
        return toastColor;
    }

    // ── Lifecycle ──

    public static void enter(CreativeScannerBlockEntity be) {
        if (be == null) return;
        scanner = be;
        active = true;
        initialMin = be.getBoundaryMin();
        initialMax = be.getBoundaryMax();
        currentMin = initialMin;
        currentMax = initialMax;
        selectedAnchor = Anchor.MAX;
        hoveredAxis = AxisDrag.NONE;
        draggingAxis = AxisDrag.NONE;

        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) {
            mc.setScreen(null);
        }
        if (mc.mouseHandler != null) {
            mc.mouseHandler.releaseMouse();
        }

        showToast(com.wsteam.wandscape.shared.ui.I18n.string("gui.wandscape.gizmo.toast_enter", "§a✓ 已进入 3D 可视化调整模式 (右键旋转视角, 左键拖拽轴向)"), 0xFF55FF55);
        Log.info(TAG, "Entered Gizmo mode for scanner at {}", be.getBlockPos());
    }

    public static void confirm() {
        if (!active || scanner == null) return;

        // Normalize min and max
        int minX = Math.min(currentMin.x(), currentMax.x());
        int minY = Math.min(currentMin.y(), currentMax.y());
        int minZ = Math.min(currentMin.z(), currentMax.z());
        int maxX = Math.max(currentMin.x(), currentMax.x());
        int maxY = Math.max(currentMin.y(), currentMax.y());
        int maxZ = Math.max(currentMin.z(), currentMax.z());

        currentMin = BlockOffset.of(minX, minY, minZ);
        currentMax = BlockOffset.of(maxX, maxY, maxZ);

        scanner.setBoundary(currentMin, currentMax);

        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            PacketDistributor.sendToServer(new ScannerSyncPacket(
                    scanner.getBlockPos(),
                    scanner.saveWithoutMetadata(mc.level.registryAccess())
            ));
        }

        CreativeScannerBlockEntity be = scanner;
        active = false;
        if (mc.mouseHandler != null) {
            mc.mouseHandler.releaseMouse();
        }

        CreativeScannerScreen screen = (be instanceof ScannerBlockEntity survival)
                ? new ScannerScreen(survival)
                : new CreativeScannerScreen(be);
        mc.setScreen(screen);
        screen.showFeedback(com.wsteam.wandscape.shared.ui.I18n.name("gui.wandscape.gizmo.toast_confirmed", "§a✓ 3D 包围盒已更新并同步！(%d×%d×%d)", getWidth(), getHeight(), getDepth()), 0xFF55FF55);
        Log.info(TAG, "Confirmed Gizmo changes: min={}, max={}", currentMin, currentMax);
    }

    public static void cancel() {
        if (!active || scanner == null) return;

        CreativeScannerBlockEntity be = scanner;
        scanner.setBoundary(initialMin, initialMax);
        active = false;

        Minecraft mc = Minecraft.getInstance();
        if (mc.mouseHandler != null) {
            mc.mouseHandler.releaseMouse();
        }

        CreativeScannerScreen screen = (be instanceof ScannerBlockEntity survival)
                ? new ScannerScreen(survival)
                : new CreativeScannerScreen(be);
        mc.setScreen(screen);
        screen.showFeedback(com.wsteam.wandscape.shared.ui.I18n.name("gui.wandscape.gizmo.toast_cancelled", "§6✓ 已还原原始 3D 边界配置。"), 0xFFFFAA00);
        Log.info(TAG, "Cancelled Gizmo mode, reverted to min={}, max={}", initialMin, initialMax);
    }

    // ── Adjustments ──

    public static void adjustMin(int dx, int dy, int dz) {
        currentMin = BlockOffset.of(currentMin.x() + dx, currentMin.y() + dy, currentMin.z() + dz);
        if (scanner != null) {
            scanner.setBoundary(currentMin, currentMax);
        }
    }

    public static void adjustMax(int dx, int dy, int dz) {
        currentMax = BlockOffset.of(currentMax.x() + dx, currentMax.y() + dy, currentMax.z() + dz);
        if (scanner != null) {
            scanner.setBoundary(currentMin, currentMax);
        }
    }

    public static void setMin(int x, int y, int z) {
        currentMin = BlockOffset.of(x, y, z);
        if (scanner != null) {
            scanner.setBoundary(currentMin, currentMax);
        }
    }

    public static void setMax(int x, int y, int z) {
        currentMax = BlockOffset.of(x, y, z);
        if (scanner != null) {
            scanner.setBoundary(currentMin, currentMax);
        }
    }

    public static Vec3 getWorldAnchorPos(Anchor anchor) {
        if (scanner == null) return Vec3.ZERO;
        BlockPos bePos = scanner.getBlockPos();
        if (anchor == Anchor.MIN) {
            return new Vec3(bePos.getX() + currentMin.x(), bePos.getY() + currentMin.y(), bePos.getZ() + currentMin.z());
        } else {
            return new Vec3(bePos.getX() + currentMax.x() + 1.0, bePos.getY() + currentMax.y() + 1.0, bePos.getZ() + currentMax.z() + 1.0);
        }
    }
}