package com.wsteam.wandscape.building.scanner.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.wsteam.wandscape.building.data.BlockOffset;
import com.wsteam.wandscape.building.scanner.CreativeScannerBlockEntity;
import com.wsteam.wandscape.building.scanner.CreativeScannerBlockEntity.BlockMode;
import com.wsteam.wandscape.building.scanner.CreativeScannerBlockEntity.ShopGoodData;
import com.wsteam.wandscape.building.scanner.CreativeScannerBlockEntity.TargetMode;
import com.wsteam.wandscape.building.scanner.ScannerBlockEntity;
import com.wsteam.wandscape.building.scanner.client.gizmo.ScannerGizmoState;
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
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Unified Building Scanner Screen for both Creative and Survival modes.
 * Uses a clean 4-tab medieval architecture (Bounds, Properties, Presets, Export)
 * with robust multi-line wrapping, paged lists, 3D Gizmo integration, and zero text overlap.
 */
public class CreativeScannerScreen extends MedievalScreen {

    private static final int PW = 380;
    private static final int PH = 240;
    private static final int TAB_H = 18;

    protected final CreativeScannerBlockEntity scanner;
    protected final boolean isSurvival;

    // ── Active Tab (0: Bounds, 1: Properties, 2: Presets, 3: Export) ──
    private int activeTab = 0;

    // ── Custom Medieval Button Record ──
    private record CustomButton(int x, int y, int w, int h, String text, Runnable action, boolean enabled) {}
    private final List<CustomButton> customButtons = new ArrayList<>();

    // ── Field Background Insets for glowing EditBoxes ──
    private record FieldRect(int x, int y, int w, int h, EditBox box) {}
    private final List<FieldRect> insetFields = new ArrayList<>();

    // ── Categories & Elements Definitions ──
    public record CategoryDef(String id, String labelKey, String defaultLabel, String icon) {
        public String label() {
            return I18n.string(labelKey, defaultLabel);
        }
    }
    private static final List<CategoryDef> CATEGORIES = List.of(
            new CategoryDef("basic", "category.wandscape.basic", "基础建筑", "🏠"),
            new CategoryDef("government", "category.wandscape.government", "政务市政", "🏛️"),
            new CategoryDef("node", "category.wandscape.node", "采集节点", "⛏️"),
            new CategoryDef("storage", "category.wandscape.storage", "仓库存储", "📦"),
            new CategoryDef("workstation", "category.wandscape.workstation", "工作工坊", "🔨"),
            new CategoryDef("crafting_station", "category.wandscape.crafting_station", "物品合成", "⚙️"),
            new CategoryDef("magic_station", "category.wandscape.magic_station", "魔法工坊", "🧪"),
            new CategoryDef("tavern", "category.wandscape.tavern", "冒险酒馆", "🍺"),
            new CategoryDef("shop", "category.wandscape.shop", "商业商店", "🏪"),
            new CategoryDef("service", "category.wandscape.service", "市民服务", "🛎️"),
            new CategoryDef("decoration", "category.wandscape.decoration", "城镇装饰", "🌳"),
            new CategoryDef("wonder", "category.wandscape.wonder", "奇观奇迹", "✨"),
            new CategoryDef("altar", "category.wandscape.altar", "元素祭坛", "🔮"),
            new CategoryDef("relax", "category.wandscape.relax", "休闲放松", "☕"),
            new CategoryDef("atm", "category.wandscape.atm", "钱庄ATM", "💰"),
            new CategoryDef("custom", "category.wandscape.custom", "自定义", "🛠️")
    );

    public record ElementDef(String id, String labelKey, String defaultLabel, String symbol) {
        public String label() {
            return I18n.string(labelKey, defaultLabel);
        }
    }
    private static final List<ElementDef> ELEMENTS = List.of(
            new ElementDef("earth", "element.wandscape.earth", "土 Earth", "🟤"),
            new ElementDef("wood", "element.wandscape.wood", "木 Wood", "🟢"),
            new ElementDef("water", "element.wandscape.water", "水 Water", "🔵"),
            new ElementDef("fire", "element.wandscape.fire", "火 Fire", "🔴"),
            new ElementDef("metal", "element.wandscape.metal", "金 Metal", "🟡"),
            new ElementDef("wind", "element.wandscape.wind", "风 Wind", "⚪"),
            new ElementDef("dark", "element.wandscape.dark", "暗 Dark", "🟣")
    );

    // ── Shared EditBoxes ──
    private EditBox structureNameEdit;

    // ── Tab 0: Bounds EditBoxes ──
    private EditBox bMinX, bMinY, bMinZ;
    private EditBox bMaxX, bMaxY, bMaxZ;
    private EditBox doorX, doorY, doorZ;

    // ── Tab 1: Metadata EditBoxes ──
    private EditBox metaId, metaName, metaCreator;
    private EditBox metaComfort, metaMagic, metaWonder, unlockLevel;

    // Category-specific inputs (Creative only)
    private EditBox shopProfitRate, shopDuration;
    private EditBox serviceEnergy, serviceMaxOcc, serviceDuration;
    private EditBox relaxEnergy, relaxDuration;
    private EditBox atmWithdraw, atmDuration;
    private EditBox nodeAmount, nodeChannel;

    // Paged item collections
    private int shopGoodsPage = 0;
    private static final int GOODS_PER_PAGE = 3;

    private int serviceElemPage = 0;
    private static final int ELEM_PER_PAGE = 3;

    // ── Tab 2: Presets ──
    private EditBox presetNameEdit;
    private int presetsPage = 0;
    private static final int PRESETS_PER_PAGE = 4;

    // ── Execution Result Message ──
    private Component scanResult = I18n.name("gui.wandscape.scanner.result_initial", "尚未扫描");
    private boolean needsRebuild = false;

    public CreativeScannerScreen(CreativeScannerBlockEntity scanner) {
        this(scanner, scanner instanceof ScannerBlockEntity);
    }

    public CreativeScannerScreen(CreativeScannerBlockEntity scanner, boolean isSurvival) {
        super(Component.literal("Building Scanner"), PW, PH);
        this.scanner = scanner;
        this.isSurvival = isSurvival;
        if (isSurvival) {
            setTitleBar(I18n.name("gui.wandscape.scanner.title_survival", "建筑扫描器 (生存)"));
            this.helpDocumentPath = "scanner_guide";
        } else {
            setTitleBar(I18n.name("gui.wandscape.scanner.title", "创造建筑扫描器"));
            this.helpDocumentPath = "creative_scanner_guide";
        }
        this.showCloseButton = true;
        this.showHelpButton = true;
    }

    public CreativeScannerBlockEntity getScanner() {
        return scanner;
    }

    public boolean isSurvival() {
        return isSurvival;
    }

    private void addBtn(int x, int y, int w, int h, String text, Runnable action) {
        customButtons.add(new CustomButton(x, y, w, h, text, action, true));
    }

    private void addBtn(int x, int y, int w, int h, String text, Runnable action, boolean enabled) {
        customButtons.add(new CustomButton(x, y, w, h, text, action, enabled));
    }

    @Override
    protected void init() {
        super.init();
        customButtons.clear();
        insetFields.clear();

        int lx = leftPos + 8;
        int contentY = topPos + headerHeight + TAB_H + 6;

        // ── CORNER Mode View ──
        if (scanner.getBlockMode() == BlockMode.CORNER) {
            initCornerMode(lx, topPos + headerHeight + 8);
            return;
        }

        // ── SAVE Mode Tab Content ──
        switch (activeTab) {
            case 0 -> initTab0Bounds(lx, contentY);
            case 1 -> initTab1Properties(lx, contentY);
            case 2 -> initTab2Presets(lx, contentY);
            case 3 -> initTab3Export(lx, contentY);
            default -> activeTab = 0;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // CORNER MODE INIT
    // ─────────────────────────────────────────────────────────────────────────────

    private void initCornerMode(int lx, int topY) {
        // Top Toolbar
        addBtn(lx, topY, 90, 18, I18n.string("gui.wandscape.scanner.mode_corner", "模式: CORNER ▾"), () -> {
            scanner.setBlockMode(BlockMode.SAVE);
            syncToServer();
            showFeedback(Component.literal("§e已切换到 SAVE 主扫描模式"), 0xFFD4A840);
            rebuild();
        });

        structureNameEdit = mkEdit(lx + 135, topY, 150, 18, scanner.getStructureName(), s -> {
            scanner.setStructureName(s);
            syncToServer();
        });

        // Done button
        addBtn(leftPos + (PW - 120) / 2, topPos + PH - 32, 120, 20,
                I18n.string("gui.wandscape.scanner.done", "✓ 完成"), this::onClose);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TAB 0: 📐 范围与结构 (Boundary & Structure)
    // ─────────────────────────────────────────────────────────────────────────────

    private void initTab0Bounds(int lx, int startY) {
        int y = startY;

        // Top Toolbar Row (Total width: 364)
        addBtn(lx, y, 80, 18, I18n.string("gui.wandscape.scanner.mode_save", "模式: SAVE ▾"), () -> {
            scanner.setBlockMode(BlockMode.CORNER);
            syncToServer();
            showFeedback(Component.literal("§e已切换到 CORNER 辅角点模式"), 0xFFD4A840);
            rebuild();
        });

        String targetLabel = scanner.getTargetMode() == TargetMode.BUILDING
                ? I18n.string("gui.wandscape.scanner.target_building", "目标: 建筑 ▾")
                : I18n.string("gui.wandscape.scanner.target_road", "目标: 道路 ▾");
        addBtn(lx + 84, y, 92, 18, targetLabel, () -> {
            TargetMode next = scanner.getTargetMode() == TargetMode.BUILDING ? TargetMode.ROAD : TargetMode.BUILDING;
            scanner.setTargetMode(next);
            syncToServer();
            showFeedback(Component.literal("§e已切换为 " + (next == TargetMode.BUILDING ? "建筑模式" : "道路模式")), 0xFFD4A840);
            rebuild();
        });

        structureNameEdit = mkEdit(lx + 180, y, 82, 18, scanner.getStructureName(), s -> {
            scanner.setStructureName(s);
            syncToServer();
        });

        addBtn(lx + 266, y, 98, 18, I18n.string("gui.wandscape.scanner.match_corners", "❖ 匹配角点"), () -> {
            if (minecraft != null && minecraft.level != null) {
                boolean matched = scanner.detectBoundaryFromCorners(minecraft.level);
                if (matched) {
                    BlockOffset bMin = scanner.getBoundaryMin();
                    BlockOffset bMax = scanner.getBoundaryMax();
                    int dx = Math.abs(bMax.x() - bMin.x()) + 1;
                    int dy = Math.abs(bMax.y() - bMin.y()) + 1;
                    int dz = Math.abs(bMax.z() - bMin.z()) + 1;
                    showFeedback(Component.literal(String.format("§a✓ 匹配成功！已更新 3D 边界 (%d×%d×%d)", dx, dy, dz)), 0xFF55FF55);
                    scanResult = Component.literal(String.format("§a已匹配角点并更新边界！尺寸: %d×%d×%d", dx, dy, dz));
                } else {
                    showFeedback(Component.literal("§c⚠ 未找到同名暗号的 CORNER 扫描器 (需在 64 格内)"), 0xFFFF5555);
                    scanResult = Component.literal("§e未找到同名暗号的 CORNER 扫描器。请确认暗号是否一致。");
                }
            }
            syncToServer();
            rebuild();
        });
        y += 24;

        // ── Card 1: 3D 边界坐标 (Min & Max) ──
        int cw = 36;
        int colMin = lx + 50;
        bMinX = mkCoordEdit(colMin, y + 16, cw, 16, scanner.getBoundaryMin().x(), this::onBoundaryEdit);
        bMinY = mkCoordEdit(colMin + cw + 4, y + 16, cw, 16, scanner.getBoundaryMin().y(), this::onBoundaryEdit);
        bMinZ = mkCoordEdit(colMin + (cw + 4) * 2, y + 16, cw, 16, scanner.getBoundaryMin().z(), this::onBoundaryEdit);

        bMaxX = mkCoordEdit(colMin, y + 36, cw, 16, scanner.getBoundaryMax().x(), this::onBoundaryEdit);
        bMaxY = mkCoordEdit(colMin + cw + 4, y + 36, cw, 16, scanner.getBoundaryMax().y(), this::onBoundaryEdit);
        bMaxZ = mkCoordEdit(colMin + (cw + 4) * 2, y + 36, cw, 16, scanner.getBoundaryMax().z(), this::onBoundaryEdit);

        addBtn(lx + 268, y + 16, 92, 36, I18n.string("gui.wandscape.scanner.gizmo_btn", "🎮 可视化调整"), () -> {
            ScannerGizmoState.enter(scanner);
        });

        y += 62;

        // ── Card 2: 门偏移与交互位 ──
        int doorW = 28;
        int doorCol = lx + 44;
        doorX = mkCoordEdit(doorCol, y + 16, doorW, 16, getDoorAxis(0), this::onDoorChanged);
        doorY = mkCoordEdit(doorCol + doorW + 4, y + 16, doorW, 16, getDoorAxis(1), this::onDoorChanged);
        doorZ = mkCoordEdit(doorCol + (doorW + 4) * 2, y + 16, doorW, 16, getDoorAxis(2), this::onDoorChanged);

        addBtn(lx + 8, y + 36, 114, 16, I18n.string("gui.wandscape.scanner.auto_detect_door", "自动检门"), this::onAutoDetectDoor);
        addBtn(lx + 126, y + 36, 52, 16, I18n.string("gui.wandscape.scanner.clear", "清除"), () -> {
            scanner.clearDoorOffsets();
            if (doorX != null) doorX.setValue("0");
            if (doorY != null) doorY.setValue("0");
            if (doorZ != null) doorZ.setValue("0");
            syncToServer();
            showFeedback(Component.literal("§6✓ 已清除全部门偏移记录 (默认走外围入口)"), 0xFFFFAA00);
            scanResult = Component.literal("§7已清除全部门偏移记录。");
            rebuild();
        });
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TAB 1: 🏷️ 属性配置 (Properties & Category Config)
    // ─────────────────────────────────────────────────────────────────────────────

    private void initTab1Properties(int lx, int startY) {
        int y = startY;

        if (scanner.getTargetMode() == TargetMode.ROAD) {
            // ── ROAD Mode Properties ──
            metaId = mkEdit(lx + 68, y + 14, 290, 18, scanner.getBuildingId().isEmpty() ? "wandscape:custom_road" : scanner.getBuildingId(), s -> {
                scanner.setBuildingId(s);
                syncToServer();
            });
            metaName = mkEdit(lx + 68, y + 38, 290, 18, scanner.getDisplayName().isEmpty() ? "自定义道路" : scanner.getDisplayName(), s -> {
                scanner.setDisplayName(s);
                syncToServer();
            });
            metaCreator = mkEdit(lx + 68, y + 62, 290, 18, scanner.getCreator(), s -> {
                scanner.setCreator(s);
                syncToServer();
            });

            addBtn(lx + 248, y + 86, 110, 18, I18n.string("gui.wandscape.scanner.switch_to_building", "切换为建筑模式"), () -> {
                scanner.setTargetMode(TargetMode.BUILDING);
                syncToServer();
                showFeedback(Component.literal("§e已切换为建筑模式"), 0xFFD4A840);
                rebuild();
            });
            return;
        }

        // ── BUILDING Mode Properties ──
        if (isSurvival) {
            // Survival mode: category is locked to custom, stats are block-calculated
            metaId = mkEdit(lx + 24, y, 100, 16, scanner.getBuildingId().isEmpty() ? "custom_building" : scanner.getBuildingId(), s -> {
                scanner.setBuildingId(s);
                syncToServer();
            });
            metaName = mkEdit(lx + 154, y, 92, 16, scanner.getDisplayName().isEmpty() ? "自定义建筑" : scanner.getDisplayName(), s -> {
                scanner.setDisplayName(s);
                syncToServer();
            });
            metaCreator = mkEdit(lx + 276, y, 88, 16, scanner.getCreator(), s -> {
                scanner.setCreator(s);
                syncToServer();
            });
            y += 24;

            addBtn(lx + 256, y, 108, 18, I18n.string("gui.wandscape.scanner.switch_to_road", "切换为道路模式"), () -> {
                scanner.setTargetMode(TargetMode.ROAD);
                syncToServer();
                showFeedback(Component.literal("§e已切换为道路模式"), 0xFFD4A840);
                rebuild();
            });
            return;
        }

        // Creative mode: Full configurable category and attributes
        // Row 1: ID, Name, Creator
        metaId = mkEdit(lx + 24, y, 100, 16, scanner.getBuildingId(), s -> {
            scanner.setBuildingId(s);
            syncToServer();
        });
        metaName = mkEdit(lx + 154, y, 92, 16, scanner.getDisplayName(), s -> {
            scanner.setDisplayName(s);
            syncToServer();
        });
        metaCreator = mkEdit(lx + 276, y, 88, 16, scanner.getCreator(), s -> {
            scanner.setCreator(s);
            syncToServer();
        });
        y += 22;

        // Row 2: Category Selector
        addBtn(lx + 62, y, 16, 18, "◀", () -> cycleCategory(-1));
        CategoryDef curCat = getCategoryDef(scanner.getCategory());
        addBtn(lx + 80, y, 148, 18, curCat.icon() + " " + curCat.label() + " (" + curCat.id() + ")", () -> cycleCategory(1));
        addBtn(lx + 230, y, 16, 18, "▶", () -> cycleCategory(1));

        addBtn(lx + 256, y, 108, 18, I18n.string("gui.wandscape.scanner.switch_to_road", "切换为道路模式"), () -> {
            scanner.setTargetMode(TargetMode.ROAD);
            syncToServer();
            showFeedback(Component.literal("§e已切换为道路模式"), 0xFFD4A840);
            rebuild();
        });
        y += 22;

        // Row 3: Stats (Comfort, Magic, Wonder, Unlock Level)
        metaComfort = mkNumEdit(lx + 34, y, 30, 16, scanner.getComfort(), s -> {
            scanner.setComfort(intOrZero(s));
            syncToServer();
        });
        metaMagic = mkNumEdit(lx + 98, y, 30, 16, scanner.getMagic(), s -> {
            scanner.setMagic(intOrZero(s));
            syncToServer();
        });
        metaWonder = mkNumEdit(lx + 162, y, 30, 16, scanner.getWonder(), s -> {
            scanner.setWonder(intOrZero(s));
            syncToServer();
        });
        unlockLevel = mkNumEdit(lx + 254, y, 28, 16, scanner.getUnlockMinLevel(), s -> {
            scanner.setUnlockMinLevel(Math.max(1, intOrZero(s)));
            syncToServer();
        });
        y += 24;

        // ── Category-Specific Sub-Panel ──
        String cat = scanner.getCategory();
        if ("shop".equals(cat)) {
            initShopConfig(lx, y);
        } else if ("service".equals(cat)) {
            initServiceConfig(lx, y);
        } else if ("node".equals(cat)) {
            initNodeConfig(lx, y);
        } else if ("relax".equals(cat)) {
            initRelaxConfig(lx, y);
        } else if ("atm".equals(cat)) {
            initAtmConfig(lx, y);
        }
    }

    private void initShopConfig(int lx, int y) {
        shopProfitRate = mkEdit(lx + 54, y + 3, 38, 16, String.valueOf(scanner.getShopProfitRate()), s -> {
            try {
                scanner.setShopProfitRate(Double.parseDouble(s));
                syncToServer();
            } catch (NumberFormatException ignored) {}
        });

        shopDuration = mkNumEdit(lx + 148, y + 3, 36, 16, scanner.getShopInteractionDurationTicks(), s -> {
            scanner.setShopInteractionDurationTicks(intOrZero(s));
            syncToServer();
        });

        addBtn(lx + 276, y + 2, 88, 18, I18n.string("gui.wandscape.scanner.add_good", "+ 添加商品"), () -> {
            scanner.addShopGood(new ShopGoodData("minecraft:apple", 5, 0, 0));
            syncToServer();
            shopGoodsPage = Math.max(0, (scanner.getShopGoods().size() - 1) / GOODS_PER_PAGE);
            showFeedback(Component.literal("§a✓ 已上架新商品行 (请编辑物品ID)"), 0xFF55FF55);
            rebuild();
        });

        int rowY = y + 24;
        List<ShopGoodData> goods = scanner.getShopGoods();
        int totalPages = Math.max(1, (goods.size() + GOODS_PER_PAGE - 1) / GOODS_PER_PAGE);
        shopGoodsPage = Math.clamp(shopGoodsPage, 0, totalPages - 1);

        int start = shopGoodsPage * GOODS_PER_PAGE;
        int end = Math.min(goods.size(), start + GOODS_PER_PAGE);

        for (int i = start; i < end; i++) {
            final int idx = i;
            ShopGoodData g = goods.get(i);
            mkEdit(lx + 32, rowY, 120, 16, g.itemId(), s -> updateShopGood(idx, s, g.comfort(), g.magic(), g.wonder()));
            mkNumEdit(lx + 172, rowY, 26, 16, g.comfort(), s -> updateShopGood(idx, g.itemId(), intOrZero(s), g.magic(), g.wonder()));
            mkNumEdit(lx + 218, rowY, 26, 16, g.magic(), s -> updateShopGood(idx, g.itemId(), g.comfort(), intOrZero(s), g.wonder()));
            mkNumEdit(lx + 264, rowY, 26, 16, g.wonder(), s -> updateShopGood(idx, g.itemId(), g.comfort(), g.magic(), intOrZero(s)));
            addBtn(lx + 300, rowY, 16, 16, "×", () -> {
                scanner.removeShopGood(idx);
                syncToServer();
                showFeedback(Component.literal("§6✓ 已移除该商品条目"), 0xFFFFAA00);
                rebuild();
            });
            rowY += 20;
        }

        if (totalPages > 1) {
            addBtn(lx + 12, y + 88, 55, 14, I18n.string("gui.wandscape.scanner.prev_page_short", "◀ 上页"), () -> {
                if (shopGoodsPage > 0) { shopGoodsPage--; rebuild(); }
            }, shopGoodsPage > 0);
            addBtn(lx + 298, y + 88, 55, 14, I18n.string("gui.wandscape.scanner.next_page_short", "下页 ▶"), () -> {
                if (shopGoodsPage < totalPages - 1) { shopGoodsPage++; rebuild(); }
            }, shopGoodsPage < totalPages - 1);
        }
    }

    private void updateShopGood(int idx, String itemId, int c, int m, int w) {
        scanner.updateShopGood(idx, new ShopGoodData(itemId, c, m, w));
        syncToServer();
    }

    private void initServiceConfig(int lx, int y) {
        serviceEnergy = mkNumEdit(lx + 38, y + 3, 30, 16, scanner.getServiceEnergyPerUse(), s -> {
            scanner.setServiceEnergyPerUse(intOrZero(s));
            syncToServer();
        });

        serviceMaxOcc = mkNumEdit(lx + 98, y + 3, 26, 16, scanner.getServiceMaxOccupancy(), s -> {
            scanner.setServiceMaxOccupancy(intOrZero(s));
            syncToServer();
        });

        serviceDuration = mkNumEdit(lx + 154, y + 3, 30, 16, scanner.getServiceInteractionDurationTicks(), s -> {
            scanner.setServiceInteractionDurationTicks(intOrZero(s));
            syncToServer();
        });

        addBtn(lx + 280, y + 2, 84, 18, I18n.string("gui.wandscape.scanner.add_output", "+ 产出"), () -> {
            String el = nextUnusedElement(scanner.getServiceElementOutput());
            if (el != null) {
                scanner.addServiceElementOutput(el, 1);
                syncToServer();
                showFeedback(Component.literal("§a✓ 已添加元素产出"), 0xFF55FF55);
                rebuild();
            } else {
                showFeedback(Component.literal("§e⚠ 所有 7 种元素已全部添加完毕"), 0xFFFFAA00);
            }
        });

        int rowY = y + 24;
        List<Map.Entry<String, Integer>> list = new ArrayList<>(scanner.getServiceElementOutput().entrySet());
        int totalPages = Math.max(1, (list.size() + ELEM_PER_PAGE - 1) / ELEM_PER_PAGE);
        serviceElemPage = Math.clamp(serviceElemPage, 0, totalPages - 1);

        int start = serviceElemPage * ELEM_PER_PAGE;
        int end = Math.min(list.size(), start + ELEM_PER_PAGE);

        for (int i = start; i < end; i++) {
            var entry = list.get(i);
            String elem = entry.getKey();
            ElementDef elDef = getElementDef(elem);
            addBtn(lx + 14, rowY, 95, 16, elDef.symbol() + " " + elDef.label(), () -> {
                cycleServiceElement(elem, entry.getValue());
            });
            mkNumEdit(lx + 142, rowY, 32, 16, entry.getValue(), s -> {
                scanner.addServiceElementOutput(elem, intOrZero(s));
                syncToServer();
            });
            addBtn(lx + 182, rowY, 16, 16, "×", () -> {
                scanner.removeServiceElementOutput(elem);
                syncToServer();
                showFeedback(Component.literal("§6✓ 已移除该元素产出"), 0xFFFFAA00);
                rebuild();
            });
            rowY += 20;
        }

        if (totalPages > 1) {
            addBtn(lx + 12, y + 88, 55, 14, I18n.string("gui.wandscape.scanner.prev_page_short", "◀ 上页"), () -> {
                if (serviceElemPage > 0) { serviceElemPage--; rebuild(); }
            }, serviceElemPage > 0);
            addBtn(lx + 298, y + 88, 55, 14, I18n.string("gui.wandscape.scanner.next_page_short", "下页 ▶"), () -> {
                if (serviceElemPage < totalPages - 1) { serviceElemPage++; rebuild(); }
            }, serviceElemPage < totalPages - 1);
        }
    }

    private void cycleServiceElement(String oldElem, int amount) {
        int idx = getElementIndex(oldElem);
        String nextElem = ELEMENTS.get((idx + 1) % ELEMENTS.size()).id();
        scanner.removeServiceElementOutput(oldElem);
        scanner.addServiceElementOutput(nextElem, amount);
        syncToServer();
        ElementDef elDef = getElementDef(nextElem);
        showFeedback(Component.literal("§e产出元素切换为: " + elDef.symbol() + " " + elDef.label()), 0xFFD4A840);
        rebuild();
    }

    private void initNodeConfig(int lx, int y) {
        ElementDef elDef = getElementDef(scanner.getNodeElement());
        addBtn(lx + 70, y + 6, 150, 20, elDef.symbol() + " " + elDef.label() + " ▾", () -> {
            int idx = getElementIndex(scanner.getNodeElement());
            String nextElem = ELEMENTS.get((idx + 1) % ELEMENTS.size()).id();
            scanner.setNodeElement(nextElem);
            syncToServer();
            ElementDef nextDef = getElementDef(nextElem);
            showFeedback(Component.literal("§e采集元素切换为: " + nextDef.symbol() + " " + nextDef.label()), 0xFFD4A840);
            rebuild();
        });

        nodeAmount = mkNumEdit(lx + 80, y + 34, 45, 16, scanner.getNodeAmountPerHarvest(), s -> {
            scanner.setNodeAmountPerHarvest(intOrZero(s));
            syncToServer();
        });

        nodeChannel = mkNumEdit(lx + 80, y + 60, 45, 16, scanner.getNodeChannelTicks(), s -> {
            scanner.setNodeChannelTicks(intOrZero(s));
            syncToServer();
        });
    }

    private void initRelaxConfig(int lx, int y) {
        relaxEnergy = mkNumEdit(lx + 80, y + 10, 45, 16, scanner.getRelaxEnergyRestore(), s -> {
            scanner.setRelaxEnergyRestore(intOrZero(s));
            syncToServer();
        });

        relaxDuration = mkNumEdit(lx + 80, y + 36, 45, 16, scanner.getRelaxInteractionDurationTicks(), s -> {
            scanner.setRelaxInteractionDurationTicks(intOrZero(s));
            syncToServer();
        });
    }

    private void initAtmConfig(int lx, int y) {
        atmWithdraw = mkNumEdit(lx + 80, y + 10, 45, 16, scanner.getAtmWithdrawAmount(), s -> {
            scanner.setAtmWithdrawAmount(intOrZero(s));
            syncToServer();
        });

        atmDuration = mkNumEdit(lx + 80, y + 36, 45, 16, scanner.getAtmInteractionDurationTicks(), s -> {
            scanner.setAtmInteractionDurationTicks(intOrZero(s));
            syncToServer();
        });
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TAB 2: 💾 预设管理 (Presets)
    // ─────────────────────────────────────────────────────────────────────────────

    private void initTab2Presets(int lx, int startY) {
        int y = startY;

        presetNameEdit = mkEdit(lx + 62, y, 165, 18, "", s -> {});
        addBtn(lx + 233, y, 131, 18, I18n.string("gui.wandscape.scanner.save_preset", "💾 保存预设"), this::onPresetSave);

        y += 24;
        List<String> presets = ScannerPresetStore.listPresets();
        int totalPages = Math.max(1, (presets.size() + PRESETS_PER_PAGE - 1) / PRESETS_PER_PAGE);
        presetsPage = Math.clamp(presetsPage, 0, totalPages - 1);

        int start = presetsPage * PRESETS_PER_PAGE;
        int end = Math.min(presets.size(), start + PRESETS_PER_PAGE);

        int listY = y + 16;
        for (int i = start; i < end; i++) {
            final String name = presets.get(i);
            addBtn(lx + 260, listY + 2, 46, 18, I18n.string("gui.wandscape.scanner.load_preset", "📂 加载"), () -> loadPresetByName(name));
            addBtn(lx + 312, listY + 2, 46, 18, I18n.string("gui.wandscape.scanner.delete_preset", "🗑️ 删除"), () -> deletePresetByName(name));
            listY += 24;
        }

        if (totalPages > 1) {
            addBtn(lx + 14, y + 118, 65, 16, I18n.string("gui.wandscape.scanner.prev_page", "◀ 上一页"), () -> {
                if (presetsPage > 0) { presetsPage--; rebuild(); }
            }, presetsPage > 0);
            addBtn(lx + 285, y + 118, 65, 16, I18n.string("gui.wandscape.scanner.next_page", "下一页 ▶"), () -> {
                if (presetsPage < totalPages - 1) { presetsPage++; rebuild(); }
            }, presetsPage < totalPages - 1);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TAB 3: ⚡ 导出操作 (Export & Actions)
    // ─────────────────────────────────────────────────────────────────────────────

    private void initTab3Export(int lx, int startY) {
        int btnY = startY + 82;
        addBtn(lx + 4, btnY, 114, 22, I18n.string("gui.wandscape.scanner.scan_area", "🔍 扫描区域"), this::doScan);
        addBtn(lx + 122, btnY, 114, 22, I18n.string("gui.wandscape.scanner.calc_area_value", "💰 计算价值"), this::doValue);

        String exportText = scanner.getTargetMode() == TargetMode.ROAD
                ? I18n.string("gui.wandscape.scanner.export_road_btn", "🚀 导出道路 JSON")
                : I18n.string("gui.wandscape.scanner.export_building_btn", "🚀 导出建筑 JSON");
        addBtn(lx + 240, btnY, 120, 22, exportText, this::doExport);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // RENDERING
    // ─────────────────────────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics gui, int mx, int my, float pt) {
        renderBackground(gui, mx, my, pt);
        renderMinimalHeader(gui);
        renderCloseButton(gui, mx, my);

        int lx = leftPos + 8;

        // ── CORNER Mode Render ──
        if (scanner.getBlockMode() == BlockMode.CORNER) {
            renderCornerMode(gui, mx, my, lx);
            renderWidgetsAndButtons(gui, mx, my, pt);
            renderFeedback(gui);
            return;
        }

        // ── Tabs Bar Render ──
        renderTabBar(gui, mx, my, lx);

        // ── Tab Specific Content ──
        int contentY = topPos + headerHeight + TAB_H + 6;
        switch (activeTab) {
            case 0 -> renderTab0Bounds(gui, lx, contentY);
            case 1 -> renderTab1Properties(gui, lx, contentY);
            case 2 -> renderTab2Presets(gui, lx, contentY);
            case 3 -> renderTab3Export(gui, lx, contentY);
        }

        renderWidgetsAndButtons(gui, mx, my, pt);
        renderFeedback(gui);
    }

    private void renderTabBar(GuiGraphics gui, int mx, int my, int lx) {
        String[] tabs = {
                I18n.string("gui.wandscape.scanner.tab_bounds", "📐 范围结构"),
                I18n.string("gui.wandscape.scanner.tab_properties", "🏷️ 属性配置"),
                I18n.string("gui.wandscape.scanner.tab_presets", "💾 预设管理"),
                I18n.string("gui.wandscape.scanner.tab_export", "⚡ 导出操作")
        };
        int tabY = topPos + headerHeight + 2;
        int tabW = 88;

        for (int i = 0; i < tabs.length; i++) {
            int tx = lx + i * (tabW + 4);
            boolean active = i == activeTab;
            boolean hover = !active && isInRect(mx, my, tx, tabY, tabW, TAB_H);

            drawMinimalBox(gui, tx, tabY, tabW, TAB_H, active, hover);

            int textColor = active ? MedievalColors.BORDER_GOLD : (hover ? MedievalColors.TEXT_WARM_WHITE : MedievalColors.TEXT_MUTED);
            gui.drawString(font, tabs[i], tx + (tabW - font.width(tabs[i])) / 2, tabY + 4, textColor);
        }
    }

    private void renderCornerMode(GuiGraphics gui, int mx, int my, int lx) {
        int topY = topPos + headerHeight + 8;
        gui.drawString(font, I18n.string("gui.wandscape.scanner.passphrase", "暗号:"), lx + 102, topY + 5, MedievalColors.TEXT_MUTED);

        int cardY = topY + 30;
        drawMinimalBox(gui, lx + 8, cardY, 348, 120, true, false);

        gui.drawString(font, I18n.string("gui.wandscape.scanner.corner_title", "❖ CORNER 辅角点模式已激活"), lx + 20, cardY + 12, MedievalColors.BORDER_GOLD);
        gui.drawString(font, I18n.string("gui.wandscape.scanner.corner_step1", "1. 在上方输入框输入与 SAVE 主扫描器完全一致的暗号。"), lx + 20, cardY + 32, MedievalColors.TEXT_WARM_WHITE);
        gui.drawString(font, I18n.string("gui.wandscape.scanner.corner_step2", "2. 将此方块放置在建筑 3D 对角线的另一个角点顶点位置。"), lx + 20, cardY + 50, MedievalColors.TEXT_MUTED);
        gui.drawString(font, I18n.string("gui.wandscape.scanner.corner_step3", "3. 返回 SAVE 主扫描器点击“❖ 匹配角点”，系统将自动连接"), lx + 20, cardY + 68, MedievalColors.TEXT_MUTED);
        gui.drawString(font, I18n.string("gui.wandscape.scanner.corner_step4", "   并框选出完整的 3D 建筑包围盒范围。"), lx + 20, cardY + 84, MedievalColors.TEXT_MUTED);
    }

    private void renderTab0Bounds(GuiGraphics gui, int lx, int y) {
        // Top row label
        gui.drawString(font, I18n.string("gui.wandscape.scanner.passphrase", "暗号:"), lx + 148, y + 5, MedievalColors.TEXT_MUTED);

        // Card 1: 3D Bounds
        int card1Y = y + 24;
        drawMinimalBox(gui, lx, card1Y, 364, 58, false, false);
        gui.drawString(font, I18n.string("gui.wandscape.scanner.bounds_header", "❖ 3D 边界坐标 (相对于扫描器)"), lx + 8, card1Y + 4, MedievalColors.BORDER_GOLD);

        gui.drawString(font, I18n.string("gui.wandscape.scanner.min_label", "Min(小):"), lx + 8, card1Y + 19, MedievalColors.TEXT_MUTED);
        gui.drawString(font, I18n.string("gui.wandscape.scanner.max_label", "Max(大):"), lx + 8, card1Y + 39, MedievalColors.TEXT_MUTED);

        BlockOffset bMin = scanner.getBoundaryMin();
        BlockOffset bMax = scanner.getBoundaryMax();
        int dx = Math.abs(bMax.x() - bMin.x()) + 1;
        int dy = Math.abs(bMax.y() - bMin.y()) + 1;
        int dz = Math.abs(bMax.z() - bMin.z()) + 1;
        long vol = (long) dx * dy * dz;

        int infoX = lx + 172;
        gui.drawString(font, I18n.string("gui.wandscape.scanner.size_format", "尺寸: %d×%d×%d", dx, dy, dz), infoX, card1Y + 16, MedievalColors.BORDER_GOLD);
        gui.drawString(font, I18n.string("gui.wandscape.scanner.vol_format", "体积: %,d格", vol), infoX, card1Y + 28, MedievalColors.TEXT_WARM_WHITE);
        gui.drawString(font, String.format("X%d Y%d Z%d", dx, dy, dz), infoX, card1Y + 40, MedievalColors.TEXT_DIM);

        // Card 2: Doors & Spots
        int card2Y = card1Y + 62;
        // Sub-card Left: Doors
        drawMinimalBox(gui, lx, card2Y, 186, 92, false, false);
        gui.drawString(font, I18n.string("gui.wandscape.scanner.door_offset_count_header", "❖ 门偏移 (%d 扇)", scanner.getDoorOffsets().size()), lx + 8, card2Y + 4, MedievalColors.BORDER_GOLD);
        gui.drawString(font, I18n.string("gui.wandscape.scanner.first_door", "首门:"), lx + 14, card2Y + 20, MedievalColors.TEXT_MUTED);

        List<BlockOffset> doors = scanner.getDoorOffsets();
        if (doors.isEmpty()) {
            gui.drawString(font, I18n.string("gui.wandscape.scanner.no_door_hint", "未设门: 游客沿包围盒外进入"), lx + 8, card2Y + 60, MedievalColors.TEXT_DIM);
        } else {
            StringBuilder sb = new StringBuilder(I18n.string("gui.wandscape.scanner.doors_recorded", "已录入: "));
            for (int i = 0; i < Math.min(3, doors.size()); i++) {
                BlockOffset d = doors.get(i);
                sb.append("(").append(d.x()).append(",").append(d.y()).append(",").append(d.z()).append(") ");
            }
            if (doors.size() > 3) sb.append("...");
            gui.drawString(font, font.plainSubstrByWidth(sb.toString(), 170), lx + 8, card2Y + 60, MedievalColors.TEXT_WARM_WHITE);
        }

        // Sub-card Right: Spots
        int spotsX = lx + 192;
        drawMinimalBox(gui, spotsX, card2Y, 172, 92, false, false);
        gui.drawString(font, I18n.string("gui.wandscape.scanner.interact_spots_header", "❖ 交互位 Marker"), spotsX + 8, card2Y + 4, MedievalColors.BORDER_GOLD);

        int spotCount = countSpotMarkers();
        int spotColor = spotCount > 0 ? MedievalColors.TEXT_WARM_WHITE : (isTouristCategory(scanner.getCategory()) ? 0xFFFF5555 : MedievalColors.TEXT_MUTED);
        gui.drawString(font, I18n.string("gui.wandscape.scanner.spots_count", "已放置: %d 个交互位", spotCount), spotsX + 8, card2Y + 20, spotColor);

        if (isTouristCategory(scanner.getCategory()) && spotCount == 0) {
            gui.drawString(font, I18n.string("gui.wandscape.scanner.no_spots_warn", "§c⚠ 无交互位！"), spotsX + 8, card2Y + 40, 0xFFFF5555);
            gui.drawString(font, I18n.string("gui.wandscape.scanner.no_spots_detail", "§c游客将无法在此互动消费"), spotsX + 8, card2Y + 54, 0xFFFF7777);
        } else if (spotCount > 0) {
            gui.drawString(font, I18n.string("gui.wandscape.scanner.spots_ready", "§a✓ 交互位已就绪"), spotsX + 8, card2Y + 40, 0xFF55FF55);
            gui.drawString(font, I18n.string("gui.wandscape.scanner.spots_hint", "§7右键换动作, 潜行换朝向"), spotsX + 8, card2Y + 54, MedievalColors.TEXT_DIM);
        } else {
            gui.drawString(font, I18n.string("gui.wandscape.scanner.spots_optional", "§7基础/功能建筑可选填交互位"), spotsX + 8, card2Y + 40, MedievalColors.TEXT_DIM);
        }
    }

    private void renderTab1Properties(GuiGraphics gui, int lx, int y) {
        if (scanner.getTargetMode() == TargetMode.ROAD) {
            drawMinimalBox(gui, lx, y, 364, 84, false, false);
            gui.drawString(font, I18n.string("gui.wandscape.scanner.road_preset_header", "❖ 道路预设属性 (Road Preset)"), lx + 8, y + 4, MedievalColors.BORDER_GOLD);
            gui.drawString(font, I18n.string("gui.wandscape.scanner.road_id", "道路ID:"), lx + 14, y + 19, MedievalColors.TEXT_MUTED);
            gui.drawString(font, I18n.string("gui.wandscape.scanner.display_name", "显示名称:"), lx + 14, y + 43, MedievalColors.TEXT_MUTED);
            gui.drawString(font, I18n.string("gui.wandscape.scanner.creator", "制作作者:"), lx + 14, y + 67, MedievalColors.TEXT_MUTED);

            drawMinimalBox(gui, lx, y + 88, 364, 66, false, false);
            gui.drawString(font, I18n.string("gui.wandscape.scanner.road_desc_header", "❖ 道路预设说明"), lx + 8, y + 92, MedievalColors.BORDER_GOLD);
            String hint = I18n.string("gui.wandscape.scanner.road_desc_body", "导出后将自动计算包围盒内各方块权重并生成 road_preset JSON，热注册到道路工坊与建造法杖中，可立即使用。");
            List<FormattedCharSequence> lines = font.split(Component.literal(hint), 345);
            int ly = y + 106;
            for (var line : lines) {
                gui.drawString(font, line, lx + 10, ly, MedievalColors.TEXT_MUTED);
                ly += 11;
            }
            return;
        }

        // ── BUILDING Mode Properties ──
        if (isSurvival) {
            drawMinimalBox(gui, lx, y - 2, 364, 48, false, false);
            gui.drawString(font, I18n.string("gui.wandscape.scanner.id_label", "ID:"), lx + 6, y + 4, MedievalColors.TEXT_MUTED);
            gui.drawString(font, I18n.string("gui.wandscape.scanner.name_label", "名称:"), lx + 128, y + 4, MedievalColors.TEXT_MUTED);
            gui.drawString(font, I18n.string("gui.wandscape.scanner.author_label", "作者:"), lx + 250, y + 4, MedievalColors.TEXT_MUTED);

            gui.drawString(font, I18n.string("gui.wandscape.scanner.category_label", "分类:"), lx + 6, y + 27, MedievalColors.TEXT_MUTED);
            gui.drawString(font, I18n.string("gui.wandscape.scanner.survival_category_locked", "🛠️ 自定义建筑 (custom) [生存模式固定]"), lx + 36, y + 27, MedievalColors.BORDER_GOLD);

            drawMinimalBox(gui, lx, y + 50, 364, 104, false, false);
            gui.drawString(font, I18n.string("gui.wandscape.scanner.survival_desc_header", "❖ 生存建筑属性与蓝图说明"), lx + 8, y + 54, MedievalColors.BORDER_GOLD);
            String[] notes = {
                    I18n.string("gui.wandscape.scanner.survival_note1", "• 生存模式下扫描的建筑类别固定为 custom (自定义)。"),
                    I18n.string("gui.wandscape.scanner.survival_note2", "• 建筑的舒适度、魔法值、奇观值与维护费用在建造时由其内部方块属性自动评估，无需手动配置。"),
                    I18n.string("gui.wandscape.scanner.survival_note3", "• 导出的蓝图可直接在蓝图工坊、建造法杖及建筑列表中使用，支持市民法师全自动建造。"),
                    I18n.string("gui.wandscape.scanner.survival_note4", "• 如需配置商店出售商品、服务元素产出等深度经济系统，请在创造模式使用创造建筑扫描器。")
            };
            int ny = y + 68;
            for (String note : notes) {
                List<FormattedCharSequence> lines = font.split(Component.literal(note), 345);
                for (var line : lines) {
                    gui.drawString(font, line, lx + 10, ny, MedievalColors.TEXT_MUTED);
                    ny += 11;
                }
            }
            return;
        }

        // Creative mode: Full configurable category and attributes
        drawMinimalBox(gui, lx, y - 2, 364, 68, false, false);
        gui.drawString(font, I18n.string("gui.wandscape.scanner.id_label", "ID:"), lx + 6, y + 4, MedievalColors.TEXT_MUTED);
        gui.drawString(font, I18n.string("gui.wandscape.scanner.name_label", "名称:"), lx + 128, y + 4, MedievalColors.TEXT_MUTED);
        gui.drawString(font, I18n.string("gui.wandscape.scanner.author_label", "作者:"), lx + 250, y + 4, MedievalColors.TEXT_MUTED);

        gui.drawString(font, I18n.string("gui.wandscape.scanner.category_label", "分类:"), lx + 6, y + 27, MedievalColors.TEXT_MUTED);

        gui.drawString(font, I18n.string("gui.wandscape.scanner.comfort_label", "舒适:"), lx + 6, y + 48, MedievalColors.TEXT_MUTED);
        gui.drawString(font, I18n.string("gui.wandscape.scanner.magic_label", "魔法:"), lx + 70, y + 48, MedievalColors.TEXT_MUTED);
        gui.drawString(font, I18n.string("gui.wandscape.scanner.wonder_label", "奇观:"), lx + 134, y + 48, MedievalColors.TEXT_MUTED);
        gui.drawString(font, I18n.string("gui.wandscape.scanner.unlock_level_label", "解锁等级:"), lx + 200, y + 48, MedievalColors.TEXT_MUTED);
        gui.drawString(font, I18n.string("gui.wandscape.scanner.level_range", "(1~10)"), lx + 286, y + 48, MedievalColors.TEXT_DIM);

        // Category config panel
        int catY = y + 70;
        drawMinimalBox(gui, lx, catY, 364, 84, false, false);
        String cat = scanner.getCategory();
        CategoryDef def = getCategoryDef(cat);
        gui.drawString(font, I18n.string("gui.wandscape.scanner.specs_header", "❖ %s 专属配置", def.label()), lx + 8, catY + 4, MedievalColors.BORDER_GOLD);

        if ("shop".equals(cat)) {
            gui.drawString(font, I18n.string("gui.wandscape.scanner.profit_rate", "利润%:"), lx + 14, catY + 19, MedievalColors.TEXT_MUTED);
            gui.drawString(font, I18n.string("gui.wandscape.scanner.duration_ticks", "时长Ticks:"), lx + 96, catY + 19, MedievalColors.TEXT_MUTED);

            List<ShopGoodData> goods = scanner.getShopGoods();
            if (goods.isEmpty()) {
                gui.drawString(font, I18n.string("gui.wandscape.scanner.no_goods", "暂无商品，请点击右上角“+ 上架商品”"), lx + 14, catY + 44, MedievalColors.TEXT_DIM);
            } else {
                int start = shopGoodsPage * GOODS_PER_PAGE;
                int end = Math.min(goods.size(), start + GOODS_PER_PAGE);
                int gy = catY + 36;
                for (int i = start; i < end; i++) {
                    gui.drawString(font, I18n.string("gui.wandscape.scanner.item_label", "物品:"), lx + 6, gy + 4, MedievalColors.TEXT_DIM);
                    gui.drawString(font, I18n.string("gui.wandscape.scanner.comfort_short", "舒:"), lx + 156, gy + 4, MedievalColors.TEXT_DIM);
                    gui.drawString(font, I18n.string("gui.wandscape.scanner.magic_short", "魔:"), lx + 202, gy + 4, MedievalColors.TEXT_DIM);
                    gui.drawString(font, I18n.string("gui.wandscape.scanner.wonder_short", "奇:"), lx + 248, gy + 4, MedievalColors.TEXT_DIM);
                    gy += 20;
                }
            }
        } else if ("service".equals(cat)) {
            gui.drawString(font, I18n.string("gui.wandscape.scanner.energy", "能耗:"), lx + 10, catY + 19, MedievalColors.TEXT_MUTED);
            gui.drawString(font, I18n.string("gui.wandscape.scanner.occupancy", "容纳:"), lx + 72, catY + 19, MedievalColors.TEXT_MUTED);
            gui.drawString(font, I18n.string("gui.wandscape.scanner.duration", "时长:"), lx + 128, catY + 19, MedievalColors.TEXT_MUTED);

            var outputs = scanner.getServiceElementOutput();
            if (outputs.isEmpty()) {
                gui.drawString(font, I18n.string("gui.wandscape.scanner.no_element_output", "暂无元素产出，点击右上角“+ 产出”添加"), lx + 14, catY + 44, MedievalColors.TEXT_DIM);
            } else {
                int gy = catY + 36;
                List<Map.Entry<String, Integer>> list = new ArrayList<>(outputs.entrySet());
                int start = serviceElemPage * ELEM_PER_PAGE;
                int end = Math.min(list.size(), start + ELEM_PER_PAGE);
                for (int i = start; i < end; i++) {
                    gui.drawString(font, I18n.string("gui.wandscape.scanner.output_amount", "产出量:"), lx + 112, gy + 4, MedievalColors.TEXT_DIM);
                    gy += 20;
                }
            }
        } else if ("node".equals(cat)) {
            gui.drawString(font, I18n.string("gui.wandscape.scanner.node_amount", "产出量/次:"), lx + 14, catY + 38, MedievalColors.TEXT_MUTED);
            gui.drawString(font, I18n.string("gui.wandscape.scanner.node_channel", "引导Ticks:"), lx + 14, catY + 64, MedievalColors.TEXT_MUTED);
            gui.drawString(font, I18n.string("gui.wandscape.scanner.node_desc", "市民法师在此节点引导采集元素能量"), lx + 140, catY + 48, MedievalColors.TEXT_DIM);
        } else if ("relax".equals(cat)) {
            gui.drawString(font, I18n.string("gui.wandscape.scanner.relax_energy", "恢复精力/次:"), lx + 14, catY + 22, MedievalColors.TEXT_MUTED);
            gui.drawString(font, I18n.string("gui.wandscape.scanner.relax_duration", "交互时长Ticks:"), lx + 14, catY + 48, MedievalColors.TEXT_MUTED);
            gui.drawString(font, I18n.string("gui.wandscape.scanner.relax_desc", "游客在此建筑放松时恢复精力值"), lx + 14, catY + 68, MedievalColors.TEXT_DIM);
        } else if ("atm".equals(cat)) {
            gui.drawString(font, I18n.string("gui.wandscape.scanner.atm_withdraw", "取现上限/次:"), lx + 14, catY + 22, MedievalColors.TEXT_MUTED);
            gui.drawString(font, I18n.string("gui.wandscape.scanner.atm_duration", "交互时长Ticks:"), lx + 14, catY + 48, MedievalColors.TEXT_MUTED);
            gui.drawString(font, I18n.string("gui.wandscape.scanner.atm_desc", "游客金币不足时在此取款后继续消费"), lx + 14, catY + 68, MedievalColors.TEXT_DIM);
        } else {
            gui.drawString(font, I18n.string("gui.wandscape.scanner.cat_default_title", "该建筑类别无需额外特定参数。"), lx + 14, catY + 26, MedievalColors.TEXT_WARM_WHITE);
            gui.drawString(font, I18n.string("gui.wandscape.scanner.cat_default_desc", "放置后直接作为标准功能或装饰建筑运作。"), lx + 14, catY + 44, MedievalColors.TEXT_MUTED);
        }
    }

    private void renderTab2Presets(GuiGraphics gui, int lx, int y) {
        gui.drawString(font, I18n.string("gui.wandscape.scanner.preset_name_label", "预设名称:"), lx + 8, y + 5, MedievalColors.TEXT_MUTED);

        int cardY = y + 24;
        List<String> presets = ScannerPresetStore.listPresets();
        drawMinimalBox(gui, lx, cardY, 364, 130, false, false);
        gui.drawString(font, I18n.string("gui.wandscape.scanner.presets_saved_count", "❖ 已保存的扫描预设模板 (%d 个)", presets.size()), lx + 8, cardY + 4, MedievalColors.BORDER_GOLD);

        if (presets.isEmpty()) {
            gui.drawString(font, I18n.string("gui.wandscape.scanner.no_presets_1", "暂无保存的预设模板。"), lx + 14, cardY + 36, MedievalColors.TEXT_WARM_WHITE);
            gui.drawString(font, I18n.string("gui.wandscape.scanner.no_presets_2", "在上方输入名称并点击“保存当前为预设”，"), lx + 14, cardY + 54, MedievalColors.TEXT_MUTED);
            gui.drawString(font, I18n.string("gui.wandscape.scanner.no_presets_3", "即可将当前包围盒、分类与属性保存为模板存档。"), lx + 14, cardY + 70, MedievalColors.TEXT_DIM);
            return;
        }

        int start = presetsPage * PRESETS_PER_PAGE;
        int end = Math.min(presets.size(), start + PRESETS_PER_PAGE);
        int itemY = cardY + 16;

        for (int i = start; i < end; i++) {
            String name = presets.get(i);
            drawInsetField(gui, lx + 8, itemY, 348, 22);
            gui.drawString(font, "📌 " + name, lx + 16, itemY + 7, MedievalColors.BORDER_GOLD);
            itemY += 24;
        }

        int totalPages = Math.max(1, (presets.size() + PRESETS_PER_PAGE - 1) / PRESETS_PER_PAGE);
        if (totalPages > 1) {
            String pageStr = I18n.string("gui.wandscape.scanner.page_format", "第 %d/%d 页", presetsPage + 1, totalPages);
            gui.drawString(font, pageStr, lx + (364 - font.width(pageStr)) / 2, cardY + 116, MedievalColors.TEXT_MUTED);
        }
    }

    private void renderTab3Export(GuiGraphics gui, int lx, int y) {
        // Top Overview Card
        drawMinimalBox(gui, lx, y, 364, 76, false, false);
        gui.drawString(font, I18n.string("gui.wandscape.scanner.overview_header", "❖ 当前扫描配置概览"), lx + 8, y + 4, MedievalColors.BORDER_GOLD);

        String targetStr = scanner.getTargetMode() == TargetMode.ROAD ? "道路 (ROAD)" : "建筑 (BUILDING)";
        String catStr = isSurvival ? "自定义 (custom)" : (getCategoryDef(scanner.getCategory()).label() + " (" + scanner.getCategory() + ")");
        gui.drawString(font, I18n.string("gui.wandscape.scanner.overview_target_cat", "目标: %s | 分类: %s", targetStr, catStr), lx + 12, y + 18, MedievalColors.TEXT_WARM_WHITE);

        String idStr = scanner.getBuildingId().isEmpty() ? "未命名ID" : scanner.getBuildingId();
        String nameStr = scanner.getDisplayName().isEmpty() ? "—" : scanner.getDisplayName();
        gui.drawString(font, I18n.string("gui.wandscape.scanner.overview_id_name", "标识: %s (%s)", font.plainSubstrByWidth(idStr, 130), font.plainSubstrByWidth(nameStr, 120)), lx + 12, y + 34, MedievalColors.TEXT_WARM_WHITE);

        BlockOffset bMin = scanner.getBoundaryMin();
        BlockOffset bMax = scanner.getBoundaryMax();
        int dx = Math.abs(bMax.x() - bMin.x()) + 1;
        int dy = Math.abs(bMax.y() - bMin.y()) + 1;
        int dz = Math.abs(bMax.z() - bMin.z()) + 1;
        long vol = (long) dx * dy * dz;
        gui.drawString(font, I18n.string("gui.wandscape.scanner.overview_stats", "尺寸: %d×%d×%d (%,d格) | 门: %d扇 | 交互位: %d个", dx, dy, dz, vol, scanner.getDoorOffsets().size(), countSpotMarkers()),
                lx + 12, y + 50, MedievalColors.BORDER_GOLD);

        // Result Card
        int resY = y + 108;
        drawMinimalBox(gui, lx, resY, 364, 46, true, false);
        gui.drawString(font, I18n.string("gui.wandscape.scanner.result_header", "❖ 执行结果 / 状态反馈"), lx + 8, resY + 4, MedievalColors.BORDER_GOLD);

        List<FormattedCharSequence> lines = font.split(scanResult, 345);
        int ly = resY + 18;
        for (int i = 0; i < Math.min(2, lines.size()); i++) {
            gui.drawString(font, lines.get(i), lx + 12, ly, MedievalColors.TEXT_WARM_WHITE);
            ly += 11;
        }
    }

    private void renderWidgetsAndButtons(GuiGraphics gui, int mx, int my, float pt) {
        // Inset glowing borders for edit boxes
        for (FieldRect f : insetFields) {
            boolean focused = f.box() != null && f.box().isFocused();
            boolean hover = isInRect(mx, my, f.x(), f.y(), f.w(), f.h());
            drawEditBoxBorder(gui, f.x(), f.y(), f.w(), f.h(), focused, hover);
        }

        // Custom Buttons
        for (CustomButton btn : customButtons) {
            boolean hover = btn.enabled() && isInRect(mx, my, btn.x(), btn.y(), btn.w(), btn.h());
            drawMinimalBox(gui, btn.x(), btn.y(), btn.w(), btn.h(), false, hover);
            int textColor = !btn.enabled() ? MedievalColors.TEXT_DIM : (hover ? MedievalColors.BORDER_GOLD : MedievalColors.TEXT_WARM_WHITE);
            String text = btn.text();
            int maxTextW = btn.w() - 4;
            if (font.width(text) > maxTextW) {
                text = font.plainSubstrByWidth(text, maxTextW);
            }
            int tx = btn.x() + (btn.w() - font.width(text)) / 2;
            int ty = btn.y() + (btn.h() - font.lineHeight) / 2;
            gui.drawString(font, text, tx, ty, textColor);
        }

        // Super render (EditBoxes)
        for (var child : children()) {
            if (child instanceof EditBox box && box.visible) {
                box.render(gui, mx, my, pt);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // INPUT HANDLING
    // ─────────────────────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            // Close hit handled by super
            if (isCloseHit(mouseX, mouseY)) {
                this.onClose();
                return true;
            }

            // Tab bar click
            if (scanner.getBlockMode() == BlockMode.SAVE) {
                int tabY = topPos + headerHeight + 2;
                int tabW = 88;
                for (int i = 0; i < 4; i++) {
                    int tx = leftPos + 8 + i * (tabW + 4);
                    if (isInRect(mouseX, mouseY, tx, tabY, tabW, TAB_H)) {
                        if (activeTab != i) {
                            activeTab = i;
                            rebuild();
                            return true;
                        }
                    }
                }
            }

            // Custom Buttons click
            for (CustomButton btn : customButtons) {
                if (btn.enabled() && isInRect(mouseX, mouseY, btn.x(), btn.y(), btn.w(), btn.h())) {
                    btn.action().run();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        if (deltaY != 0) {
            if (activeTab == 1 && scanner.getCategory().equals("shop")) {
                int totalPages = Math.max(1, (scanner.getShopGoods().size() + GOODS_PER_PAGE - 1) / GOODS_PER_PAGE);
                if (deltaY < 0 && shopGoodsPage < totalPages - 1) {
                    shopGoodsPage++;
                    rebuild();
                    return true;
                } else if (deltaY > 0 && shopGoodsPage > 0) {
                    shopGoodsPage--;
                    rebuild();
                    return true;
                }
            } else if (activeTab == 2) {
                int totalPages = Math.max(1, (ScannerPresetStore.listPresets().size() + PRESETS_PER_PAGE - 1) / PRESETS_PER_PAGE);
                if (deltaY < 0 && presetsPage < totalPages - 1) {
                    presetsPage++;
                    rebuild();
                    return true;
                } else if (deltaY > 0 && presetsPage > 0) {
                    presetsPage--;
                    rebuild();
                    return true;
                }
            }
        }
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
    }

    @Override
    public void tick() {
        if (needsRebuild) {
            needsRebuild = false;
            rebuild();
        }
    }

    private void rebuild() {
        super.clearWidgets();
        init();
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // ACTIONS & LOGIC
    // ─────────────────────────────────────────────────────────────────────────────

    private void onBoundaryEdit() {
        scanner.setBoundary(
                BlockOffset.of(intOrZero(bMinX), intOrZero(bMinY), intOrZero(bMinZ)),
                BlockOffset.of(intOrZero(bMaxX), intOrZero(bMaxY), intOrZero(bMaxZ)));
        syncToServer();
    }

    private int getDoorAxis(int axis) {
        BlockOffset off = scanner.getDoorOffset();
        if (off == null) return 0;
        return switch (axis) {
            case 0 -> off.x();
            case 1 -> off.y();
            default -> off.z();
        };
    }

    private void onDoorChanged() {
        if (doorX == null || doorY == null || doorZ == null) return;
        String xs = doorX.getValue();
        String ys = doorY.getValue();
        String zs = doorZ.getValue();
        if (xs.isEmpty() || ys.isEmpty() || zs.isEmpty()) return;
        try {
            BlockOffset off = BlockOffset.of(Integer.parseInt(xs), Integer.parseInt(ys), Integer.parseInt(zs));
            if (scanner.getDoorOffsets().isEmpty()) {
                scanner.setDoorOffsets(List.of(off));
            } else {
                scanner.updateDoorOffset(0, off);
            }
            syncToServer();
        } catch (NumberFormatException ignored) {}
    }

    private void onAutoDetectDoor() {
        if (minecraft == null || minecraft.level == null) return;
        List<BlockOffset> doors = scanner.detectDoors(minecraft.level);
        if (doors.isEmpty()) {
            showFeedback(I18n.name("gui.wandscape.scanner.result_no_door_found", "§e⚠ 未在当前 3D 包围盒内检测到门方块"), 0xFFFFAA00);
            scanResult = I18n.name("gui.wandscape.scanner.result_no_door_found", "§e未在包围盒内检测到门方块。");
            return;
        }
        scanner.setDoorOffsets(doors);
        BlockOffset first = doors.get(0);
        if (doorX != null) doorX.setValue(String.valueOf(first.x()));
        if (doorY != null) doorY.setValue(String.valueOf(first.y()));
        if (doorZ != null) doorZ.setValue(String.valueOf(first.z()));
        showFeedback(I18n.name("gui.wandscape.scanner.result_doors_found", "§a✓ 已在包围盒内自动检出 %s 扇门", String.valueOf(doors.size())), 0xFF55FF55);
        scanResult = I18n.name("gui.wandscape.scanner.result_doors_found", "§a已自动检门 %s 扇，游客可从任意一扇门进入", String.valueOf(doors.size()));
        syncToServer();
        rebuild();
    }

    private void cycleCategory(int dir) {
        if (isSurvival) return;
        int idx = getCategoryIndex(scanner.getCategory());
        int next = (idx + dir + CATEGORIES.size()) % CATEGORIES.size();
        scanner.setCategory(CATEGORIES.get(next).id());
        syncToServer();
        CategoryDef curCat = CATEGORIES.get(next);
        showFeedback(Component.literal("§e" + I18n.string("gui.wandscape.scanner.category_label", "分类:") + " " + curCat.icon() + " " + curCat.label()), 0xFFD4A840);
        rebuild();
    }

    private void onPresetSave() {
        if (presetNameEdit == null) return;
        String name = presetNameEdit.getValue().trim();
        if (name.isEmpty()) {
            showFeedback(I18n.name("gui.wandscape.scanner.result_need_preset_name", "§c⚠ 请先输入预设名称"), 0xFFFF5555);
            scanResult = I18n.name("gui.wandscape.scanner.result_need_preset_name", "§c请先输入预设名称。");
            return;
        }
        ScannerPresetStore.savePreset(name, capturePresetData());
        showFeedback(I18n.name("gui.wandscape.scanner.result_preset_saved", "§a✓ 预设已成功保存: %s", name), 0xFF55FF55);
        scanResult = I18n.name("gui.wandscape.scanner.result_preset_saved", "§a预设已成功保存: %s", name);
        rebuild();
    }

    private void loadPresetByName(String name) {
        CompoundTag tag = ScannerPresetStore.loadPreset(name);
        if (tag == null) {
            showFeedback(I18n.name("gui.wandscape.scanner.result_preset_not_found", "§c⚠ 未找到预设模板: %s", name), 0xFFFF5555);
            scanResult = I18n.name("gui.wandscape.scanner.result_preset_not_found", "§c未找到预设: %s", name);
            return;
        }
        applyPresetData(tag);
        syncToServer();
        showFeedback(I18n.name("gui.wandscape.scanner.result_preset_loaded", "§a✓ 预设已成功加载: %s", name), 0xFF55FF55);
        scanResult = I18n.name("gui.wandscape.scanner.result_preset_loaded", "§a预设已成功加载: %s", name);
        rebuild();
    }

    private void deletePresetByName(String name) {
        ScannerPresetStore.deletePreset(name);
        showFeedback(I18n.name("gui.wandscape.scanner.result_preset_deleted", "§6✓ 预设模板已删除: %s", name), 0xFFFFAA00);
        scanResult = I18n.name("gui.wandscape.scanner.result_preset_deleted", "§e预设已删除: %s", name);
        rebuild();
    }

    private void doScan() {
        BlockPos wMin = scanner.getWorldMin();
        BlockPos wMax = scanner.getWorldMax();
        if (wMin == null || wMax == null) {
            showFeedback(I18n.name("gui.wandscape.scanner.result_no_boundary", "§c⚠ 未定义 3D 边界范围"), 0xFFFF5555);
            scanResult = I18n.name("gui.wandscape.scanner.result_no_boundary", "§c未定义 3D 边界范围。");
            return;
        }
        int count = 0;
        BlockPos scannerPos = scanner.getBlockPos();
        if (minecraft != null && minecraft.level != null) {
            for (int x = wMin.getX(); x <= wMax.getX(); x++) {
                for (int y = wMin.getY(); y <= wMax.getY(); y++) {
                    for (int z = wMin.getZ(); z <= wMax.getZ(); z++) {
                        BlockPos bp = new BlockPos(x, y, z);
                        if (bp.equals(scannerPos)) continue;
                        var state = minecraft.level.getBlockState(bp);
                        if (state.is(com.wsteam.wandscape.Wandscape.BUILDING_SCANNER.get())
                                || state.is(com.wsteam.wandscape.Wandscape.CREATIVE_BUILDING_SCANNER.get())
                                || state.is(com.wsteam.wandscape.Wandscape.INTERACT_SPOT_MARKER.get())) continue;
                        if (!state.isAir()) count++;
                    }
                }
            }
        }
        showFeedback(I18n.name("gui.wandscape.scanner.result_scanned", "§a✓ 扫描完成！共 %s 个有效方块", String.valueOf(count)), 0xFF55FF55);
        scanResult = I18n.name("gui.wandscape.scanner.result_scanned", "§a已扫描区域有效方块: %s 个 (已排除扫描器)", String.valueOf(count));
    }

    private void doExport() {
        String id = scanner.getBuildingId();
        if (id == null || id.isBlank()) {
            showFeedback(I18n.name("gui.wandscape.scanner.result_need_id", "§c⚠ 请先在属性配置页设置建筑/道路 ID！"), 0xFFFF5555);
            scanResult = I18n.name("gui.wandscape.scanner.result_need_id", "§c导出失败: 请先在属性配置页设置建筑 ID！");
            return;
        }
        PacketDistributor.sendToServer(new ScannerExportPacket(scanner.getBlockPos()));
        showFeedback(I18n.name("gui.wandscape.scanner.result_export_started", "§a✓ 已发起导出与热注册: %s！", id), 0xFF55FF55);
        scanResult = I18n.name("gui.wandscape.scanner.result_export_started", "§a已发起导出与热注册: %s (详见游戏聊天区)", id);
    }

    private void doValue() {
        BlockPos wMin = scanner.getWorldMin();
        BlockPos wMax = scanner.getWorldMax();
        if (wMin == null || wMax == null) {
            showFeedback(I18n.name("gui.wandscape.scanner.result_no_boundary", "§c⚠ 未定义 3D 边界范围"), 0xFFFF5555);
            scanResult = I18n.name("gui.wandscape.scanner.result_no_boundary", "§c未定义 3D 边界。");
            return;
        }
        PacketDistributor.sendToServer(new ScannerValuePacket(scanner.getBlockPos()));
        showFeedback(I18n.name("gui.wandscape.scanner.result_value_started", "§a✓ 已发起价值估算，请查看聊天区"), 0xFF55FF55);
        scanResult = I18n.name("gui.wandscape.scanner.result_value_started", "§a已发起区域元素价值计算，结果已输出到聊天区。");
    }

    private void syncToServer() {
        if (minecraft == null || minecraft.level == null) return;
        if (isSurvival) {
            scanner.setCategory("custom");
        }
        CompoundTag tag = scanner.saveWithoutMetadata(minecraft.level.registryAccess());
        PacketDistributor.sendToServer(new ScannerSyncPacket(scanner.getBlockPos(), tag));
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // PRESET SERIALIZATION
    // ─────────────────────────────────────────────────────────────────────────────

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
        if (tag.contains("boundary_min", Tag.TAG_INT_ARRAY)) {
            int[] arr = tag.getIntArray("boundary_min");
            if (arr.length == 3) scanner.setBoundary(BlockOffset.of(arr[0], arr[1], arr[2]), scanner.getBoundaryMax());
        }
        if (tag.contains("boundary_max", Tag.TAG_INT_ARRAY)) {
            int[] arr = tag.getIntArray("boundary_max");
            if (arr.length == 3) scanner.setBoundary(scanner.getBoundaryMin(), BlockOffset.of(arr[0], arr[1], arr[2]));
        }
        if (tag.contains("category")) {
            if (isSurvival) scanner.setCategory("custom");
            else scanner.setCategory(tag.getString("category"));
        }
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

        if (tag.contains("relax_energy_restore")) scanner.setRelaxEnergyRestore(tag.getInt("relax_energy_restore"));
        if (tag.contains("relax_duration")) scanner.setRelaxInteractionDurationTicks(tag.getInt("relax_duration"));
        if (tag.contains("atm_withdraw_amount")) scanner.setAtmWithdrawAmount(tag.getInt("atm_withdraw_amount"));
        if (tag.contains("atm_duration")) scanner.setAtmInteractionDurationTicks(tag.getInt("atm_duration"));
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // HELPERS & WIDGET FACTORIES
    // ─────────────────────────────────────────────────────────────────────────────

    private EditBox mkEdit(int x, int y, int w, int h, String val, Consumer<String> r) {
        EditBox box = new EditBox(font, x + 3, y + 3, w - 6, h - 6, Component.empty());
        box.setValue(val != null ? val : "");
        box.setBordered(false);
        box.setTextColor(MedievalColors.TEXT_WARM_WHITE);
        box.setTextColorUneditable(MedievalColors.TEXT_MUTED);
        box.setResponder(r);
        insetFields.add(new FieldRect(x, y, w, h, box));
        return addRenderableWidget(box);
    }

    private EditBox mkNumEdit(int x, int y, int w, int h, int val, Consumer<String> r) {
        EditBox box = new EditBox(font, x + 3, y + 3, w - 6, h - 6, Component.empty());
        box.setFilter(s -> s.matches("\\d*"));
        box.setValue(String.valueOf(val));
        box.setBordered(false);
        box.setTextColor(MedievalColors.TEXT_WARM_WHITE);
        box.setTextColorUneditable(MedievalColors.TEXT_MUTED);
        box.setResponder(r);
        insetFields.add(new FieldRect(x, y, w, h, box));
        return addRenderableWidget(box);
    }

    private EditBox mkCoordEdit(int x, int y, int w, int h, int val, Runnable onChange) {
        EditBox box = new EditBox(font, x + 3, y + 3, w - 6, h - 6, Component.empty());
        box.setMaxLength(6);
        box.setFilter(s -> s.matches("-?\\d{0,6}"));
        box.setValue(String.valueOf(val));
        box.setBordered(false);
        box.setTextColor(MedievalColors.TEXT_WARM_WHITE);
        box.setTextColorUneditable(MedievalColors.TEXT_MUTED);
        box.setResponder(s -> onChange.run());
        insetFields.add(new FieldRect(x, y, w, h, box));
        return addRenderableWidget(box);
    }

    private void drawEditBoxBorder(GuiGraphics gui, int x, int y, int w, int h, boolean focused, boolean hover) {
        drawInsetField(gui, x, y, w, h);
        int borderColor = focused ? MedievalColors.BORDER_GOLD : (hover ? 0xAAFFD700 : 0x55806848);
        gui.fill(x, y, x + w, y + 1, borderColor);
        gui.fill(x, y + h - 1, x + w, y + h, borderColor);
        gui.fill(x, y, x + 1, y + h, borderColor);
        gui.fill(x + w - 1, y, x + w, y + h, borderColor);
        if (focused) {
            gui.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0x18FFD700);
        }
    }

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

    private static boolean isTouristCategory(String c) {
        return "shop".equals(c) || "service".equals(c) || "relax".equals(c) || "atm".equals(c);
    }

    private static CategoryDef getCategoryDef(String id) {
        for (CategoryDef c : CATEGORIES) {
            if (c.id().equals(id)) return c;
        }
        return CATEGORIES.get(0);
    }

    private static int getCategoryIndex(String id) {
        for (int i = 0; i < CATEGORIES.size(); i++) {
            if (CATEGORIES.get(i).id().equals(id)) return i;
        }
        return 0;
    }

    private static ElementDef getElementDef(String id) {
        for (ElementDef e : ELEMENTS) {
            if (e.id().equals(id)) return e;
        }
        return ELEMENTS.get(0);
    }

    private static int getElementIndex(String id) {
        for (int i = 0; i < ELEMENTS.size(); i++) {
            if (ELEMENTS.get(i).id().equals(id)) return i;
        }
        return 0;
    }

    private static String nextUnusedElement(Map<String, Integer> current) {
        for (ElementDef el : ELEMENTS) {
            if (!current.containsKey(el.id())) return el.id();
        }
        return null;
    }

    private static int intOrZero(EditBox box) {
        if (box == null) return 0;
        return intOrZero(box.getValue());
    }

    private static int intOrZero(String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}