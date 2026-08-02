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
import com.wsteam.wandscape.building.scanner.network.BuildingScannerExportPacket;
import com.wsteam.wandscape.building.scanner.network.BuildingScannerSyncPacket;
import com.wsteam.wandscape.shared.ui.component.MedievalButton;
import com.wsteam.wandscape.shared.ui.component.MedievalScreen;
import com.wsteam.wandscape.shared.ui.theme.MedievalColors;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Scanner GUI built on MedievalScreen MINIMAL theme.
 * Uses MedievalButton, MedievalColors, and inset dark fields for full theme harmony.
 */
public class BuildingScannerScreen extends MedievalScreen {

    private static final int PW = 340;
    private static final int PH = 250;

    private final BuildingScannerBlockEntity scanner;

    // ── Structure Block Mode & Name ──
    private MedievalButton blockModeBtn;
    private EditBox structureNameEdit;

    // ── Target Mode (Building vs Road) ──
    private MedievalButton targetModeBtn;

    // ── Category ──
    private MedievalButton categoryBtn;
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
    private MedievalButton nodeElemBtn;
    private String currentNodeElem;
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
    private int doorEditY;
    private int zoneHeaderY;
    private int metaStartY, metaLabelY;
    private int unlockY;
    private int shopCatY, svcCatY;
    private int exportBtnY;

    // ── Field Background Inset Rectangles ──
    private record FieldRect(int x, int y, int w, int h) {}
    private final List<FieldRect> insetFields = new ArrayList<>();

    // ── Column layout constants ──
    private static final int COL2 = 70;  // input fields start here
    private static final int FW = 60;    // default field width
    private static final int ROW_H = 22; // vertical row spacing

    public BuildingScannerScreen(BuildingScannerBlockEntity scanner) {
        super(Component.literal("Building Scanner"), PW, PH);
        setTitleBar("结构扫描器");
        this.showCloseButton = true;
        this.showHelpButton = true;
        this.helpDocumentPath = "scanner_guide";
        this.scanner = scanner;
    }

    /** Package-private accessor for the renderer. */
    BuildingScannerBlockEntity getScanner() { return scanner; }

    // ── init / rebuild ──

    @Override
    protected void init() {
        super.init();
        zoneRows.clear();
        insetFields.clear();
        maintRows.clear();
        goodRows.clear();
        elemOutRows.clear();

        int cx = leftPos + PW / 2;
        lx = leftPos + 16;
        int y = topPos + headerHeight + 8 + scrollOff;

        // ── Block Mode selector (SAVE vs CORNER) ──
        blockModeBtn = mkMedievalButton(lx, y, 90, 20, "Mode: " + scanner.getBlockMode().name(), () -> {
            BuildingScannerBlockEntity.BlockMode next = scanner.getBlockMode() == BuildingScannerBlockEntity.BlockMode.SAVE
                    ? BuildingScannerBlockEntity.BlockMode.CORNER : BuildingScannerBlockEntity.BlockMode.SAVE;
            scanner.setBlockMode(next);
            syncToServer();
            needsRebuild = true;
        });

        // ── Structure Name input ──
        structureNameEdit = mkEdit(lx + 96, y, 204, scanner.getStructureName(), s -> {
            scanner.setStructureName(s);
            syncToServer();
        });
        y += 26;

        if (scanner.getBlockMode() == BuildingScannerBlockEntity.BlockMode.CORNER) {
            // CORNER mode: simplified UI
            mkMedievalButton(cx - 50, y + 30, 100, 20, "完成", this::onClose);
            return;
        }

        // ── Target Mode & Category & Detect ──
        targetModeBtn = mkMedievalButton(lx, y, 105, 20, "Target: " + scanner.getTargetMode().name(), () -> {
            BuildingScannerBlockEntity.TargetMode next = scanner.getTargetMode() == BuildingScannerBlockEntity.TargetMode.BUILDING
                    ? BuildingScannerBlockEntity.TargetMode.ROAD : BuildingScannerBlockEntity.TargetMode.BUILDING;
            scanner.setTargetMode(next);
            syncToServer();
            needsRebuild = true;
        });

        categoryBtn = mkMedievalButton(lx + 110, y, 95, 20, "Type: " + scanner.getCategory(), () -> {
            int curIdx = CATEGORIES.indexOf(scanner.getCategory());
            int nextIdx = (curIdx + 1) % CATEGORIES.size();
            scanner.setCategory(CATEGORIES.get(nextIdx));
            syncToServer();
            needsRebuild = true;
        });

        mkMedievalButton(lx + 210, y, 90, 20, "匹配角点", () -> {
            syncToServer();
            needsRebuild = true;
        });
        y += 28;

        // ── Door section ──
        addSectionHeader(y, "Door Offset");
        y += 14;
        doorEditY = y + ROW_H - 4;
        y = doorEditY + ROW_H + 6;

        doorX = mkEdit(lx + COL2, doorEditY, FW, loadDoorStr(0), s -> onDoorChanged());
        doorY = mkEdit(lx + COL2 + FW + 4, doorEditY, FW, loadDoorStr(1), s -> onDoorChanged());
        doorZ = mkEdit(lx + COL2 + (FW + 4) * 2, doorEditY, FW, loadDoorStr(2), s -> onDoorChanged());
        mkMedievalButton(lx + COL2 + (FW + 4) * 3 + 8, doorEditY, 50, 18, "清除", () -> {
            scanner.setDoorOffset(null);
            doorX.setValue(""); doorY.setValue(""); doorZ.setValue("");
            syncToServer();
        });

        // ── Tourist interact zones section ──
        addSectionHeader(y, "Tourist Interact Zones (" + scanner.getTouristInteractZones().size() + ")");
        y += 14;
        zoneHeaderY = y - 14;

        mkMedievalButton(lx + COL2 + 200, y - 13, 80, 18, "+ 添加区域", () -> {
            scanner.addTouristInteractZone(new BoundaryBox(
                    BlockOffset.of(-1, 0, -1), BlockOffset.of(1, 0, 1)));
            syncToServer();
            needsRebuild = true;
        });

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

        metaId = mkEdit(lx + 4, y + 14, 150, scanner.getBuildingId(), s -> {
            scanner.setBuildingId(s);
            syncToServer();
        });
        metaName = mkEdit(lx + 164, y + 14, 140, scanner.getDisplayName(), s -> {
            scanner.setDisplayName(s);
            syncToServer();
        });
        y += 36;

        metaLabelY = y;
        y += 12;

        metaComfort = mkNumEdit(lx + COL2, y, FW, scanner.getComfort(), s -> {
            scanner.setComfort(intOrZero(s));
            syncToServer();
        });
        metaMagic = mkNumEdit(lx + COL2 + FW + 12, y, FW, scanner.getMagic(), s -> {
            scanner.setMagic(intOrZero(s));
            syncToServer();
        });
        metaWonder = mkNumEdit(lx + COL2 + (FW + 12) * 2, y, FW, scanner.getWonder(), s -> {
            scanner.setWonder(intOrZero(s));
            syncToServer();
        });
        y += ROW_H + 6;

        // ── Unlock requirement ──
        addSectionHeader(y, "Unlock Requirement");
        y += 14;
        unlockY = y - 14;
        unlockLevel = mkNumEdit(lx + COL2 + 70, y, 40, scanner.getUnlockMinLevel(), s -> {
            scanner.setUnlockMinLevel(Math.max(1, intOrZero(s)));
            syncToServer();
        });
        y += ROW_H + 6;

        // ── Maintenance cost section ──
        addSectionHeader(y, "Maintenance Cost");
        y += 14;
        maintCostY = y - 14;

        mkMedievalButton(lx + COL2 + 200, y - 13, 80, 18, "+ 添加消耗", () -> {
            scanner.addMaintenanceCost("earth", 1);
            syncToServer();
            needsRebuild = true;
        });

        for (var entry : scanner.getMaintenanceCost().entrySet()) {
            String el = entry.getKey();
            maintRows.add(new CostRow(lx + 4, y, el, entry.getValue(),
                    () -> {
                        scanner.removeMaintenanceCost(el);
                        syncToServer();
                        needsRebuild = true;
                    },
                    this::syncMaintCost));
            y += ROW_H;
        }
        y += 6;

        // ── Node config (category=node) ──
        String cat = scanner.getCategory();
        if ("node".equals(cat)) {
            addSectionHeader(y, "Node Config");
            y += 14;
            nodeCatY = y - 14;

            this.currentNodeElem = ELEMENTS.contains(scanner.getNodeElement()) ? scanner.getNodeElement() : "earth";
            nodeElemBtn = mkMedievalButton(lx + COL2 + 70, y, 70, 18, currentNodeElem, () -> {
                int curIdx = ELEMENTS.indexOf(currentNodeElem);
                currentNodeElem = ELEMENTS.get((curIdx + 1) % ELEMENTS.size());
                scanner.setNodeElement(currentNodeElem);
                nodeElemBtn.setMessage(Component.literal(currentNodeElem));
                syncToServer();
            });
            y += ROW_H;

            nodeAmount = mkNumEdit(lx + COL2 + 70, y, 50, scanner.getNodeAmountPerHarvest(), s -> {
                scanner.setNodeAmountPerHarvest(intOrZero(s));
                syncToServer();
            });
            y += ROW_H;

            nodeChannel = mkNumEdit(lx + COL2 + 70, y, 50, scanner.getNodeChannelTicks(), s -> {
                scanner.setNodeChannelTicks(intOrZero(s));
                syncToServer();
            });
            y += ROW_H;

            nodeMana = mkNumEdit(lx + COL2 + 70, y, 50, scanner.getNodeManaCost(), s -> {
                scanner.setNodeManaCost(intOrZero(s));
                syncToServer();
            });
            y += ROW_H + 6;
        } else {
            nodeCatY = 0;
            nodeElemBtn = null;
            nodeAmount = null; nodeChannel = null; nodeMana = null;
        }

        // ── Presets section ──
        addSectionHeader(y, "Presets");
        y += 14;
        presetY = y - 14;
        presetNameEdit = mkEdit(lx + 4, y, 100, "", s -> {});
        mkMedievalButton(lx + 110, y, 60, 18, "保存预设", this::onPresetSave);
        mkMedievalButton(lx + 174, y, 60, 18, "加载预设", this::onPresetLoad);
        y += ROW_H + 6;

        // ── Category-specific sections ──
        if ("shop".equals(cat)) {
            svcCatY = 0;
            addSectionHeader(y, "Shop Config");
            y += 14;
            shopCatY = y - 14;

            shopProfitRate = mkEdit(lx + COL2 + 70, y, 50, String.valueOf(scanner.getShopProfitRate()), s -> {
                try {
                    scanner.setShopProfitRate(Double.parseDouble(s));
                    syncToServer();
                } catch (NumberFormatException ignored) {}
            });
            y += ROW_H;

            shopDuration = mkNumEdit(lx + COL2 + 70, y, 50, scanner.getShopInteractionDurationTicks(), s -> {
                scanner.setShopInteractionDurationTicks(intOrZero(s));
                syncToServer();
            });
            y += ROW_H + 6;

            addSectionHeader(y, "Shop Goods (" + scanner.getShopGoods().size() + ")");
            y += 14;
            goodsCatY = y - 14;

            mkMedievalButton(lx + COL2 + 200, y - 13, 80, 18, "+ 添加商品", () -> {
                scanner.addShopGood(new ShopGoodData("minecraft:apple", 5, 0, 0));
                syncToServer();
                needsRebuild = true;
            });

            for (int i = 0; i < scanner.getShopGoods().size(); i++) {
                goodRows.add(new GoodRow(i, lx + 4, y));
                y += ROW_H;
            }
            y += 6;
            elemOutY = 0;
        } else if ("service".equals(cat)) {
            shopCatY = 0;
            goodsCatY = 0;
            shopProfitRate = null; shopDuration = null;
            addSectionHeader(y, "Service Config");
            y += 14;
            svcCatY = y - 14;

            serviceEnergy = mkNumEdit(lx + COL2 + 90, y, 50, scanner.getServiceEnergyPerUse(), s -> {
                scanner.setServiceEnergyPerUse(intOrZero(s));
                syncToServer();
            });
            y += ROW_H;

            serviceMaxOcc = mkNumEdit(lx + COL2 + 90, y, 50, scanner.getServiceMaxOccupancy(), s -> {
                scanner.setServiceMaxOccupancy(intOrZero(s));
                syncToServer();
            });
            y += ROW_H;

            serviceDuration = mkNumEdit(lx + COL2 + 90, y, 50, scanner.getServiceInteractionDurationTicks(), s -> {
                scanner.setServiceInteractionDurationTicks(intOrZero(s));
                syncToServer();
            });
            y += ROW_H + 6;

            addSectionHeader(y, "Element Output");
            y += 14;
            elemOutY = y - 14;

            mkMedievalButton(lx + COL2 + 200, y - 13, 80, 18, "+ 添加产出", () -> {
                scanner.addServiceElementOutput("earth", 1);
                syncToServer();
                needsRebuild = true;
            });

            for (var entry : scanner.getServiceElementOutput().entrySet()) {
                String el = entry.getKey();
                elemOutRows.add(new CostRow(lx + 4, y, el, entry.getValue(),
                        () -> {
                            scanner.removeServiceElementOutput(el);
                            syncToServer();
                            needsRebuild = true;
                        },
                        this::syncServiceElemOutput));
                y += ROW_H;
            }
            y += 6;
        } else {
            shopCatY = 0;
            goodsCatY = 0;
            shopProfitRate = null;
            shopDuration = null;
            svcCatY = 0;
            elemOutY = 0;
        }

        // ── Export section ──
        addSectionHeader(y, "Export");
        y += 16;
        exportBtnY = y - 14;

        String btnText = scanner.getTargetMode() == BuildingScannerBlockEntity.TargetMode.ROAD
                ? "导出道路 JSON" : "导出建筑 JSON";
        mkMedievalButton(lx + 5, exportBtnY, 100, 20, "扫描区域", () -> doScan());
        mkMedievalButton(lx + 110, exportBtnY, 140, 20, btnText, () -> doExport());

        int bottom = exportBtnY + 60;
        int visibleHeight = height - 40;
        maxScroll = Math.min(0, visibleHeight - bottom);
    }

    private void addSectionHeader(int y, String title) {
        // Layout marker only
    }

    // ── Widget creation helpers ──

    private EditBox mkEdit(int x, int y, int w, String val, Consumer<String> r) {
        insetFields.add(new FieldRect(x, y, w, 18));
        EditBox box = new EditBox(font, x + 3, y + 2, w - 6, 14, Component.empty());
        box.setValue(val);
        box.setBordered(false);
        box.setTextColor(MedievalColors.TEXT_WARM_WHITE);
        box.setTextColorUneditable(MedievalColors.TEXT_MUTED);
        box.setResponder(r);
        return addRenderableWidget(box);
    }

    private EditBox mkNumEdit(int x, int y, int w, int val, Consumer<String> r) {
        insetFields.add(new FieldRect(x, y, w, 18));
        EditBox box = new EditBox(font, x + 3, y + 2, w - 6, 14, Component.empty());
        box.setFilter(s -> s.matches("\\d*"));
        box.setValue(String.valueOf(val));
        box.setBordered(false);
        box.setTextColor(MedievalColors.TEXT_WARM_WHITE);
        box.setTextColorUneditable(MedievalColors.TEXT_MUTED);
        box.setResponder(r);
        return addRenderableWidget(box);
    }

    private MedievalButton mkMedievalButton(int x, int y, int w, int h, String text, Runnable onPress) {
        return addRenderableWidget(new MedievalButton(x, y, w, h, Component.literal(text), onPress::run));
    }

    // ── Inner classes for rows ──

    private class ZoneRow {
        final int index;
        final EditBox[] min = new EditBox[3];
        final EditBox[] max = new EditBox[3];

        ZoneRow(int idx, int zx, int zy) {
            this.index = idx;
            int zw = 28;
            BoundaryBox zone = scanner.getTouristInteractZones().get(idx);

            int labelW = 20;
            EditBox label = mkEdit(zx, zy, labelW, "#" + idx, s -> {});
            label.setEditable(false);
            label.setFocused(false);

            int mx = zx + 24;
            min[0] = mkZoneEdit(mx, zy, zw, zone.min().x(), () -> updateZone(idx));
            min[1] = mkZoneEdit(mx + zw + 2, zy, zw, zone.min().y(), () -> updateZone(idx));
            min[2] = mkZoneEdit(mx + (zw + 2) * 2, zy, zw, zone.min().z(), () -> updateZone(idx));

            int mx2 = mx + (zw + 2) * 3 + 8;
            max[0] = mkZoneEdit(mx2, zy, zw, zone.max().x(), () -> updateZone(idx));
            max[1] = mkZoneEdit(mx2 + zw + 2, zy, zw, zone.max().y(), () -> updateZone(idx));
            max[2] = mkZoneEdit(mx2 + (zw + 2) * 2, zy, zw, zone.max().z(), () -> updateZone(idx));

            mkMedievalButton(mx2 + (zw + 2) * 3 + 6, zy, 18, 18, "×", () -> {
                scanner.removeTouristInteractZone(idx);
                syncToServer();
                needsRebuild = true;
            });
        }
    }

    private class CostRow {
        final Runnable onChanged;
        final MedievalButton elemBtn;
        private String currentElem;
        final EditBox amountBox;

        CostRow(int x, int y, String elem, int amount, Runnable onRemove, Runnable onChanged) {
            this.onChanged = onChanged;
            this.currentElem = ELEMENTS.contains(elem) ? elem : "earth";
            this.elemBtn = addRenderableWidget(new MedievalButton(x, y, 56, 18, Component.literal(currentElem), this::cycleElem));
            amountBox = mkNumEdit(x + 60, y, 36, amount, s -> onChanged.run());
            if (onRemove != null) {
                mkMedievalButton(x + 100, y, 18, 18, "×", onRemove::run);
            }
        }

        private void cycleElem() {
            int curIdx = ELEMENTS.indexOf(currentElem);
            currentElem = ELEMENTS.get((curIdx + 1) % ELEMENTS.size());
            elemBtn.setMessage(Component.literal(currentElem));
            onChanged.run();
        }
        String element() { return currentElem; }
        int amount() { return intOrZero(amountBox); }
    }

    private class GoodRow {
        final int index;
        final EditBox itemIdBox;
        final EditBox gComfort, gMagic, gWonder;

        GoodRow(int idx, int x, int y) {
            this.index = idx;
            ShopGoodData good = scanner.getShopGoods().get(idx);
            itemIdBox = mkEdit(x + 4, y, 120, good.itemId(), s -> updateGood());
            gComfort = mkNumEdit(x + 130, y, 28, good.comfort(), s -> updateGood());
            gMagic = mkNumEdit(x + 162, y, 28, good.magic(), s -> updateGood());
            gWonder = mkNumEdit(x + 194, y, 28, good.wonder(), s -> updateGood());
            mkMedievalButton(x + 228, y, 18, 18, "×", () -> {
                scanner.removeShopGood(idx);
                syncToServer();
                needsRebuild = true;
            });
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
        insetFields.add(new FieldRect(x, y, w, 18));
        EditBox box = new EditBox(font, x + 2, y + 1, w - 4, 14, Component.empty());
        box.setMaxLength(6);
        box.setFilter(s -> s.matches("-?\\d{0,6}"));
        box.setValue(String.valueOf(val));
        box.setBordered(false);
        box.setTextColor(MedievalColors.TEXT_WARM_WHITE);
        box.setTextColorUneditable(MedievalColors.TEXT_MUTED);
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

    private void rebuild() {
        super.clearWidgets();
        zoneRows.clear();
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

    private void syncMaintCost() {
        Map<String, Integer> map = new HashMap<>();
        for (CostRow r : maintRows) {
            if (r.amount() > 0) {
                map.put(r.element(), r.amount());
            }
        }
        scanner.setMaintenanceCost(map);
        syncToServer();
    }

    private void syncServiceElemOutput() {
        Map<String, Integer> map = new HashMap<>();
        for (CostRow r : elemOutRows) {
            if (r.amount() > 0) {
                map.put(r.element(), r.amount());
            }
        }
        scanner.setServiceElementOutput(map);
        syncToServer();
    }

    private void doScan() {
        BlockPos wMin = scanner.getWorldMin();
        BlockPos wMax = scanner.getWorldMax();
        if (wMin == null || wMax == null) {
            scanResult = Component.literal("No boundary defined");
            return;
        }
        int count = 0;
        BlockPos scannerPos = scanner.getBlockPos();
        for (int x = wMin.getX(); x <= wMax.getX(); x++) {
            for (int y = wMin.getY(); y <= wMax.getY(); y++) {
                for (int z = wMin.getZ(); z <= wMax.getZ(); z++) {
                    BlockPos bp = new BlockPos(x, y, z);
                    if (bp.equals(scannerPos)) continue;
                    if (minecraft != null && minecraft.level != null && !minecraft.level.getBlockState(bp).isAir()) {
                        count++;
                    }
                }
            }
        }
        scanResult = Component.literal("Scanned " + count + " non-air blocks");
    }

    private void doExport() {
        String id = scanner.getBuildingId();
        if (id.isBlank()) {
            scanResult = Component.literal("Set a building ID before exporting");
            return;
        }
        PacketDistributor.sendToServer(new BuildingScannerExportPacket(scanner.getBlockPos()));
        scanResult = Component.literal("Export requested for '" + id + "' — check server console");
    }

    private void syncToServer() {
        if (minecraft == null || minecraft.level == null) return;
        CompoundTag tag = scanner.saveWithoutMetadata(minecraft.level.registryAccess());
        PacketDistributor.sendToServer(new BuildingScannerSyncPacket(scanner.getBlockPos(), tag));
    }

    private static final Map<String, CompoundTag> LOCAL_PRESETS = new HashMap<>();

    private void onPresetSave() {
        if (presetNameEdit == null || minecraft == null) return;
        String name = presetNameEdit.getValue().trim();
        if (name.isEmpty()) return;
        CompoundTag tag = capturePresetData();
        LOCAL_PRESETS.put(name, tag);
        scanResult = Component.literal("Preset saved: " + name);
    }

    private void onPresetLoad() {
        if (presetNameEdit == null || minecraft == null) return;
        String name = presetNameEdit.getValue().trim();
        if (name.isEmpty()) return;
        CompoundTag tag = LOCAL_PRESETS.get(name);
        if (tag == null) {
            scanResult = Component.literal("Preset not found: " + name);
            return;
        }
        applyPresetData(tag);
        syncToServer();
        needsRebuild = true;
        scanResult = Component.literal("Preset loaded: " + name);
    }

    private CompoundTag capturePresetData() {
        CompoundTag tag = new CompoundTag();
        BlockOffset bMin = scanner.getBoundaryMin();
        BlockOffset bMax = scanner.getBoundaryMax();
        tag.putIntArray("boundary_min", new int[]{bMin.x(), bMin.y(), bMin.z()});
        tag.putIntArray("boundary_max", new int[]{bMax.x(), bMax.y(), bMax.z()});

        BlockOffset dOff = scanner.getDoorOffset();
        if (dOff != null) {
            tag.putIntArray("door_offset", new int[]{dOff.x(), dOff.y(), dOff.z()});
        }

        ListTag zonesTag = new ListTag();
        for (BoundaryBox zone : scanner.getTouristInteractZones()) {
            CompoundTag zt = new CompoundTag();
            zt.putIntArray("min", new int[]{zone.min().x(), zone.min().y(), zone.min().z()});
            zt.putIntArray("max", new int[]{zone.max().x(), zone.max().y(), zone.max().z()});
            zonesTag.add(zt);
        }
        tag.put("tourist_interact_zones", zonesTag);

        tag.putString("building_id", scanner.getBuildingId());
        tag.putString("display_name", scanner.getDisplayName());
        tag.putString("category", scanner.getCategory());
        tag.putInt("comfort", scanner.getComfort());
        tag.putInt("magic", scanner.getMagic());
        tag.putInt("wonder", scanner.getWonder());
        tag.putInt("unlock_min_level", scanner.getUnlockMinLevel());

        ListTag mcList = new ListTag();
        for (var entry : scanner.getMaintenanceCost().entrySet()) {
            CompoundTag et = new CompoundTag();
            et.putString("element", entry.getKey());
            et.putInt("amount", entry.getValue());
            mcList.add(et);
        }
        tag.put("maintenance_cost", mcList);

        CompoundTag ncTag = new CompoundTag();
        ncTag.putString("blueprint", scanner.getNodeBlueprint());
        ncTag.putString("element", scanner.getNodeElement());
        ncTag.putInt("amount_per_harvest", scanner.getNodeAmountPerHarvest());
        ncTag.putInt("channel_ticks", scanner.getNodeChannelTicks());
        ncTag.putInt("mana_cost", scanner.getNodeManaCost());
        tag.put("node_config", ncTag);

        tag.putDouble("shop_profit_rate", scanner.getShopProfitRate());
        tag.putInt("shop_interaction_duration_ticks", scanner.getShopInteractionDurationTicks());

        ListTag goodsList = new ListTag();
        for (ShopGoodData good : scanner.getShopGoods()) {
            CompoundTag gt = new CompoundTag();
            gt.putString("item_id", good.itemId());
            gt.putInt("comfort", good.comfort());
            gt.putInt("magic", good.magic());
            gt.putInt("wonder", good.wonder());
            goodsList.add(gt);
        }
        tag.put("shop_goods", goodsList);

        tag.putInt("service_energy_per_use", scanner.getServiceEnergyPerUse());
        tag.putInt("service_max_occupancy", scanner.getServiceMaxOccupancy());
        tag.putInt("service_interaction_duration_ticks", scanner.getServiceInteractionDurationTicks());

        ListTag seoList = new ListTag();
        for (var entry : scanner.getServiceElementOutput().entrySet()) {
            CompoundTag et = new CompoundTag();
            et.putString("element", entry.getKey());
            et.putInt("amount", entry.getValue());
            seoList.add(et);
        }
        tag.put("service_element_output", seoList);

        return tag;
    }

    private void applyPresetData(CompoundTag tag) {
        if (tag.contains("category")) scanner.setCategory(tag.getString("category"));
        if (tag.contains("building_id")) scanner.setBuildingId(tag.getString("building_id"));
        if (tag.contains("display_name")) scanner.setDisplayName(tag.getString("display_name"));
        if (tag.contains("comfort")) scanner.setComfort(tag.getInt("comfort"));
        if (tag.contains("magic")) scanner.setMagic(tag.getInt("magic"));
        if (tag.contains("wonder")) scanner.setWonder(tag.getInt("wonder"));
        if (tag.contains("unlock_min_level")) scanner.setUnlockMinLevel(tag.getInt("unlock_min_level"));

        if (tag.contains("door_offset", Tag.TAG_INT_ARRAY)) {
            int[] arr = tag.getIntArray("door_offset");
            if (arr.length == 3) scanner.setDoorOffset(BlockOffset.of(arr[0], arr[1], arr[2]));
        } else {
            scanner.setDoorOffset(null);
        }

        scanner.clearTouristInteractZones();
        if (tag.contains("tourist_interact_zones", Tag.TAG_LIST)) {
            for (int i = 0; i < tag.getList("tourist_interact_zones", Tag.TAG_COMPOUND).size(); i++) {
                CompoundTag zt = tag.getList("tourist_interact_zones", Tag.TAG_COMPOUND).getCompound(i);
                int[] min = zt.getIntArray("min");
                int[] max = zt.getIntArray("max");
                if (min.length == 3 && max.length == 3) {
                    scanner.addTouristInteractZone(new BoundaryBox(
                            BlockOffset.of(min[0], min[1], min[2]),
                            BlockOffset.of(max[0], max[1], max[2])));
                }
            }
        }

        scanner.setMaintenanceCost(Map.of());
        if (tag.contains("maintenance_cost", Tag.TAG_LIST)) {
            ListTag list = tag.getList("maintenance_cost", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag et = list.getCompound(i);
                scanner.addMaintenanceCost(et.getString("element"), et.getInt("amount"));
            }
        }

        if (tag.contains("node_config", Tag.TAG_COMPOUND)) {
            CompoundTag nc = tag.getCompound("node_config");
            if (nc.contains("element")) scanner.setNodeElement(nc.getString("element"));
            if (nc.contains("amount_per_harvest")) scanner.setNodeAmountPerHarvest(nc.getInt("amount_per_harvest"));
            if (nc.contains("channel_ticks")) scanner.setNodeChannelTicks(nc.getInt("channel_ticks"));
            if (nc.contains("mana_cost")) scanner.setNodeManaCost(nc.getInt("mana_cost"));
        }

        if (tag.contains("shop_profit_rate")) scanner.setShopProfitRate(tag.getDouble("shop_profit_rate"));
        if (tag.contains("shop_interaction_duration_ticks")) scanner.setShopInteractionDurationTicks(tag.getInt("shop_interaction_duration_ticks"));

        scanner.clearShopGoods();
        if (tag.contains("shop_goods", Tag.TAG_LIST)) {
            ListTag gl = tag.getList("shop_goods", Tag.TAG_COMPOUND);
            for (int i = 0; i < gl.size(); i++) {
                CompoundTag gt = gl.getCompound(i);
                scanner.addShopGood(new ShopGoodData(
                        gt.getString("item_id"), gt.getInt("comfort"), gt.getInt("magic"), gt.getInt("wonder")));
            }
        }

        if (tag.contains("service_energy_per_use")) scanner.setServiceEnergyPerUse(tag.getInt("service_energy_per_use"));
        if (tag.contains("service_max_occupancy")) scanner.setServiceMaxOccupancy(tag.getInt("service_max_occupancy"));
        if (tag.contains("service_interaction_duration_ticks")) scanner.setServiceInteractionDurationTicks(tag.getInt("service_interaction_duration_ticks"));

        scanner.setServiceElementOutput(Map.of());
        if (tag.contains("service_element_output", Tag.TAG_LIST)) {
            ListTag so = tag.getList("service_element_output", Tag.TAG_COMPOUND);
            for (int i = 0; i < so.size(); i++) {
                CompoundTag et = so.getCompound(i);
                scanner.addServiceElementOutput(et.getString("element"), et.getInt("amount"));
            }
        }
    }

    // ── Render ──

    @Override
    public void render(GuiGraphics gui, int mx, int my, float pt) {
        renderBackground(gui, mx, my, pt);
        renderMinimalHeader(gui);
        renderCloseButton(gui, mx, my);

        // Render inset dark field backgrounds for all edit boxes
        for (FieldRect f : insetFields) {
            drawInsetField(gui, f.x(), f.y(), f.w(), f.h());
        }

        if (scanner.getBlockMode() == BuildingScannerBlockEntity.BlockMode.CORNER) {
            gui.drawString(font, "CORNER 模式：请在名称框输入结构名称。", lx, topPos + headerHeight + 50, MedievalColors.TEXT_MUTED);
            gui.drawString(font, "SAVE 模式扫描器会自动匹配同名 CORNER 算出 3D 包围盒。", lx, topPos + headerHeight + 66, MedievalColors.TEXT_DIM);
            super.render(gui, mx, my, pt);
            return;
        }

        // Boundary size summary
        BlockOffset bMin = scanner.getBoundaryMin();
        BlockOffset bMax = scanner.getBoundaryMax();
        int dx = bMax.x() - bMin.x() + 1;
        int dy = bMax.y() - bMin.y() + 1;
        int dz = bMax.z() - bMin.z() + 1;
        String bInfo = String.format("包围盒: Min(%d,%d,%d) ~ Max(%d,%d,%d) | 尺寸: %d×%d×%d",
                bMin.x(), bMin.y(), bMin.z(), bMax.x(), bMax.y(), bMax.z(), dx, dy, dz);
        gui.drawString(font, bInfo, lx, topPos + headerHeight + 36, MedievalColors.BORDER_GOLD);

        // Section Headers & Labels
        drawHdr(gui, "门偏移 (Door Offset)", lx, doorEditY - 14);
        drawLbl(gui, "X", lx + COL2, doorEditY - 10);
        drawLbl(gui, "Y", lx + COL2 + FW + 4, doorEditY - 10);
        drawLbl(gui, "Z", lx + COL2 + (FW + 4) * 2, doorEditY - 10);

        drawHdr(gui, "游览交互区 (" + scanner.getTouristInteractZones().size() + ")", lx, zoneHeaderY);

        drawHdr(gui, "放置元数据", lx, metaStartY);
        drawLbl(gui, "ID", lx + 4, metaStartY + 14);
        drawLbl(gui, "Name", lx + 164, metaStartY + 14);
        drawLbl(gui, "Comfort", lx + COL2, metaLabelY - 10);
        drawLbl(gui, "Magic", lx + COL2 + FW + 12, metaLabelY - 10);
        drawLbl(gui, "Wonder", lx + COL2 + (FW + 12) * 2, metaLabelY - 10);

        drawHdr(gui, "解锁等级", lx, unlockY);
        drawLbl(gui, "最低等级", lx + COL2, unlockY + ROW_H - 4);

        drawHdr(gui, "周期维护费", lx, maintCostY);

        if ("node".equals(scanner.getCategory())) {
            drawHdr(gui, "节点配置", lx, nodeCatY);
            drawLbl(gui, "元素", lx + COL2, nodeCatY + ROW_H - 4);
            drawLbl(gui, "产出/次", lx + COL2, nodeCatY + ROW_H * 2 - 4);
            drawLbl(gui, "引导Ticks", lx + COL2, nodeCatY + ROW_H * 3 - 4);
            drawLbl(gui, "魔力消耗", lx + COL2, nodeCatY + ROW_H * 4 - 4);
        }

        drawHdr(gui, "预设预存", lx, presetY);

        String cat = scanner.getCategory();
        if ("shop".equals(cat)) {
            drawHdr(gui, "商店参数", lx, shopCatY);
            drawLbl(gui, "利润率%", lx + COL2, shopCatY + ROW_H - 4);
            drawLbl(gui, "交互时长", lx + COL2, shopCatY + ROW_H * 2 - 4);
            drawHdr(gui, "上架商品", lx, goodsCatY);
        } else if ("service".equals(cat)) {
            drawHdr(gui, "服务参数", lx, svcCatY);
            drawLbl(gui, "能量消耗/次", lx + COL2, svcCatY + ROW_H - 4);
            drawLbl(gui, "最大容纳人数", lx + COL2, svcCatY + ROW_H * 2 - 2);
            drawLbl(gui, "交互时长", lx + COL2, svcCatY + ROW_H * 3 - 2);
            drawHdr(gui, "元素产出", lx, elemOutY);
        }

        drawHdr(gui, "导出导出", lx, exportBtnY - 14);
        gui.drawString(font, scanResult, lx + 230, exportBtnY + 6, MedievalColors.TEXT_MUTED);

        super.render(gui, mx, my, pt);
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

    private static int intOrZero(String s) {
        if (s == null || s.isEmpty()) return 0;
        try { return Integer.parseInt(s); }
        catch (NumberFormatException e) { return 0; }
    }
}
