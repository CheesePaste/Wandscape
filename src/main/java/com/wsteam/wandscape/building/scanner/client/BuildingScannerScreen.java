package com.wsteam.wandscape.building.scanner.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.wsteam.wandscape.building.data.BlockOffset;
import com.wsteam.wandscape.building.data.BuildingConfig.BoundaryBox;
import com.wsteam.wandscape.building.scanner.BuildingScannerBlockEntity;
import com.wsteam.wandscape.building.scanner.BuildingScannerBlockEntity.ShopGoodData;
import com.wsteam.wandscape.building.scanner.network.BuildingScannerSyncPacket;
import com.wsteam.wandscape.building.scanner.network.BuildingScannerExportPacket;

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

    // ── Structure Block Mode (SAVE vs CORNER) & Name ──
    private CycleButton<BuildingScannerBlockEntity.BlockMode> blockModeBtn;
    private EditBox structureNameEdit;

    // ── Target Mode (Building vs Road) ──
    private CycleButton<BuildingScannerBlockEntity.TargetMode> targetModeBtn;

    // ── Category ──
    private CycleButton<String> categoryBtn;
    private static final List<String> CATEGORIES = List.of(
            "basic", "government", "node", "storage", "workstation", "crafting_station",
            "potion_station", "tavern", "shop", "service", "decoration", "wonder"
    );

    // ── Door offset ──
    private EditBox doorX, doorY, doorZ;

    // ── Tourist interact zones ──
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

    // ── Elements list for selectors ──
    private static final List<String> ELEMENTS = List.of("earth", "wood", "water", "fire", "metal", "wind", "dark");

    // ── Maintenance cost ──
    private int maintCostY;
    private final List<CostRow> maintRows = new ArrayList<>();

    // ── Node config fields (category=node) ──
    private CycleButton<String> nodeElemBtn;
    private EditBox nodeAmount, nodeChannel, nodeMana;
    private int nodeCatY;

    // ── Shop goods rows (category=shop) ──
    private int goodsCatY;
    private final List<GoodRow> goodRows = new ArrayList<>();

    // ── Service element output rows (category=service) ──
    private int elemOutY;
    private final List<CostRow> elemOutRows = new ArrayList<>();

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

        addRenderableWidget(new com.wsteam.wandscape.shared.ui.component.HelpButton(this.width - 24, 6, 16, 16, this::openHelpDocument));

        int cx = width / 2;
        lx = cx - 152;
        int y = 10 + scrollOff;

        // ── Block Mode selector (SAVE vs CORNER) ──
        blockModeBtn = addRenderableWidget(
                CycleButton.builder((BuildingScannerBlockEntity.BlockMode v) -> Component.literal("Mode: " + v.name()))
                        .withValues(BuildingScannerBlockEntity.BlockMode.values())
                        .withInitialValue(scanner.getBlockMode())
                        .displayOnlyValue()
                        .create(cx - 150, y, 90, 20, Component.literal("Mode"),
                                (btn, val) -> { scanner.setBlockMode(val); syncToServer(); needsRebuild = true; })
        );

        // ── Structure Name input ──
        structureNameEdit = mkEdit(cx - 50, y, 190, scanner.getStructureName(), s -> {
            scanner.setStructureName(s);
            syncToServer();
        });
        y += 28;

        if (scanner.getBlockMode() == BuildingScannerBlockEntity.BlockMode.CORNER) {
            // CORNER mode: simplified UI, only mode & structure name needed
            addRenderableWidget(Button.builder(Component.literal("Done"), b -> this.onClose())
                    .bounds(cx - 50, y + 20, 100, 20).build());
            return;
        }

        // ── Target Mode & Category ──
        targetModeBtn = addRenderableWidget(
                CycleButton.builder((BuildingScannerBlockEntity.TargetMode v) -> Component.literal("Target: " + v.name()))
                        .withValues(BuildingScannerBlockEntity.TargetMode.values())
                        .withInitialValue(scanner.getTargetMode())
                        .displayOnlyValue()
                        .create(cx - 150, y, 110, 20, Component.literal("Target"),
                                (btn, val) -> { scanner.setTargetMode(val); syncToServer(); needsRebuild = true; })
        );

        categoryBtn = addRenderableWidget(
                CycleButton.builder((String v) -> Component.literal(v))
                        .withValues(CATEGORIES)
                        .withInitialValue(scanner.getCategory())
                        .displayOnlyValue()
                        .create(cx - 35, y, 95, 20, Component.literal("Type"),
                                (btn, val) -> { scanner.setCategory(val); syncToServer(); needsRebuild = true; })
        );

        addRenderableWidget(Button.builder(Component.literal("Detect Corners"), b -> {
                    syncToServer();
                    needsRebuild = true;
                })
                .bounds(cx + 65, y, 95, 20).build());
        y += 28;

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

        // ── Tourist interact zones section ──
        addSectionHeader(y, "Tourist Interact Zones (" + scanner.getTouristInteractZones().size() + ")");
        y += 14;
        zoneHeaderY = y - 14;

        addRenderableWidget(Button.builder(Component.literal("+ Add Zone"), b -> {
                    scanner.addTouristInteractZone(new BoundaryBox(
                            BlockOffset.of(-1, 0, -1), BlockOffset.of(1, 0, 1)));
                    syncToServer();
                    needsRebuild = true;
                })
                .bounds(lx + COL2 + 200, y - 11, 80, 18).build());

        List<BoundaryBox> zones = scanner.getTouristInteractZones();
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

        // ── Maintenance Cost section ──
        addSectionHeader(y, "Maintenance Cost");
        y += 14;
        maintCostY = y - 14;

        maintRows.clear();
        int my = y;
        int mi = 0;
        for (var entry : scanner.getMaintenanceCost().entrySet()) {
            String elem = entry.getKey();
            int ey = my + mi * 22;
            CostRow cr = new CostRow(lx + COL2, ey, elem, entry.getValue(),
                    () -> { scanner.removeMaintenanceCost(elem); syncToServer(); needsRebuild = true; },
                    () -> syncMaintCost());
            maintRows.add(cr);
            mi++;
        }
        int addMaintY = my + mi * 22;
        if (mi == 0) addMaintY = my;
        addRenderableWidget(Button.builder(Component.literal("+"), b -> {
                    String next = ELEMENTS.stream()
                            .filter(e -> !scanner.getMaintenanceCost().containsKey(e))
                            .findFirst().orElse("earth");
                    scanner.addMaintenanceCost(next, 1);
                    syncToServer();
                    needsRebuild = true;
                })
                .bounds(lx + COL2, addMaintY, 30, 18).build());
        y = addMaintY + ROW_H + 6;

        // ── Node Config section (only for category=node) ──
        String cat = scanner.getCategory();
        if ("node".equals(cat)) {
            // Use default blueprint for node
            if (scanner.getNodeBlueprint().isBlank()) {
                scanner.setNodeBlueprint("node:gather");
            }
            addSectionHeader(y, "Node Config");
            y += 14;
            nodeCatY = y - 14;

            // Element selector (CycleButton, like maintenance cost rows)
            String currentElem = ELEMENTS.contains(scanner.getNodeElement())
                    ? scanner.getNodeElement() : "earth";
            nodeElemBtn = addRenderableWidget(
                    CycleButton.builder((String v) -> Component.literal(v))
                            .withValues(ELEMENTS)
                            .withInitialValue(currentElem)
                            .displayOnlyValue()
                            .create(lx + COL2, y, 56, 18, Component.literal("Element"),
                                    (b, val) -> { scanner.setNodeElement(val); syncToServer(); }));
            y += ROW_H;

            nodeAmount = mkNumEdit(lx + COL2, y, FW, scanner.getNodeAmountPerHarvest(),
                    s -> { scanner.setNodeAmountPerHarvest(intOrZero(s)); syncToServer(); });
            y += ROW_H;

            nodeChannel = mkNumEdit(lx + COL2, y, FW, scanner.getNodeChannelTicks(),
                    s -> { scanner.setNodeChannelTicks(intOrZero(s)); syncToServer(); });
            y += ROW_H;

            nodeMana = mkNumEdit(lx + COL2, y, FW, scanner.getNodeManaCost(),
                    s -> { scanner.setNodeManaCost(intOrZero(s)); syncToServer(); });
            y += ROW_H + 6;
        } else {
            nodeElemBtn = null; nodeAmount = null; nodeChannel = null; nodeMana = null;
            nodeCatY = 0;
        }

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
        // cat is already declared above in the node config section

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
            y += ROW_H + 6;
            svcCatY = 0; // not used

            // ── Shop Goods section ──
            addSectionHeader(y, "Shop Goods");
            y += 14;
            goodsCatY = y - 14;

            goodRows.clear();
            int gx = lx + COL2;
            int gy = y;
            for (int gi = 0; gi < scanner.getShopGoods().size(); gi++) {
                GoodRow gr = new GoodRow(gi, gx, gy);
                goodRows.add(gr);
                gy += 40;
            }
            addRenderableWidget(Button.builder(Component.literal("+ Add Good"), b -> {
                        scanner.addShopGood(new ShopGoodData("minecraft:air", 0, 0, 0));
                        syncToServer();
                        needsRebuild = true;
                    })
                    .bounds(gx, gy, 80, 18).build());
            y = gy + ROW_H + 6;
        } else {
            shopProfitRate = null;
            shopDuration = null;
            shopCatY = 0;
            goodsCatY = 0;
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
            y += ROW_H + 6;

            // ── Element Output section ──
            addSectionHeader(y, "Element Output");
            y += 14;
            elemOutY = y - 14;

            elemOutRows.clear();
            int eoy = y;
            int eoi = 0;
            for (var entry : scanner.getServiceElementOutput().entrySet()) {
                String elem = entry.getKey();
                int ey = eoy + eoi * 22;
                CostRow cr = new CostRow(lx + COL2, ey, elem, entry.getValue(),
                        () -> { scanner.removeServiceElementOutput(elem); syncToServer(); needsRebuild = true; },
                        () -> syncElemOut());
                elemOutRows.add(cr);
                eoi++;
            }
            if (eoi == 0) eoy = y;
            addRenderableWidget(Button.builder(Component.literal("+"), b -> {
                        String next = ELEMENTS.stream()
                                .filter(e -> !scanner.getServiceElementOutput().containsKey(e))
                                .findFirst().orElse("earth");
                        scanner.addServiceElementOutput(next, 1);
                        syncToServer();
                        needsRebuild = true;
                    })
                    .bounds(lx + COL2, y + eoi * 22, 30, 18).build());
            y = y + eoi * 22 + ROW_H + 6;
        } else {
            serviceEnergy = null;
            serviceMaxOcc = null;
            serviceDuration = null;
            if (!"shop".equals(cat)) svcCatY = 0;
            elemOutY = 0;
        }

        // ── Export section ──
        addSectionHeader(y, "Export");
        y += 16;
        exportBtnY = y - 14;
        exportResultY = exportBtnY + ROW_H + 4;

        String btnText = scanner.getTargetMode() == BuildingScannerBlockEntity.TargetMode.ROAD
                ? "Export Road JSON" : "Export Building JSON";
        addRenderableWidget(Button.builder(Component.literal("Scan Area"), b -> doScan())
                .bounds(lx + 5, exportBtnY, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal(btnText), b -> doExport())
                .bounds(lx + 110, exportBtnY, 140, 20).build());

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
            BoundaryBox zone = scanner.getTouristInteractZones().get(idx);

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
                        scanner.removeTouristInteractZone(idx);
                        syncToServer();
                        needsRebuild = true;
                    })
                    .bounds(mx2 + (zw + 2) * 3 + 6, zy, 18, 16).build());
        }
    }

    /** One row of the maintenance-cost or tourist-element-output editor: element selector + amount. */
    private class CostRow {
        final Runnable onChanged;
        final CycleButton<String> elemBtn;
        final EditBox amountBox;
        CostRow(int x, int y, String elem, int amount, Runnable onRemove, Runnable onChanged) {
            this.onChanged = onChanged;
            elemBtn = addRenderableWidget(
                    CycleButton.builder((String v) -> Component.literal(v))
                            .withValues(ELEMENTS)
                            .withInitialValue(ELEMENTS.contains(elem) ? elem : "earth")
                            .displayOnlyValue()
                            .create(x, y, 56, 18, Component.empty(), (b, v) -> onChanged.run()));
            amountBox = mkNumEdit(x + 60, y, 36, amount, s -> onChanged.run());
            if (onRemove != null) {
                addRenderableWidget(Button.builder(Component.literal("×"), b -> onRemove.run())
                        .bounds(x + 100, y, 18, 18).build());
            }
        }
        String element() { return elemBtn.getValue(); }
        int amount() { return intOrZero(amountBox); }
    }

    /** One row of the shop-goods editor. */
    private class GoodRow {
        final int index;
        final EditBox itemIdBox;
        final EditBox gComfort, gMagic, gWonder;
        int yBase;

        GoodRow(int idx, int x, int y) {
            this.index = idx;
            this.yBase = y;
            ShopGoodData good = scanner.getShopGoods().get(idx);
            itemIdBox = mkEdit(x + 4, y, 120, good.itemId(), s -> updateGood());
            gComfort = mkNumEdit(x + 130, y, 28, good.comfort(), s -> updateGood());
            gMagic = mkNumEdit(x + 162, y, 28, good.magic(), s -> updateGood());
            gWonder = mkNumEdit(x + 194, y, 28, good.wonder(), s -> updateGood());
            addRenderableWidget(Button.builder(Component.literal("×"), b -> {
                        scanner.removeShopGood(idx);
                        syncToServer();
                        needsRebuild = true;
                    })
                    .bounds(x + 228, y, 18, 18).build());
        }

        ShopGoodData captureGood() {
            return new ShopGoodData(
                    itemIdBox.getValue(),
                    intOrZero(gComfort), intOrZero(gMagic), intOrZero(gWonder));
        }

        void updateGood() {
            scanner.updateShopGood(index, captureGood());
            syncToServer();
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
        if (idx >= scanner.getTouristInteractZones().size()) return;
        ZoneRow row = zoneRows.get(idx);
        scanner.updateTouristInteractZone(idx, new BoundaryBox(
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
            int newScroll = Math.max(maxScroll, Math.min(0, scrollOff + (int) (deltaY * 20)));
            if (newScroll != scrollOff) {
                scrollOff = newScroll;
                rebuild();
                return true;
            }
        }
        return false;
    }

    // ── Rebuild widgets (after zone add/remove or scroll) ──

    private void rebuild() {
        super.clearWidgets();
        zoneRows.clear();
        init();
    }

    // ── Door helpers ──

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

    // ── Sync helpers for new editors ──

    private void syncMaintCost() {
        Map<String, Integer> map = new HashMap<>();
        for (CostRow row : maintRows) map.put(row.element(), row.amount());
        scanner.setMaintenanceCost(map);
        syncToServer();
    }

    private void syncElemOut() {
        Map<String, Integer> map = new HashMap<>();
        for (CostRow row : elemOutRows) map.put(row.element(), row.amount());
        scanner.setServiceElementOutput(map);
        syncToServer();
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

        BlockPos scannerPos = scanner.getBlockPos();
        int count = 0;
        for (int x = wMin.getX(); x <= wMax.getX(); x++) {
            for (int y = wMin.getY(); y <= wMax.getY(); y++) {
                for (int z = wMin.getZ(); z <= wMax.getZ(); z++) {
                    BlockPos bp = new BlockPos(x, y, z);
                    if (bp.equals(scannerPos)) continue;
                    if (!level.getBlockState(bp).isAir()) count++;
                }
            }
        }
        scanner.setScanned(true);
        scanResult = Component.literal("Scanned " + count + " non-air blocks");
        syncToServer();
    }

    private void doExport() {
        String id = scanner.getBuildingId();
        if (id.isBlank()) {
            scanResult = Component.literal("Set a building ID before exporting");
            return;
        }
        scanner.setScanned(true);
        // Send export request to server
        PacketDistributor.sendToServer(new BuildingScannerExportPacket(scanner.getBlockPos()));
        scanResult = Component.literal("Export requested for '" + id + "' — check server console");
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
        int cx = width / 2;

        // ── Section headers & labels ──

        // Block Mode & Structure Name Labels
        drawLbl(gui, "Mode", cx - 150, 0);
        drawLbl(gui, "Structure Name", cx - 50, 0);

        if (scanner.getBlockMode() == BuildingScannerBlockEntity.BlockMode.CORNER) {
            gui.drawString(font, "§7CORNER Mode: Set Structure Name to match SAVE scanner.", lx + 10, 40, 0xaaaaaa);
            gui.drawString(font, "§7Place this block at the opposite 3D corner of your building.", lx + 10, 56, 0x888888);
            super.render(gui, mx, my, pt);
            return;
        }

        // Boundary size summary (calculated from SAVE <-> CORNER matching)
        BlockOffset bMin = scanner.getBoundaryMin();
        BlockOffset bMax = scanner.getBoundaryMax();
        int dx = bMax.x() - bMin.x() + 1;
        int dy = bMax.y() - bMin.y() + 1;
        int dz = bMax.z() - bMin.z() + 1;
        String bInfo = String.format("Box: Min(%d,%d,%d) ~ Max(%d,%d,%d)  |  Size: %d × %d × %d",
                bMin.x(), bMin.y(), bMin.z(), bMax.x(), bMax.y(), bMax.z(), dx, dy, dz);
        gui.drawString(font, "§e" + bInfo, lx + 4, 32, 0xffff88);

        // Door
        drawHdr(gui, "Door Offset", lx, doorEditY - 14);
        drawLbl(gui, "X", lx + COL2, doorEditY - 10);
        drawLbl(gui, "Y", lx + COL2 + FW + 4, doorEditY - 10);
        drawLbl(gui, "Z", lx + COL2 + (FW + 4) * 2, doorEditY - 10);

        // Tourist interact zones
        drawHdr(gui, "Tourist Interact Zones (" + scanner.getTouristInteractZones().size() + ")", lx, zoneHeaderY);

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

        // Maintenance Cost
        drawHdr(gui, "Maintenance Cost", lx, maintCostY);

        // Node Config
        if ("node".equals(scanner.getCategory())) {
            drawHdr(gui, "Node Config", lx, nodeCatY);
            drawLbl(gui, "Element", lx + COL2, nodeCatY + ROW_H - 4);
            drawLbl(gui, "Amount/Harvest", lx + COL2, nodeCatY + ROW_H * 2 - 4);
            drawLbl(gui, "Channel Ticks", lx + COL2, nodeCatY + ROW_H * 3 - 4);
            drawLbl(gui, "Mana Cost", lx + COL2, nodeCatY + ROW_H * 4 - 4);
        }

        // Presets
        drawHdr(gui, "Presets", lx, presetY);

        // Category-specific
        String cat = scanner.getCategory();
        if ("shop".equals(cat)) {
            drawHdr(gui, "Shop Config", lx, shopCatY);
            drawLbl(gui, "Profit%", lx + COL2, shopCatY + ROW_H - 4);
            drawLbl(gui, "Duration (tick)", lx + COL2, shopCatY + ROW_H * 2 - 4);
            drawHdr(gui, "Shop Goods", lx, goodsCatY);
        } else if ("service".equals(cat)) {
            drawHdr(gui, "Service Config", lx, svcCatY);
            drawLbl(gui, "Energy/use", lx + COL2, svcCatY + ROW_H - 4);
            drawLbl(gui, "Max Occupancy", lx + COL2, svcCatY + ROW_H * 2 - 2);
            drawLbl(gui, "Duration (tick)", lx + COL2, svcCatY + ROW_H * 3 - 2);
            drawHdr(gui, "Element Output", lx, elemOutY);
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
        // tourist interact zones
        ListTag zones = new ListTag();
        for (BoundaryBox zone : scanner.getTouristInteractZones()) {
            CompoundTag zt = new CompoundTag();
            zt.putIntArray("min", new int[]{zone.min().x(), zone.min().y(), zone.min().z()});
            zt.putIntArray("max", new int[]{zone.max().x(), zone.max().y(), zone.max().z()});
            zones.add(zt);
        }
        tag.put("tourist_interact_zones", zones);
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

        // maintenance cost
        ListTag mcList = new ListTag();
        for (var entry : scanner.getMaintenanceCost().entrySet()) {
            CompoundTag et = new CompoundTag();
            et.putString("element", entry.getKey());
            et.putInt("amount", entry.getValue());
            mcList.add(et);
        }
        tag.put("maintenance_cost", mcList);

        // node config
        CompoundTag nc = new CompoundTag();
        nc.putString("blueprint", scanner.getNodeBlueprint());
        nc.putString("element", scanner.getNodeElement());
        nc.putInt("amount_per_harvest", scanner.getNodeAmountPerHarvest());
        nc.putInt("channel_ticks", scanner.getNodeChannelTicks());
        nc.putInt("mana_cost", scanner.getNodeManaCost());
        tag.put("node_config", nc);

        // shop goods
        ListTag gl = new ListTag();
        for (ShopGoodData g : scanner.getShopGoods()) {
            CompoundTag gt = new CompoundTag();
            gt.putString("item_id", g.itemId());
            gt.putInt("comfort", g.comfort());
            gt.putInt("magic", g.magic());
            gt.putInt("wonder", g.wonder());
            gl.add(gt);
        }
        tag.put("shop_goods", gl);

        // service element output
        ListTag so = new ListTag();
        for (var entry : scanner.getServiceElementOutput().entrySet()) {
            CompoundTag et = new CompoundTag();
            et.putString("element", entry.getKey());
            et.putInt("amount", entry.getValue());
            so.add(et);
        }
        tag.put("service_element_output", so);

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
        // tourist interact zones
        scanner.clearTouristInteractZones();
        if (tag.contains("tourist_interact_zones", Tag.TAG_LIST)) {
            for (int i = 0; i < tag.getList("tourist_interact_zones", Tag.TAG_COMPOUND).size(); i++) {
                CompoundTag zt = tag.getList("tourist_interact_zones", Tag.TAG_COMPOUND).getCompound(i);
                int[] zMin = zt.getIntArray("min");
                int[] zMax = zt.getIntArray("max");
                if (zMin.length == 3 && zMax.length == 3) {
                    scanner.addTouristInteractZone(new BoundaryBox(
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

        // maintenance cost
        scanner.setMaintenanceCost(Map.of());
        if (tag.contains("maintenance_cost", Tag.TAG_LIST)) {
            ListTag list = tag.getList("maintenance_cost", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag et = list.getCompound(i);
                scanner.addMaintenanceCost(et.getString("element"), et.getInt("amount"));
            }
        }

        // node config
        if (tag.contains("node_config", Tag.TAG_COMPOUND)) {
            CompoundTag nc = tag.getCompound("node_config");
            scanner.setNodeBlueprint(nc.getString("blueprint"));
            scanner.setNodeElement(nc.getString("element"));
            scanner.setNodeAmountPerHarvest(nc.getInt("amount_per_harvest"));
            scanner.setNodeChannelTicks(nc.getInt("channel_ticks"));
            scanner.setNodeManaCost(nc.getInt("mana_cost"));
        }

        // shop goods
        scanner.clearShopGoods();
        if (tag.contains("shop_goods", Tag.TAG_LIST)) {
            ListTag gl = tag.getList("shop_goods", Tag.TAG_COMPOUND);
            for (int i = 0; i < gl.size(); i++) {
                CompoundTag gt = gl.getCompound(i);
                scanner.addShopGood(new ShopGoodData(
                        gt.getString("item_id"),
                        gt.getInt("comfort"), gt.getInt("magic"), gt.getInt("wonder")));
            }
        }

        // service element output
        scanner.setServiceElementOutput(Map.of());
        if (tag.contains("service_element_output", Tag.TAG_LIST)) {
            ListTag so = tag.getList("service_element_output", Tag.TAG_COMPOUND);
            for (int i = 0; i < so.size(); i++) {
                CompoundTag et = so.getCompound(i);
                scanner.addServiceElementOutput(et.getString("element"), et.getInt("amount"));
            }
        }
    }

    private void openHelpDocument() {
        if (minecraft != null) {
            String content = com.wsteam.wandscape.shared.ui.markdown.navigation.DocumentLoader.loadMarkdown("scanner_guide");
            var screen = new com.wsteam.wandscape.shared.ui.guide.GuideTestScreen(this, content, "scanner_guide");
            minecraft.setScreen(screen);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_H || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_F1) {
            openHelpDocument();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
