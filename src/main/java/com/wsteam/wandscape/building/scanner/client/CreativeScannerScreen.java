package com.wsteam.wandscape.building.scanner.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.wsteam.wandscape.building.data.BlockOffset;
import com.wsteam.wandscape.building.scanner.CreativeScannerBlockEntity;
import com.wsteam.wandscape.building.scanner.CreativeScannerBlockEntity.ShopGoodData;
import com.wsteam.wandscape.building.scanner.network.ScannerExportPacket;
import com.wsteam.wandscape.building.scanner.network.ScannerSyncPacket;
import com.wsteam.wandscape.building.scanner.network.ScannerValuePacket;
import com.wsteam.wandscape.shared.ui.I18n;
import com.wsteam.wandscape.shared.ui.component.MedievalScreen;
import com.wsteam.wandscape.shared.ui.theme.MedievalColors;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Scanner GUI built on MedievalScreen MINIMAL theme.
 * Features 6 boundary expand buttons (X±1, Y±1, Z±1), auto door detection & cycling,
 * strict Scissor clipping, edit box highlights, and custom drawMinimalBox buttons.
 */
public class CreativeScannerScreen extends MedievalScreen {

    private static final int PW = 360;
    private static final int PH = 1000;

    private final CreativeScannerBlockEntity scanner;

    // ── Custom Medieval Button Definition ──
    private record CustomButton(int x, int y, int w, int h, String text, Runnable action) {}
    private final List<CustomButton> customButtons = new ArrayList<>();

    // ── Structure Block Mode & Name ──
    private EditBox structureNameEdit;

    // ── Category ──
    private static final List<String> CATEGORIES = List.of(
            "basic", "government", "node", "storage", "workstation", "crafting_station",
            "magic_station", "tavern", "shop", "service", "decoration", "wonder", "altar",
            "relax", "atm", "custom"
    );

    // ── Door offset ──
    private EditBox doorX, doorY, doorZ;
    private int doorListY;

    // ── Metadata ──
    private EditBox metaId, metaName, metaCreator;
    private EditBox metaComfort, metaMagic, metaWonder;

    // ── Unlock requirement ──
    private EditBox unlockLevel;

    // ── Shop config (shown when category=shop) ──
    private EditBox shopProfitRate, shopDuration;

    // ── Service config (shown when category=service) ──
    private EditBox serviceEnergy, serviceMaxOcc, serviceDuration;

    // ── Relax config (shown when category=relax) ──
    private EditBox relaxEnergy, relaxDuration;

    // ── Atm config (shown when category=atm) ──
    private EditBox atmWithdraw, atmDuration;

    // ── Presets ──
    private EditBox presetNameEdit;
    private int presetY;

    // ── Scrolling ──
    private static final int SCROLL_TRAIL = 500;
    private int scrollOff = 0;
    private int maxScroll = 0;

    // ── Elements list for selectors ──
    private static final List<String> ELEMENTS = List.of("earth", "wood", "water", "fire", "metal", "wind", "dark");

    // ── Node config fields (category=node) ──
    private String currentNodeElem;
    private EditBox nodeAmount, nodeChannel;
    private int nodeCatY;

    // ── Shop goods rows (category=shop) ──
    private int goodsCatY;
    private final List<GoodRow> goodRows = new ArrayList<>();

    // ── Service element output rows (category=service) ──
    private int elemOutY;
    private final List<CostRow> elemOutRows = new ArrayList<>();

    // ── Export ──
    private Component scanResult = I18n.name("gui.wandscape.scanner.result_initial", "尚未扫描");

    // ── Layout Y positions (computed in init, used in render) ──
    private int lx; // left edge for widgets (leftPos + 16)
    private int boundaryCardY;
    private int boundaryEditY;
    private EditBox bMinX, bMinY, bMinZ, bMaxX, bMaxY, bMaxZ;
    private int doorEditY;
    private int spotHeaderY;
    private int spotMarkerCount;
    private int metaStartY, metaCreatorY, metaLabelY;
    private int unlockY;
    private int shopCatY, svcCatY, relaxCatY, atmCatY;
    private int exportBtnY;

    // ── Field Background Inset Rectangles with EditBox reference ──
    private record FieldRect(int x, int y, int w, int h, EditBox box) {}
    private final List<FieldRect> insetFields = new ArrayList<>();

    // ── Column layout constants (Spacious: max right edge <= lx + 320) ──
    private static final int COL2 = 60;  // input fields start here
    private static final int FW = 48;    // default field width
    private static final int ROW_H = 24; // vertical row spacing

    public CreativeScannerScreen(CreativeScannerBlockEntity scanner) {
        super(Component.literal("Creative Building Scanner"), PW, PH);
        setTitleBar(I18n.name("gui.wandscape.scanner.title", "创造建筑扫描器"));
        this.showCloseButton = true;
        this.showHelpButton = true;
        this.helpDocumentPath = "creative_scanner_guide";
        this.scanner = scanner;
    }

    /** Package-private accessor for the renderer. */
    CreativeScannerBlockEntity getScanner() { return scanner; }

    private void addCustomButton(int x, int y, int w, int h, String text, Runnable action) {
        customButtons.add(new CustomButton(x, y, w, h, text, action));
    }

    // ── init / rebuild ──

    @Override
    protected void init() {
        super.init();
        customButtons.clear();
        insetFields.clear();
        goodRows.clear();
        elemOutRows.clear();

        int cx = leftPos + PW / 2;
        lx = leftPos + 16;
        int y = topPos + headerHeight + 10 + scrollOff;

        // ── Toolbar Row 1: Mode & Structure Name (Right edge: lx + 320) ──
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

        if (scanner.getBlockMode() == CreativeScannerBlockEntity.BlockMode.CORNER) {
            // CORNER mode: simplified UI
            addCustomButton(cx - 50, y + 60, 100, 22, I18n.name("gui.wandscape.scanner.done", "完成").getString(), this::onClose);
            return;
        }

        // ── ROAD Target Mode: Ultra-clean UI (Only Road Info, Export & Hot-register) ──
        if (scanner.getTargetMode() == CreativeScannerBlockEntity.TargetMode.ROAD) {
            addCustomButton(lx, y, 145, 20, "Target: ROAD", () -> {
                scanner.setTargetMode(CreativeScannerBlockEntity.TargetMode.BUILDING);
                syncToServer();
                needsRebuild = true;
            });

            addCustomButton(lx + 155, y, 165, 20, I18n.name("gui.wandscape.scanner.match_corners", "❖ 匹配角点").getString(), () -> {
                syncToServer();
                needsRebuild = true;
            });
            y += 28;

            // Boundary Card Row
            boundaryCardY = y;
            y += 10;

            // Boundary min/max edit rows (top=min, bottom=max)
            boundaryEditY = y;
            addBoundaryEdits(y);
            y += ROW_H * 2 + 14;

            // Road Preset Identity (ID & Display Name)
            addSectionHeader(y, I18n.name("gui.wandscape.scanner.road_preset_header", "❖ 道路预设属性 (Road Preset)").getString());
            y += 16;
            metaStartY = y - 14;

            metaId = mkEdit(lx + 4, y + 14, 155, scanner.getBuildingId().isEmpty() ? "wandscape:custom_road" : scanner.getBuildingId(), s -> {
                scanner.setBuildingId(s);
                syncToServer();
            });
            metaName = mkEdit(lx + 165, y + 14, 155, scanner.getDisplayName().isEmpty() ? I18n.name("gui.wandscape.scanner.custom_road", "自定义道路").getString() : scanner.getDisplayName(), s -> {
                scanner.setDisplayName(s);
                syncToServer();
            });
            y += 40;

            // Export section
            addSectionHeader(y, I18n.name("gui.wandscape.scanner.road_export_header", "❖ 道路 JSON 导出与热注册").getString());
            y += 18;
            exportBtnY = y - 14;

            addCustomButton(lx + 4, exportBtnY, 95, 22, I18n.name("gui.wandscape.scanner.scan_area", "扫描区域").getString(), () -> doScan());
            addCustomButton(lx + 105, exportBtnY, 215, 22, I18n.name("gui.wandscape.scanner.export_hotregister_road", "导出与热注册道路 JSON").getString(), () -> doExport());

            int bottom = exportBtnY + 60;
            int visibleBottom = Math.min(topPos + PH - 6, height - 12);
            maxScroll = -10000;
            return;
        }

        // ── BUILDING Target Mode: Complete Building Configuration ──
        addCustomButton(lx, y, 110, 20, "Target: BUILDING", () -> {
            scanner.setTargetMode(CreativeScannerBlockEntity.TargetMode.ROAD);
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

        addCustomButton(lx + 220, y, 100, 20, I18n.name("gui.wandscape.scanner.match_corners", "❖ 匹配角点").getString(), () -> {
            syncToServer();
            needsRebuild = true;
        });
        y += 28;

        // ── Boundary Card Row ──
        boundaryCardY = y;
        y += 10;

        // Boundary min/max edit rows (top=min, bottom=max)
        boundaryEditY = y;
        addBoundaryEdits(y);
        y += ROW_H * 2 + 14;

        // ── Door section ──
        addSectionHeader(y, I18n.name("gui.wandscape.scanner.door_offset_header", "❖ 门偏移 (Door Offset)").getString());
        y += 16;
        doorEditY = y + ROW_H - 4;
        y = doorEditY + ROW_H + 8;
        doorListY = y;
        y += 26;

        doorX = mkEdit(lx + COL2, doorEditY, FW, loadDoorStr(0), s -> onDoorChanged());
        doorY = mkEdit(lx + COL2 + FW + 4, doorEditY, FW, loadDoorStr(1), s -> onDoorChanged());
        doorZ = mkEdit(lx + COL2 + (FW + 4) * 2, doorEditY, FW, loadDoorStr(2), s -> onDoorChanged());
        addCustomButton(lx + 218, doorEditY, 44, 20, I18n.name("gui.wandscape.scanner.clear", "清除").getString(), () -> {
            scanner.clearDoorOffsets();
            doorX.setValue(""); doorY.setValue(""); doorZ.setValue("");
            syncToServer();
        });
        addCustomButton(lx + 266, doorEditY, 54, 20, I18n.name("gui.wandscape.scanner.auto_detect_door", "自动检门").getString(), this::onAutoDetectDoor);

        // ── Interact spots (marker-driven) section ──
        spotMarkerCount = countSpotMarkers();
        addSectionHeader(y, I18n.name("gui.wandscape.scanner.interact_spots_count_header", "❖ 交互位 (%s)", spotMarkerCount).getString());
        spotHeaderY = y - 14;
        y += 16;
        y += ROW_H * 2;   // 两行提示文案
        if (isTouristCategory(scanner.getCategory()) && spotMarkerCount == 0) {
            y += ROW_H;   // 无交互位警告
        }
        y += 8;

        // ── Metadata section ──
        addSectionHeader(y, I18n.name("gui.wandscape.scanner.placement_meta_header", "❖ 放置元数据").getString());
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
        y += ROW_H + 2;
        metaCreatorY = y;
        metaCreator = mkEdit(lx + 4, y + 14, 310, scanner.getCreator(), s -> {
            scanner.setCreator(s);
            syncToServer();
        });
        y += ROW_H + 8;

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
        addSectionHeader(y, I18n.name("gui.wandscape.scanner.unlock_requirement_header", "❖ 解锁门槛").getString());
        y += 16;
        unlockY = y - 14;
        unlockLevel = mkNumEdit(lx + COL2 + 70, y, 50, scanner.getUnlockMinLevel(), s -> {
            scanner.setUnlockMinLevel(Math.max(1, intOrZero(s)));
            syncToServer();
        });
        y += ROW_H + 8;

        // ── Node config (category=node) ──
        String cat = scanner.getCategory();
        if ("node".equals(cat)) {
            addSectionHeader(y, I18n.name("gui.wandscape.scanner.node_config_header", "❖ 节点配置").getString());
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
            y += ROW_H + 8;
        } else {
            nodeCatY = 0;
            nodeAmount = null; nodeChannel = null;
        }

        // ── Presets section ──
        addSectionHeader(y, I18n.name("gui.wandscape.scanner.presets_header", "❖ 预设预存").getString());
        y += 16;
        presetY = y - 14;
        presetNameEdit = mkEdit(lx + 4, y, 110, "", s -> {});
        addCustomButton(lx + 120, y, 65, 20, I18n.name("gui.wandscape.scanner.save_preset", "保存预设").getString(), this::onPresetSave);
        addCustomButton(lx + 190, y, 65, 20, I18n.name("gui.wandscape.scanner.load_preset", "加载预设").getString(), this::onPresetLoad);
        addCustomButton(lx + 260, y, 55, 20, I18n.name("gui.wandscape.scanner.delete_preset", "删除预设").getString(), this::onPresetDelete);
        y += ROW_H;

        // Preset name quick-load buttons (directly clickable to load)
        int px = lx + 4;
        for (String pn : ScannerPresetStore.listPresets()) {
            int bw = Math.min(font.width(pn) + 12, 120);
            if (px + bw > lx + 320) { px = lx + 4; y += 22; }
            addCustomButton(px, y, bw, 18, pn, () -> loadPresetByName(pn));
            px += bw + 4;
        }
        y += 22 + 8;

        // ── Category-specific sections ──
        if ("shop".equals(cat)) {
            svcCatY = 0;
            addSectionHeader(y, I18n.name("gui.wandscape.scanner.shop_params_header", "❖ 商店参数").getString());
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

            addSectionHeader(y, I18n.name("gui.wandscape.scanner.shop_goods_count_header", "❖ 上架商品 (%s)", scanner.getShopGoods().size()).getString());
            y += 16;
            goodsCatY = y - 14;

            addCustomButton(lx + 240, y - 15, 80, 20, I18n.name("gui.wandscape.scanner.add_good", "+ 添加商品").getString(), () -> {
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
            addSectionHeader(y, I18n.name("gui.wandscape.scanner.service_params_header", "❖ 服务参数").getString());
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

            addSectionHeader(y, I18n.name("gui.wandscape.scanner.element_output_header", "❖ 元素产出").getString());
            y += 16;
            elemOutY = y - 14;

            addCustomButton(lx + 240, y - 15, 80, 20, I18n.name("gui.wandscape.scanner.add_output", "+ 添加产出").getString(), () -> {
                String el = nextUnusedElement(scanner.getServiceElementOutput());
                if (el != null) {
                    scanner.addServiceElementOutput(el, 1);
                    syncToServer();
                    needsRebuild = true;
                }
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
        } else if ("relax".equals(cat)) {
            shopCatY = 0;
            goodsCatY = 0;
            shopProfitRate = null;
            shopDuration = null;
            svcCatY = 0;
            elemOutY = 0;
            addSectionHeader(y, I18n.name("gui.wandscape.scanner.relax_params_header", "❖ 放松参数").getString());
            y += 16;
            relaxCatY = y - 14;

            relaxEnergy = mkNumEdit(lx + COL2 + 90, y, 50, scanner.getRelaxEnergyRestore(), s -> {
                scanner.setRelaxEnergyRestore(intOrZero(s));
                syncToServer();
            });
            y += ROW_H + 2;

            relaxDuration = mkNumEdit(lx + COL2 + 90, y, 50, scanner.getRelaxInteractionDurationTicks(), s -> {
                scanner.setRelaxInteractionDurationTicks(intOrZero(s));
                syncToServer();
            });
            y += ROW_H + 8;
        } else if ("atm".equals(cat)) {
            shopCatY = 0;
            goodsCatY = 0;
            shopProfitRate = null;
            shopDuration = null;
            svcCatY = 0;
            elemOutY = 0;
            addSectionHeader(y, I18n.name("gui.wandscape.scanner.atm_params_header", "❖ ATM 参数").getString());
            y += 16;
            atmCatY = y - 14;

            atmWithdraw = mkNumEdit(lx + COL2 + 90, y, 50, scanner.getAtmWithdrawAmount(), s -> {
                scanner.setAtmWithdrawAmount(intOrZero(s));
                syncToServer();
            });
            y += ROW_H + 2;

            atmDuration = mkNumEdit(lx + COL2 + 90, y, 50, scanner.getAtmInteractionDurationTicks(), s -> {
                scanner.setAtmInteractionDurationTicks(intOrZero(s));
                syncToServer();
            });
            y += ROW_H + 8;
        } else {
            shopCatY = 0;
            goodsCatY = 0;
            shopProfitRate = null;
            shopDuration = null;
            svcCatY = 0;
            elemOutY = 0;
        }

        // ── Export section ──
        addSectionHeader(y, I18n.name("gui.wandscape.scanner.blueprint_export_header", "❖ 蓝图与道路导出").getString());
        y += 18;
        exportBtnY = y - 14;

        addCustomButton(lx + 4, exportBtnY, 95, 22, I18n.name("gui.wandscape.scanner.scan_area", "扫描区域").getString(), () -> doScan());
        addCustomButton(lx + 105, exportBtnY, 215, 22, I18n.name("gui.wandscape.scanner.export_building_json", "导出建筑 JSON").getString(), () -> doExport());
        addCustomButton(lx + 4, exportBtnY + 26, 170, 22, I18n.name("gui.wandscape.scanner.calc_area_value", "计算区域价值").getString(), () -> doValue());

        int bottom = exportBtnY + 60;
        int visibleBottom = Math.min(topPos + PH - 6, height - 12);
        maxScroll = -10000;
    }

    private void onAutoDetectDoor() {
        if (minecraft == null || minecraft.level == null) return;
        List<BlockOffset> doors = scanner.detectDoors(minecraft.level);
        if (doors.isEmpty()) {
            scanResult = I18n.name("gui.wandscape.scanner.result_no_door_found", "未在包围盒内检测到门方块");
            return;
        }
        scanner.setDoorOffsets(doors);
        BlockOffset first = doors.get(0);
        if (doorX != null) doorX.setValue(String.valueOf(first.x()));
        if (doorY != null) doorY.setValue(String.valueOf(first.y()));
        if (doorZ != null) doorZ.setValue(String.valueOf(first.z()));
        scanResult = I18n.name("gui.wandscape.scanner.result_doors_found", "已检门 %s 扇，游客从任意一扇门进（编辑框仅改首门）", doors.size());
        syncToServer();
    }

    private void addSectionHeader(int y, String title) {
        // Layout marker only
    }

    private static boolean isTouristCategory(String c) {
        return "shop".equals(c) || "service".equals(c) || "relax".equals(c) || "atm".equals(c);
    }

    /** 扫 boundary 内 interact_spot_marker 数量（client，一次 rebuild 算一次）。 */
    private int countSpotMarkers() {
        var level = scanner.getLevel();
        if (level == null) return 0;
        BlockPos wMin = scanner.getWorldMin();
        BlockPos wMax = scanner.getWorldMax();
        if (wMin == null || wMax == null) return 0;
        int count = 0;
        for (int x = wMin.getX(); x <= wMax.getX(); x++) {
            for (int y = wMin.getY(); y <= wMax.getY(); y++) {
                for (int z = wMin.getZ(); z <= wMax.getZ(); z++) {
                    if (level.getBlockState(new BlockPos(x, y, z))
                            .is(com.wsteam.wandscape.Wandscape.INTERACT_SPOT_MARKER.get())) {
                        count++;
                    }
                }
            }
        }
        return count;
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

    private EditBox mkNumEdit(int x, int y, int w, int val, Consumer<String> r) {
        EditBox box = new EditBox(font, x + 3, y + 3, w - 6, 14, Component.empty());
        box.setFilter(s -> s.matches("\\d*"));
        box.setValue(String.valueOf(val));
        box.setBordered(false);
        box.setTextColor(MedievalColors.TEXT_WARM_WHITE);
        box.setTextColorUneditable(MedievalColors.TEXT_MUTED);
        box.setResponder(r);
        insetFields.add(new FieldRect(x, y, w, 20, box));
        return addRenderableWidget(box);
    }

    // ── Inner classes for rows ──

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
        String sizeText = I18n.name("gui.wandscape.scanner.boundary_size", "尺寸 %d×%d×%d  (Min:%d,%d,%d Max:%d,%d,%d)", dx, dy, dz, bMin.x(), bMin.y(), bMin.z(), bMax.x(), bMax.y(), bMax.z()).getString();
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
            return; // 输入不完整时不动门列表，避免误清空
        }
        try {
            BlockOffset off = BlockOffset.of(
                    Integer.parseInt(xs), Integer.parseInt(ys), Integer.parseInt(zs));
            if (scanner.getDoorOffsets().isEmpty()) {
                scanner.setDoorOffsets(List.of(off));
            } else {
                scanner.updateDoorOffset(0, off); // 就地改首门，保留其余门
            }
            syncToServer();
        } catch (NumberFormatException e) {
            // ignore partial input
        }
    }

    /** 门区只读列表：无门提示 / 列出全部已记录门的偏移。 */
    private void drawDoorList(GuiGraphics gui) {
        List<BlockOffset> doors = scanner.getDoorOffsets();
        if (doors.isEmpty()) {
            gui.drawString(font, I18n.name("gui.wandscape.scanner.no_door_hint", "未设门：游客入口走包围盒外扫描").getString(), lx + 4, doorListY, MedievalColors.TEXT_MUTED);
            return;
        }
        StringBuilder sb = new StringBuilder(I18n.name("gui.wandscape.scanner.door_count", "%s 扇门: ", doors.size()).getString());
        for (BlockOffset d : doors) {
            sb.append("(").append(d.x()).append(",").append(d.y()).append(",").append(d.z()).append(") ");
        }
        gui.drawString(font, sb.toString(), lx + 4, doorListY, MedievalColors.TEXT_MUTED);
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
            scanResult = I18n.name("gui.wandscape.scanner.result_no_boundary", "未定义 3D 边界");
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
                        // Auto-filter both scanner blocks (creative + survival)
                        if (state.is(com.wsteam.wandscape.Wandscape.BUILDING_SCANNER.get())
                                || state.is(com.wsteam.wandscape.Wandscape.CREATIVE_BUILDING_SCANNER.get())) continue;
                        if (!state.isAir()) {
                            count++;
                        }
                    }
                }
            }
        }
        scanResult = I18n.name("gui.wandscape.scanner.result_scanned", "已扫描 %s 个有效方块 (不含扫描器)", count);
    }

    private void doExport() {
        String id = scanner.getBuildingId();
        if (id.isBlank()) {
            scanResult = I18n.name("gui.wandscape.scanner.result_need_id", "请先设置 ID");
            return;
        }
        PacketDistributor.sendToServer(new ScannerExportPacket(scanner.getBlockPos()));
        scanResult = I18n.name("gui.wandscape.scanner.result_export_started", "已发起导出: %s", id);
    }

    private void doValue() {
        BlockPos wMin = scanner.getWorldMin();
        BlockPos wMax = scanner.getWorldMax();
        if (wMin == null || wMax == null) {
            scanResult = I18n.name("gui.wandscape.scanner.result_no_boundary", "未定义 3D 边界");
            return;
        }
        PacketDistributor.sendToServer(new ScannerValuePacket(scanner.getBlockPos()));
        scanResult = I18n.name("gui.wandscape.scanner.result_value_started", "已发起价值计算，结果见聊天区");
    }

    private void syncToServer() {
        if (minecraft == null || minecraft.level == null) return;
        CompoundTag tag = scanner.saveWithoutMetadata(minecraft.level.registryAccess());
        PacketDistributor.sendToServer(new ScannerSyncPacket(scanner.getBlockPos(), tag));
    }

    private void onPresetSave() {
        if (presetNameEdit == null) return;
        String name = presetNameEdit.getValue().trim();
        if (name.isEmpty()) {
            scanResult = I18n.name("gui.wandscape.scanner.result_need_preset_name", "请先输入预设名");
            return;
        }
        ScannerPresetStore.savePreset(name, capturePresetData());
        scanResult = I18n.name("gui.wandscape.scanner.result_preset_saved", "预设已保存: %s", name);
        needsRebuild = true;
    }

    private void onPresetLoad() {
        if (presetNameEdit == null) return;
        String name = presetNameEdit.getValue().trim();
        if (name.isEmpty()) return;
        loadPresetByName(name);
    }

    private void onPresetDelete() {
        if (presetNameEdit == null) return;
        String name = presetNameEdit.getValue().trim();
        if (name.isEmpty()) return;
        ScannerPresetStore.deletePreset(name);
        scanResult = I18n.name("gui.wandscape.scanner.result_preset_deleted", "预设已删除: %s", name);
        needsRebuild = true;
    }

    private void loadPresetByName(String name) {
        CompoundTag tag = ScannerPresetStore.loadPreset(name);
        if (tag == null) {
            scanResult = I18n.name("gui.wandscape.scanner.result_preset_not_found", "未找到预设: %s", name);
            return;
        }
        applyPresetData(tag);
        syncToServer();
        needsRebuild = true;
        scanResult = I18n.name("gui.wandscape.scanner.result_preset_loaded", "预设已加载: %s", name);
    }

    private CompoundTag capturePresetData() {
        CompoundTag tag = new CompoundTag();
        BlockOffset bMin = scanner.getBoundaryMin();
        BlockOffset bMax = scanner.getBoundaryMax();
        tag.putIntArray("boundary_min", new int[]{bMin.x(), bMin.y(), bMin.z()});
        tag.putIntArray("boundary_max", new int[]{bMax.x(), bMax.y(), bMax.z()});

        if (!scanner.getDoorOffsets().isEmpty()) {
            ListTag doorList = new ListTag();
            for (BlockOffset d : scanner.getDoorOffsets()) {
                doorList.add(new IntArrayTag(new int[]{d.x(), d.y(), d.z()}));
            }
            tag.put("door_offsets", doorList);
        }

        tag.putString("building_id", scanner.getBuildingId());
        tag.putString("display_name", scanner.getDisplayName());
        tag.putString("creator", scanner.getCreator());
        tag.putString("category", scanner.getCategory());
        tag.putInt("comfort", scanner.getComfort());
        tag.putInt("magic", scanner.getMagic());
        tag.putInt("wonder", scanner.getWonder());
        tag.putInt("unlock_min_level", scanner.getUnlockMinLevel());

        CompoundTag ncTag = new CompoundTag();
        ncTag.putString("blueprint", scanner.getNodeBlueprint());
        ncTag.putString("element", scanner.getNodeElement());
        ncTag.putInt("amount_per_harvest", scanner.getNodeAmountPerHarvest());
        ncTag.putInt("channel_ticks", scanner.getNodeChannelTicks());
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

        tag.putInt("relax_energy_restore", scanner.getRelaxEnergyRestore());
        tag.putInt("relax_duration", scanner.getRelaxInteractionDurationTicks());
        tag.putInt("atm_withdraw_amount", scanner.getAtmWithdrawAmount());
        tag.putInt("atm_duration", scanner.getAtmInteractionDurationTicks());

        return tag;
    }

    private void applyPresetData(CompoundTag tag) {
        if (tag.contains("category")) scanner.setCategory(tag.getString("category"));
        if (tag.contains("building_id")) scanner.setBuildingId(tag.getString("building_id"));
        if (tag.contains("display_name")) scanner.setDisplayName(tag.getString("display_name"));
        if (tag.contains("creator")) scanner.setCreator(tag.getString("creator"));
        if (tag.contains("comfort")) scanner.setComfort(tag.getInt("comfort"));
        if (tag.contains("magic")) scanner.setMagic(tag.getInt("magic"));
        if (tag.contains("wonder")) scanner.setWonder(tag.getInt("wonder"));
        if (tag.contains("unlock_min_level")) scanner.setUnlockMinLevel(tag.getInt("unlock_min_level"));

        if (tag.contains("door_offsets", Tag.TAG_LIST)) {
            ListTag doorList = tag.getList("door_offsets", Tag.TAG_INT_ARRAY);
            List<BlockOffset> loaded = new ArrayList<>();
            for (int i = 0; i < doorList.size(); i++) {
                int[] arr = doorList.getIntArray(i);
                if (arr.length == 3) loaded.add(BlockOffset.of(arr[0], arr[1], arr[2]));
            }
            scanner.setDoorOffsets(loaded);
        } else if (tag.contains("door_offset", Tag.TAG_INT_ARRAY)) {
            int[] arr = tag.getIntArray("door_offset");
            if (arr.length == 3) {
                scanner.setDoorOffsets(List.of(BlockOffset.of(arr[0], arr[1], arr[2])));
            } else {
                scanner.clearDoorOffsets();
            }
        } else {
            scanner.clearDoorOffsets();
        }

        if (tag.contains("node_config", Tag.TAG_COMPOUND)) {
            CompoundTag nc = tag.getCompound("node_config");
            if (nc.contains("element")) scanner.setNodeElement(nc.getString("element"));
            if (nc.contains("amount_per_harvest")) scanner.setNodeAmountPerHarvest(nc.getInt("amount_per_harvest"));
            if (nc.contains("channel_ticks")) scanner.setNodeChannelTicks(nc.getInt("channel_ticks"));
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

        // Render inset dark field backgrounds with glowing gold borders for all edit boxes inside scissor
        for (FieldRect f : insetFields) {
            if (f.y() + f.h() > clipTop && f.y() < clipBottom) {
                boolean focused = f.box() != null && f.box().isFocused();
                boolean hover = isInRect(mx, my, f.x(), f.y(), f.w(), f.h());
                drawEditBoxBorder(gui, f.x(), f.y(), f.w(), f.h(), focused, hover);
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
        gui.drawString(font, I18n.name("gui.wandscape.scanner.structure_name_label", "暗号").getString(), lx + 94, topY + 6, MedievalColors.TEXT_MUTED);

        if (scanner.getBlockMode() == CreativeScannerBlockEntity.BlockMode.CORNER) {
            drawMinimalBox(gui, lx, topPos + headerHeight + 38, 320, 64, true, false);
            gui.drawString(font, I18n.name("gui.wandscape.scanner.corner_mode_title", "❖ CORNER 辅角点模式").getString(), lx + 10, topPos + headerHeight + 46, MedievalColors.BORDER_GOLD);
            gui.drawString(font, I18n.name("gui.wandscape.scanner.corner_line1", "1. 请在上方输入与 SAVE 扫描器相同的暗号。").getString(), lx + 10, topPos + headerHeight + 60, MedievalColors.TEXT_WARM_WHITE);
            gui.drawString(font, I18n.name("gui.wandscape.scanner.corner_line2", "2. 将此方块放置在建筑 3D 对角线的另一个顶点位置。").getString(), lx + 10, topPos + headerHeight + 74, MedievalColors.TEXT_MUTED);
            gui.disableScissor();
            super.render(gui, mx, my, pt);
            return;
        }

        // ── ROAD Target Mode Render ──
        if (scanner.getTargetMode() == CreativeScannerBlockEntity.TargetMode.ROAD) {
            drawBoundaryLabels(gui);

            drawHdr(gui, I18n.name("gui.wandscape.scanner.road_preset_header", "❖ 道路预设属性 (Road Preset)").getString(), lx, metaStartY);
            drawLbl(gui, "Road ID", lx + 4, metaStartY + 14);
            drawLbl(gui, "Display Name", lx + 165, metaStartY + 14);

            drawHdr(gui, I18n.name("gui.wandscape.scanner.road_export_header", "❖ 道路 JSON 导出与热注册").getString(), lx, exportBtnY - 14);
            gui.drawString(font, scanResult, lx + 5, exportBtnY + 28, MedievalColors.TEXT_MUTED);

            gui.disableScissor();
            super.render(gui, mx, my, pt);
            return;
        }

        // ── BUILDING Target Mode Render ──
        drawBoundaryLabels(gui);

        // Section Headers & Labels
        drawHdr(gui, I18n.name("gui.wandscape.scanner.door_offset_count_header", "❖ 门偏移 (%s 扇)", scanner.getDoorOffsets().size()).getString(), lx, doorEditY - 14);
        drawLbl(gui, "X", lx + COL2, doorEditY - 10);
        drawLbl(gui, "Y", lx + COL2 + FW + 4, doorEditY - 10);
        drawLbl(gui, "Z", lx + COL2 + (FW + 4) * 2, doorEditY - 10);
        drawDoorList(gui);

        drawHdr(gui, I18n.name("gui.wandscape.scanner.interact_spots_count_header", "❖ 交互位 (%s)", spotMarkerCount).getString(), lx, spotHeaderY);
        gui.drawString(font, I18n.name("gui.wandscape.scanner.interact_spot_hint", "放置 marker 标记交互位（面朝游客朝向）；右键循环动作，潜行右键循环朝向，敲掉=移除。").getString(),
                lx + 4, spotHeaderY + 16, MedievalColors.TEXT_MUTED);
        if (isTouristCategory(scanner.getCategory()) && spotMarkerCount == 0) {
            gui.drawString(font, I18n.name("gui.wandscape.scanner.no_interact_spot_warning", "§c无交互位 = 游客不选该建筑").getString(),
                    lx + 4, spotHeaderY + 30, MedievalColors.TEXT_MUTED);
        }

        drawHdr(gui, I18n.name("gui.wandscape.scanner.placement_meta_header", "❖ 放置元数据").getString(), lx, metaStartY);
        drawLbl(gui, "ID", lx + 4, metaStartY + 14);
        drawLbl(gui, "Name", lx + 165, metaStartY + 14);
        drawLbl(gui, "Creator", lx + 4, metaCreatorY);
        drawLbl(gui, "Comfort", lx + COL2, metaLabelY - 10);
        drawLbl(gui, "Magic", lx + COL2 + FW + 16, metaLabelY - 10);
        drawLbl(gui, "Wonder", lx + COL2 + (FW + 16) * 2, metaLabelY - 10);

        drawHdr(gui, I18n.name("gui.wandscape.scanner.unlock_requirement_header", "❖ 解锁门槛").getString(), lx, unlockY);
        drawLbl(gui, I18n.name("gui.wandscape.scanner.label_min_level", "最低等级").getString(), lx + COL2, unlockY + ROW_H - 4);

        if ("node".equals(scanner.getCategory())) {
            drawHdr(gui, I18n.name("gui.wandscape.scanner.node_config_header", "❖ 节点配置").getString(), lx, nodeCatY);
            drawLbl(gui, I18n.name("gui.wandscape.scanner.label_element", "元素").getString(), lx + COL2, nodeCatY + ROW_H - 4);
            drawLbl(gui, I18n.name("gui.wandscape.scanner.label_output_per_use", "产出/次").getString(), lx + COL2, nodeCatY + ROW_H * 2 - 4);
            drawLbl(gui, I18n.name("gui.wandscape.scanner.label_channel_ticks", "引导Ticks").getString(), lx + COL2, nodeCatY + ROW_H * 3 - 4);
        }

        drawHdr(gui, I18n.name("gui.wandscape.scanner.presets_header", "❖ 预设预存").getString(), lx, presetY);

        String cat = scanner.getCategory();
        if ("shop".equals(cat)) {
            drawHdr(gui, I18n.name("gui.wandscape.scanner.shop_params_header", "❖ 商店参数").getString(), lx, shopCatY);
            drawLbl(gui, I18n.name("gui.wandscape.scanner.label_profit_rate", "利润率%").getString(), lx + COL2, shopCatY + ROW_H - 4);
            drawLbl(gui, I18n.name("gui.wandscape.scanner.label_interact_duration", "交互时长").getString(), lx + COL2, shopCatY + ROW_H * 2 - 4);
            drawHdr(gui, I18n.name("gui.wandscape.scanner.shop_goods_header", "❖ 上架商品").getString(), lx, goodsCatY);
        } else if ("service".equals(cat)) {
            drawHdr(gui, I18n.name("gui.wandscape.scanner.service_params_header", "❖ 服务参数").getString(), lx, svcCatY);
            drawLbl(gui, I18n.name("gui.wandscape.scanner.label_energy_per_use", "能量消耗/次").getString(), lx + COL2, svcCatY + ROW_H - 4);
            drawLbl(gui, I18n.name("gui.wandscape.scanner.label_max_occupancy", "最大容纳人数").getString(), lx + COL2, svcCatY + ROW_H * 2 - 2);
            drawLbl(gui, I18n.name("gui.wandscape.scanner.label_interact_duration", "交互时长").getString(), lx + COL2, svcCatY + ROW_H * 3 - 2);
            drawHdr(gui, I18n.name("gui.wandscape.scanner.element_output_header", "❖ 元素产出").getString(), lx, elemOutY);
        } else if ("relax".equals(cat)) {
            drawHdr(gui, I18n.name("gui.wandscape.scanner.relax_params_header", "❖ 放松参数").getString(), lx, relaxCatY);
            drawLbl(gui, I18n.name("gui.wandscape.scanner.label_energy_restore", "回精力/次").getString(), lx + COL2, relaxCatY + ROW_H - 4);
            drawLbl(gui, I18n.name("gui.wandscape.scanner.label_interact_duration", "交互时长").getString(), lx + COL2, relaxCatY + ROW_H * 2 - 4);
        } else if ("atm".equals(cat)) {
            drawHdr(gui, I18n.name("gui.wandscape.scanner.atm_params_header", "❖ ATM 参数").getString(), lx, atmCatY);
            drawLbl(gui, I18n.name("gui.wandscape.scanner.label_withdraw_limit", "取现上限").getString(), lx + COL2, atmCatY + ROW_H - 4);
            drawLbl(gui, I18n.name("gui.wandscape.scanner.label_interact_duration", "交互时长").getString(), lx + COL2, atmCatY + ROW_H * 2 - 4);
        }

        drawHdr(gui, I18n.name("gui.wandscape.scanner.blueprint_export_header", "❖ 蓝图与道路导出").getString(), lx, exportBtnY - 14);
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

    /** Returns the first element not yet present in the given cost map, or null if all are used. */
    private static String nextUnusedElement(Map<String, Integer> current) {
        for (String el : ELEMENTS) {
            if (!current.containsKey(el)) return el;
        }
        return null;
    }

    private static int intOrZero(String s) {
        if (s == null || s.isEmpty()) return 0;
        try { return Integer.parseInt(s); }
        catch (NumberFormatException e) { return 0; }
    }
}
