package com.wsteam.wandscape.building.scanner.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.wsteam.wandscape.building.data.BlockOffset;
import com.wsteam.wandscape.building.scanner.CreativeScannerBlockEntity;
import com.wsteam.wandscape.building.scanner.ScannerBlockEntity;
import com.wsteam.wandscape.building.scanner.network.ScannerExportPacket;
import com.wsteam.wandscape.building.scanner.network.ScannerSyncPacket;
import com.wsteam.wandscape.shared.ui.component.MedievalScreen;
import com.wsteam.wandscape.shared.ui.theme.MedievalColors;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Survival Building Scanner GUI built on the same MedievalScreen MINIMAL theme as
 * {@link CreativeScannerScreen}, but intentionally stripped down:
 * category is locked to {@code custom} (no maintenance / no tourist interaction),
 * and only size (boundary), door offset, building ID/name and export are exposed.
 * ROAD target mode is preserved.
 */
public class ScannerScreen extends MedievalScreen {

    private static final int PW = 360;
    private static final int PH = 320;

    private final ScannerBlockEntity scanner;

    // ── Custom Medieval Button Definition (same as CreativeScannerScreen) ──
    private record CustomButton(int x, int y, int w, int h, String text, Runnable action) {}
    private final List<CustomButton> customButtons = new ArrayList<>();

    // ── Structure pairing (SAVE/CORNER) ──
    private EditBox structureNameEdit;
    private int modeY;

    // ── Door offset ──
    private EditBox doorX, doorY, doorZ;
    private int detectedDoorIndex = -1;

    // ── Metadata ──
    private EditBox metaId, metaName;

    // ── Export ──
    private Component scanResult = Component.literal("尚未扫描");

    // ── Layout Y positions (computed in init, used in render) ──
    private int lx;
    private int targetY;
    private int boundaryCardY;
    private int boundaryEditY;
    private EditBox bMinX, bMinY, bMinZ, bMaxX, bMaxY, bMaxZ;
    private int doorEditY;
    private int metaStartY;
    private int exportBtnY;

    // ── Field Background Inset Rectangles with EditBox reference ──
    private record FieldRect(int x, int y, int w, int h, EditBox box) {}
    private final List<FieldRect> insetFields = new ArrayList<>();

    // ── Column layout constants (Spacious: max right edge <= lx + 320) ──
    private static final int COL2 = 60;
    private static final int FW = 48;
    private static final int ROW_H = 24;

    // ── Scrolling ──
    private static final int SCROLL_TRAIL = 500;
    private int scrollOff = 0;
    private int maxScroll = 0;

    public ScannerScreen(ScannerBlockEntity scanner) {
        super(Component.literal("Building Scanner"), PW, PH);
        setTitleBar(Component.literal("建筑扫描器"));
        this.showCloseButton = true;
        this.showHelpButton = true;
        this.helpDocumentPath = "scanner_guide";
        this.scanner = scanner;
    }

    private void addCustomButton(int x, int y, int w, int h, String text, Runnable action) {
        customButtons.add(new CustomButton(x, y, w, h, text, action));
    }

    // ── init / rebuild ──

    @Override
    protected void init() {
        super.init();
        customButtons.clear();
        insetFields.clear();

        lx = leftPos + 16;
        int y = topPos + headerHeight + 10 + scrollOff;

        // ── Toolbar Row 1: BlockMode (SAVE/CORNER) + structure name (配对暗号) ──
        modeY = y;
        addCustomButton(lx, y, 90, 20, "Mode: " + scanner.getBlockMode().name(), () -> {
            CreativeScannerBlockEntity.BlockMode next = scanner.getBlockMode() == CreativeScannerBlockEntity.BlockMode.SAVE
                    ? CreativeScannerBlockEntity.BlockMode.CORNER : CreativeScannerBlockEntity.BlockMode.SAVE;
            scanner.setBlockMode(next);
            syncToServer();
            needsRebuild = true;
        });
        structureNameEdit = mkEdit(lx + 145, y, 175, scanner.getStructureName(), s -> {
            scanner.setStructureName(s);
            syncToServer();
        });
        y += 28;

        // ── CORNER mode: minimal pairing UI ──
        if (scanner.getBlockMode() == CreativeScannerBlockEntity.BlockMode.CORNER) {
            addCustomButton(leftPos + PW / 2 - 50, y + 70, 100, 22, "完成", this::onClose);
            maxScroll = -300;
            return;
        }

        // ── Toolbar Row 2: Target Mode + 匹配角点 (+ locked category label) ──
        targetY = y;
        addCustomButton(lx, y, 110, 20, "Target: " + scanner.getTargetMode().name(), () -> {
            ScannerBlockEntity.TargetMode next = scanner.getTargetMode() == ScannerBlockEntity.TargetMode.BUILDING
                    ? ScannerBlockEntity.TargetMode.ROAD : ScannerBlockEntity.TargetMode.BUILDING;
            scanner.setTargetMode(next);
            syncToServer();
            needsRebuild = true;
        });
        addCustomButton(lx + 115, y, 100, 20, "❖ 匹配角点", () -> {
            syncToServer();
            needsRebuild = true;
        });
        y += 28;

        // ── ROAD Target Mode: Ultra-clean UI (Only Road Info, Export & Hot-register) ──
        if (scanner.getTargetMode() == ScannerBlockEntity.TargetMode.ROAD) {
            boundaryCardY = y;
            y += 10;

            boundaryEditY = y;
            addBoundaryEdits(y);
            y += ROW_H * 2 + 14;

            addSectionHeader(y, "❖ 道路预设属性 (Road Preset)");
            y += 16;
            metaStartY = y - 14;

            metaId = mkEdit(lx + 4, y + 14, 155, scanner.getBuildingId().isEmpty() ? "wandscape:custom_road" : scanner.getBuildingId(), s -> {
                scanner.setBuildingId(s);
                syncToServer();
            });
            metaName = mkEdit(lx + 165, y + 14, 155, scanner.getDisplayName().isEmpty() ? "自定义道路" : scanner.getDisplayName(), s -> {
                scanner.setDisplayName(s);
                syncToServer();
            });
            y += 40;

            addSectionHeader(y, "❖ 道路 JSON 导出与热注册");
            y += 18;
            exportBtnY = y - 14;

            addCustomButton(lx + 4, exportBtnY, 95, 22, "扫描区域", () -> doScan());
            addCustomButton(lx + 105, exportBtnY, 215, 22, "导出与热注册道路 JSON", () -> doExport());

            maxScroll = -300;
            return;
        }

        // ── BUILDING Target Mode: category locked to custom ──
        boundaryCardY = y;
        y += 10;

        boundaryEditY = y;
        addBoundaryEdits(y);
        y += ROW_H * 2 + 14;

        // ── Door section ──
        addSectionHeader(y, "❖ 门偏移 (Door Offset)");
        y += 16;
        doorEditY = y + ROW_H - 4;
        y = doorEditY + ROW_H + 8;

        doorX = mkEdit(lx + COL2, doorEditY, FW, loadDoorStr(0), s -> onDoorChanged());
        doorY = mkEdit(lx + COL2 + FW + 4, doorEditY, FW, loadDoorStr(1), s -> onDoorChanged());
        doorZ = mkEdit(lx + COL2 + (FW + 4) * 2, doorEditY, FW, loadDoorStr(2), s -> onDoorChanged());
        addCustomButton(lx + 218, doorEditY, 44, 20, "清除", () -> {
            scanner.setDoorOffset(null);
            doorX.setValue(""); doorY.setValue(""); doorZ.setValue("");
            detectedDoorIndex = -1;
            syncToServer();
        });
        addCustomButton(lx + 266, doorEditY, 54, 20, "自动检门", this::onAutoDetectDoor);

        // ── Building ID / Name ──
        addSectionHeader(y, "❖ 建筑标识");
        y += 16;
        metaStartY = y - 14;

        metaId = mkEdit(lx + 4, y + 14, 155, scanner.getBuildingId(), s -> {
            scanner.setBuildingId(s);
            syncToServer();
        });
        metaName = mkEdit(lx + 165, y + 14, 155, scanner.getDisplayName(), s -> {
            scanner.setDisplayName(s);
            syncToServer();
        });
        y += 40;

        // ── Export section ──
        addSectionHeader(y, "❖ 导出 (类别固定 custom)");
        y += 18;
        exportBtnY = y - 14;

        addCustomButton(lx + 4, exportBtnY, 95, 22, "扫描区域", () -> doScan());
        addCustomButton(lx + 105, exportBtnY, 215, 22, "导出建筑 JSON", () -> doExport());

        maxScroll = -300;
    }

    private void onAutoDetectDoor() {
        if (minecraft == null || minecraft.level == null) return;
        List<BlockOffset> doors = scanner.detectDoors(minecraft.level);
        if (doors.isEmpty()) {
            scanResult = Component.literal("未在包围盒内检测到门方块");
            return;
        }
        detectedDoorIndex = (detectedDoorIndex + 1) % doors.size();
        BlockOffset target = doors.get(detectedDoorIndex);
        scanner.setDoorOffset(target);
        if (doorX != null) doorX.setValue(String.valueOf(target.x()));
        if (doorY != null) doorY.setValue(String.valueOf(target.y()));
        if (doorZ != null) doorZ.setValue(String.valueOf(target.z()));
        scanResult = Component.literal("已选门 #" + (detectedDoorIndex + 1) + "/" + doors.size() + ": (" + target.x() + "," + target.y() + "," + target.z() + ")");
        syncToServer();
    }

    private void addSectionHeader(int y, String title) {
        // Layout marker only
    }

    // ── Widget creation helpers ──

    private EditBox mkEdit(int x, int y, int w, String val, Consumer<String> r) {
        EditBox box = new EditBox(font, x + 3, y + 3, w - 6, 14, Component.empty());
        box.setValue(val);
        box.setBordered(false);
        box.setTextColor(MedievalColors.TEXT_WARM_WHITE);
        box.setTextColorUneditable(MedievalColors.TEXT_MUTED);
        box.setResponder(r);
        insetFields.add(new FieldRect(x, y, w, 20, box));
        return addRenderableWidget(box);
    }

    private void addBoundaryEdits(int y) {
        int cw = 48;
        int gap = 8;
        int col1 = lx + 20;
        int col2 = col1 + cw + gap;
        int col3 = col2 + cw + gap;
        bMinX = mkCoordEdit(col1, y, cw, scanner.getBoundaryMin().x(), this::onBoundaryEdit);
        bMinY = mkCoordEdit(col2, y, cw, scanner.getBoundaryMin().y(), this::onBoundaryEdit);
        bMinZ = mkCoordEdit(col3, y, cw, scanner.getBoundaryMin().z(), this::onBoundaryEdit);
        bMaxX = mkCoordEdit(col1, y + ROW_H, cw, scanner.getBoundaryMax().x(), this::onBoundaryEdit);
        bMaxY = mkCoordEdit(col2, y + ROW_H, cw, scanner.getBoundaryMax().y(), this::onBoundaryEdit);
        bMaxZ = mkCoordEdit(col3, y + ROW_H, cw, scanner.getBoundaryMax().z(), this::onBoundaryEdit);
    }

    private void onBoundaryEdit() {
        scanner.setBoundary(
                BlockOffset.of(intOrZero(bMinX), intOrZero(bMinY), intOrZero(bMinZ)),
                BlockOffset.of(intOrZero(bMaxX), intOrZero(bMaxY), intOrZero(bMaxZ)));
        syncToServer();
    }

    private void drawBoundaryLabels(GuiGraphics gui) {
        if (bMinX == null) return;
        int cw = 48;
        int gap = 8;
        int col1 = lx + 20;
        int col2 = col1 + cw + gap;
        int col3 = col2 + cw + gap;
        gui.drawString(font, "X", col1, boundaryCardY, MedievalColors.TEXT_MUTED);
        gui.drawString(font, "Y", col2, boundaryCardY, MedievalColors.TEXT_MUTED);
        gui.drawString(font, "Z", col3, boundaryCardY, MedievalColors.TEXT_MUTED);
        gui.drawString(font, "min", lx + 2, boundaryEditY + 3, MedievalColors.TEXT_MUTED);
        gui.drawString(font, "max", lx + 2, boundaryEditY + ROW_H + 3, MedievalColors.TEXT_MUTED);
        BlockOffset bMin = scanner.getBoundaryMin();
        BlockOffset bMax = scanner.getBoundaryMax();
        int dx = Math.abs(bMax.x() - bMin.x()) + 1;
        int dy = Math.abs(bMax.y() - bMin.y()) + 1;
        int dz = Math.abs(bMax.z() - bMin.z()) + 1;
        String sizeText = String.format("尺寸 %d×%d×%d  (Min:%d,%d,%d Max:%d,%d,%d)",
                dx, dy, dz, bMin.x(), bMin.y(), bMin.z(), bMax.x(), bMax.y(), bMax.z());
        gui.drawString(font, sizeText, lx + 2, boundaryEditY + ROW_H * 2 + 4, MedievalColors.BORDER_GOLD);
    }

    private EditBox mkCoordEdit(int x, int y, int w, int val, Runnable onChange) {
        EditBox box = new EditBox(font, x + 3, y + 3, w - 6, 14, Component.empty());
        box.setMaxLength(6);
        box.setFilter(s -> s.matches("-?\\d{0,6}"));
        box.setValue(String.valueOf(val));
        box.setBordered(false);
        box.setTextColor(MedievalColors.TEXT_WARM_WHITE);
        box.setTextColorUneditable(MedievalColors.TEXT_MUTED);
        box.setResponder(s -> onChange.run());
        insetFields.add(new FieldRect(x, y, w, 20, box));
        return addRenderableWidget(box);
    }

    private boolean needsRebuild = false;

    @Override
    public void tick() {
        if (needsRebuild) {
            needsRebuild = false;
            rebuild();
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double deltaX, double deltaY) {
        if (deltaY != 0) {
            int newScroll = Math.max(maxScroll, Math.min(0, scrollOff + (int) (deltaY * 20)));
            if (newScroll != scrollOff) {
                scrollOff = newScroll;
                rebuild();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int clipTop = topPos + headerHeight + 2;
        int clipBottom = topPos + PH - 6;
        if (mouseY < clipTop || mouseY > clipBottom) {
            return super.mouseClicked(mouseX, mouseY, button);
        }

        if (button == 0) {
            for (CustomButton btn : customButtons) {
                if (btn.y() + btn.h() > clipTop && btn.y() < clipBottom) {
                    if (isInRect(mouseX, mouseY, btn.x(), btn.y(), btn.w(), btn.h())) {
                        btn.action().run();
                        return true;
                    }
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void rebuild() {
        super.clearWidgets();
        init();
    }

    private String loadDoorStr(int axis) {
        BlockOffset off = scanner.getDoorOffset();
        if (off == null) return "";
        return switch (axis) {
            case 0 -> String.valueOf(off.x());
            case 1 -> String.valueOf(off.y());
            default -> String.valueOf(off.z());
        };
    }

    private void onDoorChanged() {
        String xs = doorX.getValue();
        String ys = doorY.getValue();
        String zs = doorZ.getValue();
        if (xs.isEmpty() || ys.isEmpty() || zs.isEmpty()) {
            scanner.setDoorOffset(null);
            return;
        }
        try {
            scanner.setDoorOffset(BlockOffset.of(
                    Integer.parseInt(xs), Integer.parseInt(ys), Integer.parseInt(zs)));
            syncToServer();
        } catch (NumberFormatException e) {
            // ignore partial input
        }
    }

    private void doScan() {
        BlockPos wMin = scanner.getWorldMin();
        BlockPos wMax = scanner.getWorldMax();
        if (wMin == null || wMax == null) {
            scanResult = Component.literal("未定义 3D 边界");
            return;
        }
        int count = 0;
        BlockPos scannerPos = scanner.getBlockPos();
        for (int x = wMin.getX(); x <= wMax.getX(); x++) {
            for (int y = wMin.getY(); y <= wMax.getY(); y++) {
                for (int z = wMin.getZ(); z <= wMax.getZ(); z++) {
                    BlockPos bp = new BlockPos(x, y, z);
                    if (bp.equals(scannerPos)) continue;
                    if (minecraft != null && minecraft.level != null) {
                        net.minecraft.world.level.block.state.BlockState state = minecraft.level.getBlockState(bp);
                        // Auto-filter both scanner blocks (this survival scanner + creative scanner)
                        if (isScannerBlock(state)) continue;
                        if (!state.isAir()) {
                            count++;
                        }
                    }
                }
            }
        }
        scanResult = Component.literal("已扫描 " + count + " 个有效方块 (不含扫描器)");
    }

    private void doExport() {
        String id = scanner.getBuildingId();
        if (id.isBlank()) {
            scanResult = Component.literal("请先设置 ID");
            return;
        }
        PacketDistributor.sendToServer(new ScannerExportPacket(scanner.getBlockPos()));
        scanResult = Component.literal("已发起导出: " + id);
    }

    private void syncToServer() {
        if (minecraft == null || minecraft.level == null) return;
        // Lock the custom invariant before persisting (belt and suspenders on top of the BE getter overrides).
        scanner.setCategory("custom");
        CompoundTag tag = scanner.saveWithoutMetadata(minecraft.level.registryAccess());
        PacketDistributor.sendToServer(new ScannerSyncPacket(scanner.getBlockPos(), tag));
    }

    private static boolean isScannerBlock(net.minecraft.world.level.block.state.BlockState state) {
        return state.is(com.wsteam.wandscape.Wandscape.BUILDING_SCANNER.get())
                || state.is(com.wsteam.wandscape.Wandscape.CREATIVE_BUILDING_SCANNER.get());
    }

    // ── Render ──

    @Override
    public void render(GuiGraphics gui, int mx, int my, float pt) {
        renderBackground(gui, mx, my, pt);
        renderMinimalHeader(gui);
        renderCloseButton(gui, mx, my);

        int clipTop = topPos + headerHeight + 2;
        int clipBottom = topPos + PH - 6;
        int clipLeft = leftPos + 4;
        int clipRight = leftPos + PW - 4;

        // Hide EditBox components outside viewable scissor region
        for (var child : children()) {
            if (child instanceof EditBox box) {
                box.visible = (box.getY() + box.getHeight() > clipTop && box.getY() < clipBottom);
            }
        }

        gui.enableScissor(clipLeft, clipTop, clipRight, clipBottom);

        // Inset dark field backgrounds with glowing gold borders for all edit boxes inside scissor
        for (FieldRect f : insetFields) {
            if (f.y() + f.h() > clipTop && f.y() < clipBottom) {
                boolean focused = f.box() != null && f.box().isFocused();
                boolean hover = isInRect(mx, my, f.x(), f.y(), f.w(), f.h());
                drawEditBoxBorder(gui, f.x(), f.y(), f.w(), f.h(), focused, hover);
            }
        }

        // Custom medieval minimal box buttons inside scissor
        for (CustomButton btn : customButtons) {
            if (btn.y() + btn.h() > clipTop && btn.y() < clipBottom) {
                boolean hover = isInRect(mx, my, btn.x(), btn.y(), btn.w(), btn.h());
                drawMinimalBox(gui, btn.x(), btn.y(), btn.w(), btn.h(), hover, hover);
                int textColor = hover ? MedievalColors.BORDER_GOLD : MedievalColors.TEXT_WARM_WHITE;
                gui.drawString(font, btn.text(), btn.x() + (btn.w() - font.width(btn.text())) / 2,
                        btn.y() + (btn.h() - font.lineHeight) / 2, textColor);
            }
        }

        // 暗号 label for the structure-name pairing key (Mode row)
        gui.drawString(font, "暗号", lx + 94, modeY + 6, MedievalColors.TEXT_MUTED);

        // Locked category label on the target row (BUILDING mode only)
        if (scanner.getBlockMode() == CreativeScannerBlockEntity.BlockMode.SAVE
                && scanner.getTargetMode() == ScannerBlockEntity.TargetMode.BUILDING) {
            gui.drawString(font, "类别: 自定义", lx + 222, targetY + 6, MedievalColors.TEXT_MUTED);
        }

        // ── CORNER mode render ──
        if (scanner.getBlockMode() == CreativeScannerBlockEntity.BlockMode.CORNER) {
            drawMinimalBox(gui, lx, topPos + headerHeight + 38, 320, 64, true, false);
            gui.drawString(font, "❖ CORNER 辅角点模式", lx + 10, topPos + headerHeight + 46, MedievalColors.BORDER_GOLD);
            gui.drawString(font, "1. 请在上方输入与 SAVE 扫描器相同的暗号。", lx + 10, topPos + headerHeight + 60, MedievalColors.TEXT_WARM_WHITE);
            gui.drawString(font, "2. 将此方块放置在建筑 3D 对角线的另一个顶点位置。", lx + 10, topPos + headerHeight + 74, MedievalColors.TEXT_MUTED);
            gui.disableScissor();
            super.render(gui, mx, my, pt);
            return;
        }

        // ── ROAD Target Mode Render ──
        if (scanner.getTargetMode() == ScannerBlockEntity.TargetMode.ROAD) {
            drawBoundaryLabels(gui);

            drawHdr(gui, "❖ 道路预设属性 (Road Preset)", lx, metaStartY);
            drawLbl(gui, "Road ID", lx + 4, metaStartY + 14);
            drawLbl(gui, "Display Name", lx + 165, metaStartY + 14);

            drawHdr(gui, "❖ 道路 JSON 导出与热注册", lx, exportBtnY - 14);
            gui.drawString(font, scanResult, lx + 5, exportBtnY + 28, MedievalColors.TEXT_MUTED);

            gui.disableScissor();
            super.render(gui, mx, my, pt);
            return;
        }

        // ── BUILDING Target Mode Render ──
        drawBoundaryLabels(gui);

        drawHdr(gui, "❖ 门偏移 (Door Offset)", lx, doorEditY - 14);
        drawLbl(gui, "X", lx + COL2, doorEditY - 10);
        drawLbl(gui, "Y", lx + COL2 + FW + 4, doorEditY - 10);
        drawLbl(gui, "Z", lx + COL2 + (FW + 4) * 2, doorEditY - 10);

        drawHdr(gui, "❖ 建筑标识", lx, metaStartY);
        drawLbl(gui, "ID", lx + 4, metaStartY + 14);
        drawLbl(gui, "Name", lx + 165, metaStartY + 14);

        drawHdr(gui, "❖ 导出 (类别固定 custom)", lx, exportBtnY - 14);
        gui.drawString(font, scanResult, lx + 230, exportBtnY + 6, MedievalColors.TEXT_MUTED);

        gui.disableScissor();

        super.render(gui, mx, my, pt);
    }

    /** Draw custom border for edit box fields with focused/hovered glow effects. */
    private void drawEditBoxBorder(GuiGraphics gui, int x, int y, int w, int h, boolean focused, boolean hover) {
        drawInsetField(gui, x, y, w, h);
        int borderColor = focused ? MedievalColors.BORDER_GOLD
                        : (hover ? 0xAAFFD700 : 0x55806848);
        gui.fill(x, y, x + w, y + 1, borderColor);
        gui.fill(x, y + h - 1, x + w, y + h, borderColor);
        gui.fill(x, y, x + 1, y + h, borderColor);
        gui.fill(x + w - 1, y, x + w, y + h, borderColor);

        if (focused) {
            gui.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0x18FFD700);
        }
    }

    /** Draw bold section header with medieval gold theme. */
    private void drawHdr(GuiGraphics gui, String text, int x, int y) {
        gui.drawString(font, Component.literal("§l" + text), x, y, MedievalColors.BORDER_GOLD);
    }

    /** Draw a label with medieval muted color. */
    private void drawLbl(GuiGraphics gui, String text, int x, int y) {
        gui.drawString(font, text, x, y, MedievalColors.TEXT_MUTED);
    }

    private static int intOrZero(EditBox box) {
        if (box == null) return 0;
        String s = box.getValue();
        if (s == null || s.isEmpty()) return 0;
        try { return Integer.parseInt(s); }
        catch (NumberFormatException e) { return 0; }
    }
}
