package com.wsteam.wandscape.building.scanner.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.wsteam.wandscape.building.data.BlockOffset;
import com.wsteam.wandscape.building.data.BuildingConfig.BoundaryBox;
import com.wsteam.wandscape.building.scanner.BuildingScannerBlockEntity;
import com.wsteam.wandscape.building.scanner.network.BuildingScannerSyncPacket;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * All-in-one scanner GUI. Clean form layout with generous spacing.
 * Categories switch building type inline; category-specific fields
 * appear/disappear automatically.
 */
public class BuildingScannerScreen extends Screen {

    private final BuildingScannerBlockEntity scanner;

    // ── Category ──
    private CycleButton<String> categoryBtn;
    private static final List<String> CATEGORIES = List.of(
            "basic", "node", "storage", "workstation", "crafting_station",
            "potion_station", "tavern", "shop", "service", "decoration", "wonder"
    );

    // ── Boundary (two corners) ──
    private EditBox minX, minY, minZ, maxX, maxY, maxZ;

    // ── Door offset ──
    private EditBox doorX, doorY, doorZ;

    // ── Interact zones ──
    private final List<ZoneRow> zoneRows = new ArrayList<>();

    // ── Metadata ──
    private EditBox metaId, metaName;
    private EditBox metaComfort, metaMagic, metaWonder;

    // ── Unlock requirement ──
    private EditBox unlockLevel;

    // ── Shop config (shown when category=shop) ──
    private EditBox shopProfitRate, shopDuration;

    // ── Service config (shown when category=service) ──
    private EditBox serviceEnergy, serviceMaxOcc, serviceDuration;

    // ── Presets ──
    private EditBox presetNameEdit;
    private int presetY;

    // ── Scrolling ──
    private int scrollOff = 0;
    private int maxScroll = 0;

    // ── Export ──
    private Component scanResult = Component.literal("Not scanned yet");

    // ── Layout Y positions (computed in init, used in render) ──
    private int lx; // left edge for widgets
    private int boundaryMinY, corner2Y, sizeInfoY;
    private int doorEditY;
    private int zoneHeaderY;
    private int metaStartY, metaLabelY;
    private int unlockY;
    private int shopCatY, svcCatY;
    private int exportBtnY, exportResultY;

    // ── Column layout constants ──
    private static final int COL1 = 0;   // label column (left-aligned)
    private static final int COL2 = 70;  // input fields start here
    private static final int FW = 60;    // default field width
    private static final int ROW_H = 22; // vertical row spacing

    public BuildingScannerScreen(BuildingScannerBlockEntity scanner) {
        super(Component.literal("Building Scanner"));
        this.scanner = scanner;
    }

    /** Package-private accessor for the renderer. */
    BuildingScannerBlockEntity getScanner() { return scanner; }

    // ── init / rebuild ──

    @Override
    protected void init() {
        super.init();
        zoneRows.clear();

        int cx = width / 2;
        lx = cx - 152;
        int y = 10 + scrollOff;

        // ── Category selector ──
        categoryBtn = addRenderableWidget(
                CycleButton.builder((String v) -> Component.literal(v))
                        .withValues(CATEGORIES)
                        .withInitialValue(scanner.getCategory())
                        .displayOnlyValue()
                        .create(cx - 60, y, 120, 20, Component.literal("Type"),
                                (btn, val) -> { scanner.setCategory(val); syncToServer(); needsRebuild = true; })
        );
        y += 28;

        // ── Boundary section ──
        addSectionHeader(y, "Boundary");
        y += 14;
        boundaryMinY = y + 22; // reserve line for "Min" label above corner 1
        corner2Y = boundaryMinY + ROW_H;
        sizeInfoY = boundaryMinY + 4;
        y = corner2Y + ROW_H + 6;

        // Min row
        minX = mkEdit(lx + COL2, boundaryMinY, FW, String.valueOf(scanner.getBoundaryMin().x()), s -> syncBoundary());
        minY = mkEdit(lx + COL2 + FW + 4, boundaryMinY, FW, String.valueOf(scanner.getBoundaryMin().y()), s -> syncBoundary());
        minZ = mkEdit(lx + COL2 + (FW + 4) * 2, boundaryMinY, FW, String.valueOf(scanner.getBoundaryMin().z()), s -> syncBoundary());

        // Max row
        maxX = mkEdit(lx + COL2, corner2Y, FW, String.valueOf(scanner.getBoundaryMax().x()), s -> syncBoundary());
        maxY = mkEdit(lx + COL2 + FW + 4, corner2Y, FW, String.valueOf(scanner.getBoundaryMax().y()), s -> syncBoundary());
        maxZ = mkEdit(lx + COL2 + (FW + 4) * 2, corner2Y, FW, String.valueOf(scanner.getBoundaryMax().z()), s -> syncBoundary());

        // ── Door section ──
        addSectionHeader(y, "Door Offset");
        y += 14;
        doorEditY = y + ROW_H - 4;
        y = doorEditY + ROW_H + 6;

        doorX = mkEdit(lx + COL2, doorEditY, FW, loadDoorStr(0), s -> onDoorChanged());
        doorY = mkEdit(lx + COL2 + FW + 4, doorEditY, FW, loadDoorStr(1), s -> onDoorChanged());
        doorZ = mkEdit(lx + COL2 + (FW + 4) * 2, doorEditY, FW, loadDoorStr(2), s -> onDoorChanged());
        addRenderableWidget(Button.builder(Component.literal("Clear"), b -> {
                    scanner.setDoorOffset(null);
                    doorX.setValue(""); doorY.setValue(""); doorZ.setValue("");
                    syncToServer();
                })
                .bounds(lx + COL2 + (FW + 4) * 3 + 8, doorEditY, 50, 20).build());

        // ── Interact zones section ──
        addSectionHeader(y, "Interact Zones (" + scanner.getInteractZones().size() + ")");
        y += 14;
        zoneHeaderY = y - 14;

        addRenderableWidget(Button.builder(Component.literal("+ Add Zone"), b -> {
                    scanner.addInteractZone(new BoundaryBox(
                            BlockOffset.of(-1, 0, -1), BlockOffset.of(1, 0, 1)));
                    syncToServer();
                    needsRebuild = true;
                })
                .bounds(lx + COL2 + 200, y - 11, 80, 18).build());

        List<BoundaryBox> zones = scanner.getInteractZones();
        for (int i = 0; i < zones.size(); i++) {
            ZoneRow row = new ZoneRow(i, lx + 4, y);
            zoneRows.add(row);
            y += ROW_H + 2;
        }
        y += 6;

        // ── Metadata section ──
        addSectionHeader(y, "Metadata");
        y += 14;
        metaStartY = y - 14;

        // ID + Name on the same line
        metaId = mkEdit(lx + 20, y, 130, scanner.getBuildingId(),
                s -> { scanner.setBuildingId(s); syncToServer(); });
        metaName = mkEdit(lx + 180, y, 120, scanner.getDisplayName(),
                s -> { scanner.setDisplayName(s); syncToServer(); });
        y += ROW_H + 2;

        // Comfort / Magic / Wonder on the same line
        metaLabelY = y - 4;
        metaComfort = mkNumEdit(lx + COL2, metaLabelY, FW, scanner.getComfort(),
                s -> { scanner.setComfort(intOrZero(s)); syncToServer(); });
        metaMagic = mkNumEdit(lx + COL2 + FW + 12, metaLabelY, FW, scanner.getMagic(),
                s -> { scanner.setMagic(intOrZero(s)); syncToServer(); });
        metaWonder = mkNumEdit(lx + COL2 + (FW + 12) * 2, metaLabelY, FW, scanner.getWonder(),
                s -> { scanner.setWonder(intOrZero(s)); syncToServer(); });
        y = metaLabelY + ROW_H + 10;

        // ── Unlock requirement section ──
        addSectionHeader(y, "Unlock Requirement");
        y += 14;
        unlockY = y - 14;

        unlockLevel = mkNumEdit(lx + COL2, y, FW, scanner.getUnlockMinLevel(),
                s -> { scanner.setUnlockMinLevel(intOrZero(s)); syncToServer(); });
        y += ROW_H + 10;

        // ── Presets section ──
        addSectionHeader(y, "Presets");
        y += 14;
        presetY = y - 14;

        presetNameEdit = mkEdit(lx + COL2, y, 100, "", null);
        addRenderableWidget(Button.builder(Component.literal("Save"), b -> {
                    String name = presetNameEdit.getValue();
                    if (!name.isBlank()) {
                        ScannerPresetStore.savePreset(name, capturePresetData());
                        needsRebuild = true;
                    }
                })
                .bounds(lx + COL2 + 104, y, 40, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Load"), b -> {
                    String name = presetNameEdit.getValue();
                    if (!name.isBlank()) {
                        CompoundTag data = ScannerPresetStore.loadPreset(name);
                        if (data != null) {
                            applyPresetData(data);
                            syncToServer();
                            needsRebuild = true;
                        }
                    }
                })
                .bounds(lx + COL2 + 148, y, 40, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Del"), b -> {
                    String name = presetNameEdit.getValue();
                    if (!name.isBlank()) {
                        ScannerPresetStore.deletePreset(name);
                        needsRebuild = true;
                    }
                })
                .bounds(lx + COL2 + 192, y, 40, 18).build());
        y += ROW_H + 2;

        // Preset name quick-load buttons
        List<String> presetNames = ScannerPresetStore.listPresets();
        int px = lx + COL2;
        for (String pn : presetNames) {
            int bw = Math.min(font.width(pn) + 10, 120);
            addRenderableWidget(Button.builder(Component.literal(pn), btn -> {
                        CompoundTag data = ScannerPresetStore.loadPreset(btn.getMessage().getString());
                        if (data != null) {
                            applyPresetData(data);
                            syncToServer();
                            needsRebuild = true;
                        }
                    })
                    .bounds(px, y, bw, 16).build());
            px += bw + 4;
            if (px > width - 40) break;
        }
        y += 20;

        // ── Category-specific sections ──
        String cat = scanner.getCategory();

        if ("shop".equals(cat)) {
            addSectionHeader(y, "Shop Config");
            y += 14;
            shopCatY = y - 14;

            shopProfitRate = mkNumEdit(lx + COL2, y, FW,
                    (int) (scanner.getShopProfitRate() * 100),
                    s -> {
                        double v = intOrZero(s) / 100.0;
                        scanner.setShopProfitRate(v);
                        syncToServer();
                    });
            y += ROW_H + 2;

            shopDuration = mkNumEdit(lx + COL2, y, FW, scanner.getShopInteractionDurationTicks(),
                    s -> { scanner.setShopInteractionDurationTicks(intOrZero(s)); syncToServer(); });
            y += ROW_H + 10;
            svcCatY = 0; // not used
        } else {
            shopProfitRate = null;
            shopDuration = null;
            shopCatY = 0;
        }

        if ("service".equals(cat)) {
            addSectionHeader(y, "Service Config");
            y += 14;
            svcCatY = y - 14;

            serviceEnergy = mkNumEdit(lx + COL2, y, FW, scanner.getServiceEnergyPerUse(),
                    s -> { scanner.setServiceEnergyPerUse(intOrZero(s)); syncToServer(); });
            y += ROW_H + 2;

            serviceMaxOcc = mkNumEdit(lx + COL2, y, FW, scanner.getServiceMaxOccupancy(),
                    s -> { scanner.setServiceMaxOccupancy(intOrZero(s)); syncToServer(); });
            y += ROW_H + 2;

            serviceDuration = mkNumEdit(lx + COL2, y, FW, scanner.getServiceInteractionDurationTicks(),
                    s -> { scanner.setServiceInteractionDurationTicks(intOrZero(s)); syncToServer(); });
            y += ROW_H + 10;
        } else {
            serviceEnergy = null;
            serviceMaxOcc = null;
            serviceDuration = null;
            if (!"shop".equals(cat)) svcCatY = 0;
        }

        // ── Export section ──
        addSectionHeader(y, "Export");
        y += 16;
        exportBtnY = y - 14;
        exportResultY = exportBtnY + ROW_H + 4;

        addRenderableWidget(Button.builder(Component.literal("Scan Building"), b -> doScan())
                .bounds(lx + 10, exportBtnY, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Export JSON"), b -> doExport())
                .bounds(lx + 120, exportBtnY, 100, 20).build());

        // Compute max scroll — generous buffer so the user can always
        // scroll well past the bottom to see everything.
        int bottom = exportResultY + 600;
        int visibleHeight = height - 40;
        maxScroll = Math.min(0, visibleHeight - bottom);
    }

    /** Draw a bold section header at the given Y. Returns y + 14 for content. */
    private void addSectionHeader(int y, String title) {
        // Subclasses don't draw at init time; this is a layout marker only.
        // Rendering is done in render().
    }

    // ── Widget creation helpers ──

    private EditBox mkEdit(int x, int y, int w, String val, Consumer<String> r) {
        EditBox box = new EditBox(font, x, y, w, 18, Component.empty());
        box.setValue(val);
        box.setResponder(r);
        return addRenderableWidget(box);
    }

    private EditBox mkNumEdit(int x, int y, int w, int val, Consumer<String> r) {
        EditBox box = new EditBox(font, x, y, w, 18, Component.empty());
        box.setFilter(s -> s.matches("\\d*"));
        box.setValue(String.valueOf(val));
        box.setResponder(r);
        return addRenderableWidget(box);
    }

    // ── Zone row inner class ──

    private class ZoneRow {
        final int index;
        final EditBox[] min = new EditBox[3];
        final EditBox[] max = new EditBox[3];

        ZoneRow(int idx, int zx, int zy) {
            this.index = idx;
            int zw = 28;
            BoundaryBox zone = scanner.getInteractZones().get(idx);

            // "#N" label
            int labelW = 20;
            EditBox label = new EditBox(font, zx, zy, labelW, 16, Component.empty());
            label.setValue("#" + idx);
            label.setEditable(false);
            label.setFocused(false);
            addRenderableWidget(label);

            int mx = zx + 24;
            // "min" label + x,y,z
            min[0] = mkZoneEdit(mx, zy, zw, zone.min().x(), () -> updateZone(idx));
            min[1] = mkZoneEdit(mx + zw + 2, zy, zw, zone.min().y(), () -> updateZone(idx));
            min[2] = mkZoneEdit(mx + (zw + 2) * 2, zy, zw, zone.min().z(), () -> updateZone(idx));

            int mx2 = mx + (zw + 2) * 3 + 8;
            // max x,y,z
            max[0] = mkZoneEdit(mx2, zy, zw, zone.max().x(), () -> updateZone(idx));
            max[1] = mkZoneEdit(mx2 + zw + 2, zy, zw, zone.max().y(), () -> updateZone(idx));
            max[2] = mkZoneEdit(mx2 + (zw + 2) * 2, zy, zw, zone.max().z(), () -> updateZone(idx));

            addRenderableWidget(Button.builder(Component.literal("×"), b -> {
                        scanner.removeInteractZone(idx);
                        syncToServer();
                        needsRebuild = true;
                    })
                    .bounds(mx2 + (zw + 2) * 3 + 6, zy, 18, 16).build());
        }
    }

    private EditBox mkZoneEdit(int x, int y, int w, int val, Runnable onChange) {
        EditBox box = new EditBox(font, x, y, w, 16, Component.empty());
        box.setMaxLength(6);
        box.setFilter(s -> s.matches("-?\\d{0,6}"));
        box.setValue(String.valueOf(val));
        box.setResponder(s -> onChange.run());
        return addRenderableWidget(box);
    }

    private void updateZone(int idx) {
        if (idx >= scanner.getInteractZones().size()) return;
        ZoneRow row = zoneRows.get(idx);
        scanner.updateInteractZone(idx, new BoundaryBox(
                BlockOffset.of(intOrZero(row.min[0]), intOrZero(row.min[1]), intOrZero(row.min[2])),
                BlockOffset.of(intOrZero(row.max[0]), intOrZero(row.max[1]), intOrZero(row.max[2]))
        ));
        syncToServer();
    }

    // ── Deferred rebuild flag (set from widget handlers to avoid CME) ──
    private boolean needsRebuild = false;

    @Override
    public void tick() {
        if (needsRebuild) {
            needsRebuild = false;
            rebuild();
        }
    }

    // ── Scrolling ──

    @Override
    public boolean mouseScrolled(double mx, double my, double deltaX, double deltaY) {
        if (deltaY != 0) {
            scrollOff = Math.max(maxScroll, Math.min(0, scrollOff + (int) (deltaY * 20)));
            rebuild();
        }
        return true;
    }

    // ── Rebuild widgets (after zone add/remove or scroll) ──

    private void rebuild() {
        super.clearWidgets();
        zoneRows.clear();
        init();
    }

    // ── Boundary helpers ──

    private void syncBoundary() {
        scanner.setBoundary(
                BlockOffset.of(intOrZero(minX), intOrZero(minY), intOrZero(minZ)),
                BlockOffset.of(
                        Math.max(intOrZero(maxX), intOrZero(minX) + 1),
                        Math.max(intOrZero(maxY), intOrZero(minY) + 1),
                        Math.max(intOrZero(maxZ), intOrZero(minZ) + 1)));
        syncToServer();
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

    // ── Door change ──

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

    // ── Scan & Export ──

    private void doScan() {
        BlockPos wMin = scanner.getWorldMin();
        BlockPos wMax = scanner.getWorldMax();
        if (wMin == null || wMax == null) {
            scanResult = Component.literal("No boundary defined");
            return;
        }
        var level = scanner.getLevel();
        if (level == null) return;

        int count = 0;
        for (int x = wMin.getX(); x <= wMax.getX(); x++) {
            for (int y = wMin.getY(); y <= wMax.getY(); y++) {
                for (int z = wMin.getZ(); z <= wMax.getZ(); z++) {
                    var state = level.getBlockState(new BlockPos(x, y, z));
                    if (!state.isAir()) count++;
                }
            }
        }
        scanner.setScanned(true);
        scanResult = Component.literal("Scanned " + count + " non-air blocks");
        syncToServer();
    }

    private void doExport() {
        scanner.setScanned(true);
        com.wsteam.wandscape.shared.log.Log.info("ScannerScreen", "Export requested for building: {}", scanner.getBuildingId());
        scanResult = Component.literal("Export stub — check server console");
        syncToServer();
    }

    // ── Network sync ──

    private void syncToServer() {
        if (scanner.getLevel() == null || scanner.getLevel().isClientSide) {
            CompoundTag tag = scanner.getUpdateTag(scanner.getLevel().registryAccess());
            PacketDistributor.sendToServer(new BuildingScannerSyncPacket(scanner.getBlockPos(), tag));
        }
    }

    // ── Render ──

    @Override
    public void renderBackground(GuiGraphics gui, int mx, int my, float pt) {
        renderTransparentBackground(gui);
    }

    @Override
    public void render(GuiGraphics gui, int mx, int my, float pt) {
        renderBackground(gui, mx, my, pt);

        // ── Section headers & labels ──

        // Boundary
        drawHdr(gui, "Boundary", lx, boundaryMinY - 14);
        drawLbl(gui, "Min", lx + COL2, boundaryMinY - 10);
        drawLbl(gui, "Max", lx + COL2, corner2Y - 10);
        // Size summary
        int dx = intOrZero(maxX) - intOrZero(minX) + 1;
        int dy = intOrZero(maxY) - intOrZero(minY) + 1;
        int dz = intOrZero(maxZ) - intOrZero(minZ) + 1;
        gui.drawString(font, dx + " × " + dy + " × " + dz, lx + COL2 + (FW + 4) * 3 + 8, boundaryMinY + 4, 0x888888);

        // Door
        drawHdr(gui, "Door Offset", lx, doorEditY - 14);
        drawLbl(gui, "X", lx + COL2, doorEditY - 10);
        drawLbl(gui, "Y", lx + COL2 + FW + 4, doorEditY - 10);
        drawLbl(gui, "Z", lx + COL2 + (FW + 4) * 2, doorEditY - 10);

        // Interact zones
        drawHdr(gui, "Interact Zones (" + scanner.getInteractZones().size() + ")", lx, zoneHeaderY);

        // Metadata
        drawHdr(gui, "Metadata", lx, metaStartY);
        drawLbl(gui, "ID", lx + 4, metaStartY + 14);
        drawLbl(gui, "Name", lx + 164, metaStartY + 14);
        drawLbl(gui, "Comfort", lx + COL2, metaLabelY - 10);
        drawLbl(gui, "Magic", lx + COL2 + FW + 12, metaLabelY - 10);
        drawLbl(gui, "Wonder", lx + COL2 + (FW + 12) * 2, metaLabelY - 10);

        // Unlock requirement
        drawHdr(gui, "Unlock Requirement", lx, unlockY);
        drawLbl(gui, "Min Level", lx + COL2, unlockY + ROW_H - 4);

        // Presets
        drawHdr(gui, "Presets", lx, presetY);

        // Category-specific
        String cat = scanner.getCategory();
        if ("shop".equals(cat)) {
            drawHdr(gui, "Shop Config", lx, shopCatY);
            drawLbl(gui, "Profit%", lx + COL2, shopCatY + ROW_H - 4);
            drawLbl(gui, "Duration (tick)", lx + COL2, shopCatY + ROW_H * 2);
        } else if ("service".equals(cat)) {
            drawHdr(gui, "Service Config", lx, svcCatY);
            drawLbl(gui, "Energy/use", lx + COL2, svcCatY + ROW_H - 4);
            drawLbl(gui, "Max Occupancy", lx + COL2, svcCatY + ROW_H * 2 - 2);
            drawLbl(gui, "Duration (tick)", lx + COL2, svcCatY + ROW_H * 3 - 2);
        }

        // Export
        drawHdr(gui, "Export", lx, exportBtnY - 14);
        gui.drawString(font, scanResult, lx + 230, exportBtnY + 6, 0x888888);

        // ── Widgets ──
        super.render(gui, mx, my, pt);
    }

    /** Draw bold section header. */
    private void drawHdr(GuiGraphics gui, String text, int x, int y) {
        gui.drawString(font, Component.literal("§l" + text), x, y, 0xdddddd);
    }

    /** Draw a label. */
    private void drawLbl(GuiGraphics gui, String text, int x, int y) {
        gui.drawString(font, text, x, y, 0xaaaaaa);
    }

    // ── Utilities ──

    private static int intOrZero(EditBox box) {
        if (box == null) return 0;
        String s = box.getValue();
        if (s == null || s.isEmpty()) return 0;
        try { return Integer.parseInt(s); }
        catch (NumberFormatException e) { return 0; }
    }

    private static int intOrZero(String s) {
        if (s == null || s.isEmpty()) return 0;
        try { return Integer.parseInt(s); }
        catch (NumberFormatException e) { return 0; }
    }

    // ── Preset helpers ──

    /** Capture all config fields from the current scanner state into a CompoundTag. */
    private CompoundTag capturePresetData() {
        CompoundTag tag = new CompoundTag();
        // boundary
        BlockOffset bMin = scanner.getBoundaryMin();
        BlockOffset bMax = scanner.getBoundaryMax();
        tag.putIntArray("boundary_min", new int[]{bMin.x(), bMin.y(), bMin.z()});
        tag.putIntArray("boundary_max", new int[]{bMax.x(), bMax.y(), bMax.z()});
        // door
        BlockOffset door = scanner.getDoorOffset();
        if (door != null) {
            tag.putIntArray("door_offset", new int[]{door.x(), door.y(), door.z()});
        }
        // interact zones
        ListTag zones = new ListTag();
        for (BoundaryBox zone : scanner.getInteractZones()) {
            CompoundTag zt = new CompoundTag();
            zt.putIntArray("min", new int[]{zone.min().x(), zone.min().y(), zone.min().z()});
            zt.putIntArray("max", new int[]{zone.max().x(), zone.max().y(), zone.max().z()});
            zones.add(zt);
        }
        tag.put("interact_zones", zones);
        // category & meta
        tag.putString("category", scanner.getCategory());
        tag.putInt("comfort", scanner.getComfort());
        tag.putInt("magic", scanner.getMagic());
        tag.putInt("wonder", scanner.getWonder());
        // unlock
        tag.putInt("unlock_min_level", scanner.getUnlockMinLevel());
        // shop
        tag.putDouble("shop_profit", scanner.getShopProfitRate());
        tag.putInt("shop_duration", scanner.getShopInteractionDurationTicks());
        // service
        tag.putInt("service_energy", scanner.getServiceEnergyPerUse());
        tag.putInt("service_max_occ", scanner.getServiceMaxOccupancy());
        tag.putInt("service_duration", scanner.getServiceInteractionDurationTicks());
        return tag;
    }

    /** Restore all config fields from a preset CompoundTag into the scanner BE. */
    private void applyPresetData(CompoundTag tag) {
        // boundary
        int[] bMin = tag.getIntArray("boundary_min");
        int[] bMax = tag.getIntArray("boundary_max");
        if (bMin.length == 3 && bMax.length == 3) {
            scanner.setBoundary(
                    BlockOffset.of(bMin[0], bMin[1], bMin[2]),
                    BlockOffset.of(Math.max(bMax[0], bMin[0] + 1),
                            Math.max(bMax[1], bMin[1] + 1),
                            Math.max(bMax[2], bMin[2] + 1)));
        }
        // door
        if (tag.contains("door_offset", Tag.TAG_INT_ARRAY)) {
            int[] d = tag.getIntArray("door_offset");
            scanner.setDoorOffset(d.length == 3 ? BlockOffset.of(d[0], d[1], d[2]) : null);
        } else {
            scanner.setDoorOffset(null);
        }
        // interact zones
        scanner.clearInteractZones();
        if (tag.contains("interact_zones", Tag.TAG_LIST)) {
            for (int i = 0; i < tag.getList("interact_zones", Tag.TAG_COMPOUND).size(); i++) {
                CompoundTag zt = tag.getList("interact_zones", Tag.TAG_COMPOUND).getCompound(i);
                int[] zMin = zt.getIntArray("min");
                int[] zMax = zt.getIntArray("max");
                if (zMin.length == 3 && zMax.length == 3) {
                    scanner.addInteractZone(new BoundaryBox(
                            BlockOffset.of(zMin[0], zMin[1], zMin[2]),
                            BlockOffset.of(zMax[0], zMax[1], zMax[2])));
                }
            }
        }
        // category
        scanner.setCategory(tag.getString("category"));
        // meta
        scanner.setComfort(tag.getInt("comfort"));
        scanner.setMagic(tag.getInt("magic"));
        scanner.setWonder(tag.getInt("wonder"));
        // unlock
        scanner.setUnlockMinLevel(Math.max(1, tag.getInt("unlock_min_level")));
        // shop
        scanner.setShopProfitRate(tag.getDouble("shop_profit"));
        scanner.setShopInteractionDurationTicks(tag.getInt("shop_duration"));
        // service
        scanner.setServiceEnergyPerUse(tag.getInt("service_energy"));
        scanner.setServiceMaxOccupancy(tag.getInt("service_max_occ"));
        scanner.setServiceInteractionDurationTicks(tag.getInt("service_duration"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
