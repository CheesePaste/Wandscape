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
 * Features strict Scissor clipping to prevent scroll overflow, spacious layout,
 * and custom drawMinimalBox buttons matching TownHallCreateScreen style.
 */
public class BuildingScannerScreen extends MedievalScreen {

    private static final int PW = 360;
    private static final int PH = 270;

    private final BuildingScannerBlockEntity scanner;

    // ── Custom Medieval Button Definition ──
    private record CustomButton(int x, int y, int w, int h, String text, Runnable action) {}
    private final List<CustomButton> customButtons = new ArrayList<>();

    // ── Structure Block Mode & Name ──
    private EditBox structureNameEdit;

    // ── Category ──
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
    private Component scanResult = Component.literal("尚未扫描");

    // ── Layout Y positions (computed in init, used in render) ──
    private int lx; // left edge for widgets (leftPos + 16)
    private int boundaryCardY;
    private int doorEditY;
    private int zoneHeaderY;
    private int metaStartY, metaLabelY;
    private int unlockY;
    private int shopCatY, svcCatY;
    private int exportBtnY;

    // ── Field Background Inset Rectangles ──
    private record FieldRect(int x, int y, int w, int h) {}
    private final List<FieldRect> insetFields = new ArrayList<>();

    // ── Column layout constants (Spacious: max right edge <= lx + 320) ──
    private static final int COL2 = 70;  // input fields start here
    private static final int FW = 54;    // default field width
    private static final int ROW_H = 24; // vertical row spacing

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

    private void addCustomButton(int x, int y, int w, int h, String text, Runnable action) {
        customButtons.add(new CustomButton(x, y, w, h, text, action));
    }

    // ── init / rebuild ──

    @Override
    protected void init() {
        super.init();
        customButtons.clear();
        zoneRows.clear();
        insetFields.clear();
        maintRows.clear();
        goodRows.clear();
        elemOutRows.clear();

        int cx = leftPos + PW / 2;
        lx = leftPos + 16;
        int y = topPos + headerHeight + 10 + scrollOff;

        // ── Toolbar Row 1: Mode & Structure Name (Right edge: lx + 320) ──
        addCustomButton(lx, y, 90, 20, "Mode: " + scanner.getBlockMode().name(), () -> {
            BuildingScannerBlockEntity.BlockMode next = scanner.getBlockMode() == BuildingScannerBlockEntity.BlockMode.SAVE
                    ? BuildingScannerBlockEntity.BlockMode.CORNER : BuildingScannerBlockEntity.BlockMode.SAVE;
            scanner.setBlockMode(next);
            syncToServer();
            needsRebuild = true;
        });

        structureNameEdit = mkEdit(lx + 145, y, 175, scanner.getStructureName(), s -> {
            scanner.setStructureName(s);
            syncToServer();
        });
        y += 28;

        if (scanner.getBlockMode() == BuildingScannerBlockEntity.BlockMode.CORNER) {
            // CORNER mode: simplified UI
            addCustomButton(cx - 50, y + 60, 100, 22, "完成", this::onClose);
            return;
        }

        // ── ROAD Target Mode: Ultra-clean UI (Only Road Info, Export & Hot-register) ──
        if (scanner.getTargetMode() == BuildingScannerBlockEntity.TargetMode.ROAD) {
            addCustomButton(lx, y, 145, 20, "Target: ROAD", () -> {
                scanner.setTargetMode(BuildingScannerBlockEntity.TargetMode.BUILDING);
                syncToServer();
                needsRebuild = true;
            });

            addCustomButton(lx + 155, y, 165, 20, "❖ 匹配角点", () -> {
                syncToServer();
                needsRebuild = true;
            });
            y += 28;

            // Boundary Card Row
            boundaryCardY = y;
            y += 28;

            // Road Preset Identity (ID & Display Name)
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

            // Export section
            addSectionHeader(y, "❖ 道路 JSON 导出与热注册");
            y += 18;
            exportBtnY = y - 14;

            addCustomButton(lx + 4, exportBtnY, 95, 22, "扫描区域", () -> doScan());
            addCustomButton(lx + 105, exportBtnY, 215, 22, "导出与热注册道路 JSON", () -> doExport());

            int bottom = exportBtnY + 60;
            int visibleHeight = height - 40;
            maxScroll = Math.min(0, visibleHeight - bottom);
            return;
        }

        // ── BUILDING Target Mode: Complete Building Configuration ──
        addCustomButton(lx, y, 110, 20, "Target: BUILDING", () -> {
            scanner.setTargetMode(BuildingScannerBlockEntity.TargetMode.ROAD);
            syncToServer();
            needsRebuild = true;
        });

        addCustomButton(lx + 115, y, 100, 20, "Type: " + scanner.getCategory(), () -> {
            int curIdx = CATEGORIES.indexOf(scanner.getCategory());
            int nextIdx = (curIdx + 1) % CATEGORIES.size();
            scanner.setCategory(CATEGORIES.get(nextIdx));
            syncToServer();
            needsRebuild = true;
        });

        addCustomButton(lx + 220, y, 100, 20, "❖ 匹配角点", () -> {
            syncToServer();
            needsRebuild = true;
        });
        y += 28;

        // ── Boundary Card Row ──
        boundaryCardY = y;
        y += 28;

        // ── Door section ──
        addSectionHeader(y, "❖ 门偏移 (Door Offset)");
        y += 16;
        doorEditY = y + ROW_H - 4;
        y = doorEditY + ROW_H + 8;

        doorX = mkEdit(lx + COL2, doorEditY, FW, loadDoorStr(0), s -> onDoorChanged());
        doorY = mkEdit(lx + COL2 + FW + 4, doorEditY, FW, loadDoorStr(1), s -> onDoorChanged());
        doorZ = mkEdit(lx + COL2 + (FW + 4) * 2, doorEditY, FW, loadDoorStr(2), s -> onDoorChanged());
        addCustomButton(lx + 260, doorEditY, 60, 20, "清除", () -> {
            scanner.setDoorOffset(null);
            doorX.setValue(""); doorY.setValue(""); doorZ.setValue("");
            syncToServer();
        });

        // ── Tourist interact zones section ──
        addSectionHeader(y, "❖ 游览交互区 (" + scanner.getTouristInteractZones().size() + ")");
        y += 16;
        zoneHeaderY = y - 14;

        addCustomButton(lx + 240, y - 15, 80, 20, "+ 添加区域", () -> {
            scanner.addTouristInteractZone(new BoundaryBox(
                    BlockOffset.of(-1, 0, -1), BlockOffset.of(1, 0, 1)));
            syncToServer();
            needsRebuild = true;
        });

        List<BoundaryBox> zones = scanner.getTouristInteractZones();
        for (int i = 0; i < zones.size(); i++) {
            ZoneRow row = new ZoneRow(i, lx + 4, y);
            zoneRows.add(row);
            y += ROW_H + 4;
        }
        y += 8;

        // ── Metadata section ──
        addSectionHeader(y, "❖ 放置元数据");
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

        metaLabelY = y;
        y += 14;

        metaComfort = mkNumEdit(lx + COL2, y, FW, scanner.getComfort(), s -> {
            scanner.setComfort(intOrZero(s));
            syncToServer();
        });
        metaMagic = mkNumEdit(lx + COL2 + FW + 16, y, FW, scanner.getMagic(), s -> {
            scanner.setMagic(intOrZero(s));
            syncToServer();
        });
        metaWonder = mkNumEdit(lx + COL2 + (FW + 16) * 2, y, FW, scanner.getWonder(), s -> {
            scanner.setWonder(intOrZero(s));
            syncToServer();
        });
        y += ROW_H + 8;

        // ── Unlock requirement ──
        addSectionHeader(y, "❖ 解锁门槛");
        y += 16;
        unlockY = y - 14;
        unlockLevel = mkNumEdit(lx + COL2 + 70, y, 50, scanner.getUnlockMinLevel(), s -> {
            scanner.setUnlockMinLevel(Math.max(1, intOrZero(s)));
            syncToServer();
        });
        y += ROW_H + 8;

        // ── Maintenance cost section ──
        addSectionHeader(y, "❖ 周期维护费");
        y += 16;
        maintCostY = y - 14;

        addCustomButton(lx + 240, y - 15, 80, 20, "+ 添加消耗", () -> {
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
            y += ROW_H + 2;
        }
        y += 8;

        // ── Node config (category=node) ──
        String cat = scanner.getCategory();
        if ("node".equals(cat)) {
            addSectionHeader(y, "❖ 节点配置");
            y += 16;
            nodeCatY = y - 14;

            this.currentNodeElem = ELEMENTS.contains(scanner.getNodeElement()) ? scanner.getNodeElement() : "earth";
            addCustomButton(lx + COL2 + 70, y, 75, 20, currentNodeElem, () -> {
                int curIdx = ELEMENTS.indexOf(currentNodeElem);
                currentNodeElem = ELEMENTS.get((curIdx + 1) % ELEMENTS.size());
                scanner.setNodeElement(currentNodeElem);
                syncToServer();
                needsRebuild = true;
            });
            y += ROW_H + 2;

            nodeAmount = mkNumEdit(lx + COL2 + 70, y, 50, scanner.getNodeAmountPerHarvest(), s -> {
                scanner.setNodeAmountPerHarvest(intOrZero(s));
                syncToServer();
            });
            y += ROW_H + 2;

            nodeChannel = mkNumEdit(lx + COL2 + 70, y, 50, scanner.getNodeChannelTicks(), s -> {
                scanner.setNodeChannelTicks(intOrZero(s));
                syncToServer();
            });
            y += ROW_H + 2;

            nodeMana = mkNumEdit(lx + COL2 + 70, y, 50, scanner.getNodeManaCost(), s -> {
                scanner.setNodeManaCost(intOrZero(s));
                syncToServer();
            });
            y += ROW_H + 8;
        } else {
            nodeCatY = 0;
            nodeAmount = null; nodeChannel = null; nodeMana = null;
        }

        // ── Presets section ──
        addSectionHeader(y, "❖ 预设预存");
        y += 16;
        presetY = y - 14;
        presetNameEdit = mkEdit(lx + 4, y, 110, "", s -> {});
        addCustomButton(lx + 120, y, 65, 20, "保存预设", this::onPresetSave);
        addCustomButton(lx + 190, y, 65, 20, "加载预设", this::onPresetLoad);
        y += ROW_H + 8;

        // ── Category-specific sections ──
        if ("shop".equals(cat)) {
            svcCatY = 0;
            addSectionHeader(y, "❖ 商店参数");
            y += 16;
            shopCatY = y - 14;

            shopProfitRate = mkEdit(lx + COL2 + 70, y, 50, String.valueOf(scanner.getShopProfitRate()), s -> {
                try {
                    scanner.setShopProfitRate(Double.parseDouble(s));
                    syncToServer();
                } catch (NumberFormatException ignored) {}
            });
            y += ROW_H + 2;

            shopDuration = mkNumEdit(lx + COL2 + 70, y, 50, scanner.getShopInteractionDurationTicks(), s -> {
                scanner.setShopInteractionDurationTicks(intOrZero(s));
                syncToServer();
            });
            y += ROW_H + 8;

            addSectionHeader(y, "❖ 上架商品 (" + scanner.getShopGoods().size() + ")");
            y += 16;
            goodsCatY = y - 14;

            addCustomButton(lx + 240, y - 15, 80, 20, "+ 添加商品", () -> {
                scanner.addShopGood(new ShopGoodData("minecraft:apple", 5, 0, 0));
                syncToServer();
                needsRebuild = true;
            });

            for (int i = 0; i < scanner.getShopGoods().size(); i++) {
                goodRows.add(new GoodRow(i, lx + 4, y));
                y += ROW_H + 2;
            }
            y += 8;
            elemOutY = 0;
        } else if ("service".equals(cat)) {
            shopCatY = 0;
            goodsCatY = 0;
            shopProfitRate = null; shopDuration = null;
            addSectionHeader(y, "❖ 服务参数");
            y += 16;
            svcCatY = y - 14;

            serviceEnergy = mkNumEdit(lx + COL2 + 90, y, 50, scanner.getServiceEnergyPerUse(), s -> {
                scanner.setServiceEnergyPerUse(intOrZero(s));
                syncToServer();
            });
            y += ROW_H + 2;

            serviceMaxOcc = mkNumEdit(lx + COL2 + 90, y, 50, scanner.getServiceMaxOccupancy(), s -> {
                scanner.setServiceMaxOccupancy(intOrZero(s));
                syncToServer();
            });
            y += ROW_H + 2;

            serviceDuration = mkNumEdit(lx + COL2 + 90, y, 50, scanner.getServiceInteractionDurationTicks(), s -> {
                scanner.setServiceInteractionDurationTicks(intOrZero(s));
                syncToServer();
            });
            y += ROW_H + 8;

            addSectionHeader(y, "❖ 元素产出");
            y += 16;
            elemOutY = y - 14;

            addCustomButton(lx + 240, y - 15, 80, 20, "+ 添加产出", () -> {
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
                y += ROW_H + 2;
            }
            y += 8;
        } else {
            shopCatY = 0;
            goodsCatY = 0;
            shopProfitRate = null;
            shopDuration = null;
            svcCatY = 0;
            elemOutY = 0;
        }

        // ── Export section ──
        addSectionHeader(y, "❖ 蓝图与道路导出");
        y += 18;
        exportBtnY = y - 14;

        addCustomButton(lx + 4, exportBtnY, 95, 22, "扫描区域", () -> doScan());
        addCustomButton(lx + 105, exportBtnY, 215, 22, "导出建筑 JSON", () -> doExport());

        int bottom = exportBtnY + 60;
        int visibleHeight = height - 40;
        maxScroll = Math.min(0, visibleHeight - bottom);
    }

    private void addSectionHeader(int y, String title) {
        // Layout marker only
    }

    // ── Widget creation helpers ──

    private EditBox mkEdit(int x, int y, int w, String val, Consumer<String> r) {
        insetFields.add(new FieldRect(x, y, w, 20));
        EditBox box = new EditBox(font, x + 3, y + 3, w - 6, 14, Component.empty());
        box.setValue(val);
        box.setBordered(false);
        box.setTextColor(MedievalColors.TEXT_WARM_WHITE);
        box.setTextColorUneditable(MedievalColors.TEXT_MUTED);
        box.setResponder(r);
        return addRenderableWidget(box);
    }

    private EditBox mkNumEdit(int x, int y, int w, int val, Consumer<String> r) {
        insetFields.add(new FieldRect(x, y, w, 20));
        EditBox box = new EditBox(font, x + 3, y + 3, w - 6, 14, Component.empty());
        box.setFilter(s -> s.matches("\\d*"));
        box.setValue(String.valueOf(val));
        box.setBordered(false);
        box.setTextColor(MedievalColors.TEXT_WARM_WHITE);
        box.setTextColorUneditable(MedievalColors.TEXT_MUTED);
        box.setResponder(r);
        return addRenderableWidget(box);
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

            addCustomButton(mx2 + (zw + 2) * 3 + 6, zy, 18, 20, "×", () -> {
                scanner.removeTouristInteractZone(idx);
                syncToServer();
                needsRebuild = true;
            });
        }
    }

    private class CostRow {
        final Runnable onChanged;
        private String currentElem;
        final EditBox amountBox;

        CostRow(int x, int y, String elem, int amount, Runnable onRemove, Runnable onChanged) {
            this.onChanged = onChanged;
            this.currentElem = ELEMENTS.contains(elem) ? elem : "earth";
            addCustomButton(x, y, 60, 20, currentElem, this::cycleElem);
            amountBox = mkNumEdit(x + 64, y, 40, amount, s -> onChanged.run());
            if (onRemove != null) {
                addCustomButton(x + 108, y, 18, 20, "×", onRemove::run);
            }
        }

        private void cycleElem() {
            int curIdx = ELEMENTS.indexOf(currentElem);
            currentElem = ELEMENTS.get((curIdx + 1) % ELEMENTS.size());
            onChanged.run();
            needsRebuild = true;
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
            itemIdBox = mkEdit(x + 4, y, 130, good.itemId(), s -> updateGood());
            gComfort = mkNumEdit(x + 140, y, 28, good.comfort(), s -> updateGood());
            gMagic = mkNumEdit(x + 172, y, 28, good.magic(), s -> updateGood());
            gWonder = mkNumEdit(x + 204, y, 28, good.wonder(), s -> updateGood());
            addCustomButton(x + 238, y, 18, 20, "×", () -> {
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
        insetFields.add(new FieldRect(x, y, w, 20));
        EditBox box = new EditBox(font, x + 2, y + 2, w - 4, 14, Component.empty());
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
                    if (minecraft != null && minecraft.level != null && !minecraft.level.getBlockState(bp).isAir()) {
                        count++;
                    }
                }
            }
        }
        scanResult = Component.literal("已扫描 " + count + " 个有效方块");
    }

    private void doExport() {
        String id = scanner.getBuildingId();
        if (id.isBlank()) {
            scanResult = Component.literal("请先设置 ID");
            return;
        }
        PacketDistributor.sendToServer(new BuildingScannerExportPacket(scanner.getBlockPos()));
        scanResult = Component.literal("已发起导出: " + id);
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
        scanResult = Component.literal("预设已保存: " + name);
    }

    private void onPresetLoad() {
        if (presetNameEdit == null || minecraft == null) return;
        String name = presetNameEdit.getValue().trim();
        if (name.isEmpty()) return;
        CompoundTag tag = LOCAL_PRESETS.get(name);
        if (tag == null) {
            scanResult = Component.literal("未找到预设: " + name);
            return;
        }
        applyPresetData(tag);
        syncToServer();
        needsRebuild = true;
        scanResult = Component.literal("预设已加载: " + name);
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

        // Enable strict Scissor clipping for all internal text and custom buttons
        gui.enableScissor(clipLeft, clipTop, clipRight, clipBottom);

        // Render inset dark field backgrounds for all edit boxes inside scissor
        for (FieldRect f : insetFields) {
            if (f.y() + f.h() > clipTop && f.y() < clipBottom) {
                drawInsetField(gui, f.x(), f.y(), f.w(), f.h());
            }
        }

        // Draw custom medieval minimal box buttons (matching TownHallCreateScreen style) inside scissor
        for (CustomButton btn : customButtons) {
            if (btn.y() + btn.h() > clipTop && btn.y() < clipBottom) {
                boolean hover = isInRect(mx, my, btn.x(), btn.y(), btn.w(), btn.h());
                drawMinimalBox(gui, btn.x(), btn.y(), btn.w(), btn.h(), hover, hover);
                int textColor = hover ? MedievalColors.BORDER_GOLD : MedievalColors.TEXT_WARM_WHITE;
                gui.drawString(font, btn.text(), btn.x() + (btn.w() - font.width(btn.text())) / 2,
                        btn.y() + (btn.h() - font.lineHeight) / 2, textColor);
            }
        }

        // Label for structure name
        int topY = topPos + headerHeight + 10 + scrollOff;
        gui.drawString(font, "结构名称", lx + 94, topY + 6, MedievalColors.TEXT_MUTED);

        if (scanner.getBlockMode() == BuildingScannerBlockEntity.BlockMode.CORNER) {
            drawMinimalBox(gui, lx, topPos + headerHeight + 38, 320, 64, true, false);
            gui.drawString(font, "❖ CORNER 辅角点模式", lx + 10, topPos + headerHeight + 46, MedievalColors.BORDER_GOLD);
            gui.drawString(font, "1. 请在上方输入与 SAVE 扫描器相同的结构名称。", lx + 10, topPos + headerHeight + 60, MedievalColors.TEXT_WARM_WHITE);
            gui.drawString(font, "2. 将此方块放置在建筑 3D 对角线的另一个顶点位置。", lx + 10, topPos + headerHeight + 74, MedievalColors.TEXT_MUTED);
            gui.disableScissor();
            super.render(gui, mx, my, pt);
            return;
        }

        // ── ROAD Target Mode Render ──
        if (scanner.getTargetMode() == BuildingScannerBlockEntity.TargetMode.ROAD) {
            if (boundaryCardY > topPos + headerHeight && boundaryCardY < topPos + PH - 16) {
                drawMinimalBox(gui, lx, boundaryCardY, 320, 20, false, false);
                BlockOffset bMin = scanner.getBoundaryMin();
                BlockOffset bMax = scanner.getBoundaryMax();
                int dx = Math.abs(bMax.x() - bMin.x()) + 1;
                int dy = Math.abs(bMax.y() - bMin.y()) + 1;
                int dz = Math.abs(bMax.z() - bMin.z()) + 1;
                String sizeText = String.format("❖ 3D 区域尺寸: %d × %d × %d 格  (Min:%d,%d,%d Max:%d,%d,%d)",
                        dx, dy, dz, bMin.x(), bMin.y(), bMin.z(), bMax.x(), bMax.y(), bMax.z());
                gui.drawString(font, sizeText, lx + 8, boundaryCardY + 6, MedievalColors.BORDER_GOLD);
            }

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
        if (boundaryCardY > topPos + headerHeight && boundaryCardY < topPos + PH - 16) {
            drawMinimalBox(gui, lx, boundaryCardY, 320, 20, false, false);
            BlockOffset bMin = scanner.getBoundaryMin();
            BlockOffset bMax = scanner.getBoundaryMax();
            int dx = Math.abs(bMax.x() - bMin.x()) + 1;
            int dy = Math.abs(bMax.y() - bMin.y()) + 1;
            int dz = Math.abs(bMax.z() - bMin.z()) + 1;
            String sizeText = String.format("❖ 3D 区域尺寸: %d × %d × %d 格  (Min:%d,%d,%d Max:%d,%d,%d)",
                    dx, dy, dz, bMin.x(), bMin.y(), bMin.z(), bMax.x(), bMax.y(), bMax.z());
            gui.drawString(font, sizeText, lx + 8, boundaryCardY + 6, MedievalColors.BORDER_GOLD);
        }

        // Section Headers & Labels
        drawHdr(gui, "❖ 门偏移 (Door Offset)", lx, doorEditY - 14);
        drawLbl(gui, "X", lx + COL2, doorEditY - 10);
        drawLbl(gui, "Y", lx + COL2 + FW + 4, doorEditY - 10);
        drawLbl(gui, "Z", lx + COL2 + (FW + 4) * 2, doorEditY - 10);

        drawHdr(gui, "❖ 游览交互区 (" + scanner.getTouristInteractZones().size() + ")", lx, zoneHeaderY);

        drawHdr(gui, "❖ 放置元数据", lx, metaStartY);
        drawLbl(gui, "ID", lx + 4, metaStartY + 14);
        drawLbl(gui, "Name", lx + 165, metaStartY + 14);
        drawLbl(gui, "Comfort", lx + COL2, metaLabelY - 10);
        drawLbl(gui, "Magic", lx + COL2 + FW + 16, metaLabelY - 10);
        drawLbl(gui, "Wonder", lx + COL2 + (FW + 16) * 2, metaLabelY - 10);

        drawHdr(gui, "❖ 解锁门槛", lx, unlockY);
        drawLbl(gui, "最低等级", lx + COL2, unlockY + ROW_H - 4);

        drawHdr(gui, "❖ 周期维护费", lx, maintCostY);

        if ("node".equals(scanner.getCategory())) {
            drawHdr(gui, "❖ 节点配置", lx, nodeCatY);
            drawLbl(gui, "元素", lx + COL2, nodeCatY + ROW_H - 4);
            drawLbl(gui, "产出/次", lx + COL2, nodeCatY + ROW_H * 2 - 4);
            drawLbl(gui, "引导Ticks", lx + COL2, nodeCatY + ROW_H * 3 - 4);
            drawLbl(gui, "魔力消耗", lx + COL2, nodeCatY + ROW_H * 4 - 4);
        }

        drawHdr(gui, "❖ 预设预存", lx, presetY);

        String cat = scanner.getCategory();
        if ("shop".equals(cat)) {
            drawHdr(gui, "❖ 商店参数", lx, shopCatY);
            drawLbl(gui, "利润率%", lx + COL2, shopCatY + ROW_H - 4);
            drawLbl(gui, "交互时长", lx + COL2, shopCatY + ROW_H * 2 - 4);
            drawHdr(gui, "❖ 上架商品", lx, goodsCatY);
        } else if ("service".equals(cat)) {
            drawHdr(gui, "❖ 服务参数", lx, svcCatY);
            drawLbl(gui, "能量消耗/次", lx + COL2, svcCatY + ROW_H - 4);
            drawLbl(gui, "最大容纳人数", lx + COL2, svcCatY + ROW_H * 2 - 2);
            drawLbl(gui, "交互时长", lx + COL2, svcCatY + ROW_H * 3 - 2);
            drawHdr(gui, "❖ 元素产出", lx, elemOutY);
        }

        drawHdr(gui, "❖ 蓝图与道路导出", lx, exportBtnY - 14);
        gui.drawString(font, scanResult, lx + 230, exportBtnY + 6, MedievalColors.TEXT_MUTED);

        gui.disableScissor();

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
