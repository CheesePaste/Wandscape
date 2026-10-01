package com.wsteam.wandscape.content.warehouse.client;

import com.wsteam.wandscape.content.element.data.ElementType;
import com.wsteam.wandscape.content.production.network.RequestRecipeBookPacket;
import com.wsteam.wandscape.content.warehouse.WarehouseMenu;
import com.wsteam.wandscape.content.warehouse.WarehousePager;
import com.wsteam.wandscape.content.warehouse.WarehouseSlot;
import com.wsteam.wandscape.content.warehouse.network.WarehouseActionPacket;
import com.wsteam.wandscape.content.warehouse.network.WarehouseDataPacket;
import com.wsteam.wandscape.content.warehouse.network.WarehouseDataPacket.ItemEntry;
import com.wsteam.wandscape.foundation.networking.Net;
import com.wsteam.wandscape.foundation.ui.I18n;
import com.wsteam.wandscape.foundation.ui.component.ElementPanel;
import com.wsteam.wandscape.foundation.ui.component.MedievalContainerScreen;
import com.wsteam.wandscape.foundation.ui.component.ScrollableList;
import com.wsteam.wandscape.foundation.ui.component.SearchBox;
import com.wsteam.wandscape.foundation.ui.theme.MedievalColors;
import com.wsteam.wandscape.foundation.util.ItemKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.*;

import static com.wsteam.wandscape.content.warehouse.WarehouseMenu.HEADER_H;
import static com.wsteam.wandscape.content.warehouse.WarehouseMenu.PANEL_H;
import static com.wsteam.wandscape.content.warehouse.WarehouseMenu.PANEL_W;

/**
 * Warehouse GUI — 与其它建筑屏同一套面板格式（继承 {@link MedievalContainerScreen}）。
 *
 * <p><b>总览页</b>：元素存量 + 可搜索的物品清单（无槽位）。
 *
 * <p><b>交换页</b>：左侧仍是原版 6 行箱（{@code generic_54} 贴图，槽框画在贴图里），
 * 上半 54 个只读仓库格 + 下半玩家背包/快捷栏真槽位；仓库侧交互走
 * {@link WarehouseActionPacket}，玩家槽位是真原版 {@link Slot}，快捷键与背包排序 mod 照常可用。
 * 页签与「配方」按钮浮在面板上方（面板内的头部由基类绘制：标题/状态/关闭/帮助/修复/拆除）。
 *
 * <p>面板比其它建筑屏高出一个头部（{@code HEADER_H}）：原版箱子贴图 222px 无法与 22px 头部
 * 同塞进 230px。槽位的 Y 坐标由 {@link WarehouseMenu} 统一加了同一个偏移，两边必须一致。
 */
public class WarehouseScreen extends MedievalContainerScreen<WarehouseMenu> {

    // ── 原版 6 行箱贴图（槽框随贴图，槽位坐标须与之对齐） ──
    private static final int CHEST_W = 176;
    private static final int CHEST_H = 222;
    private static final int CHEST_TOP = HEADER_H;

    // X 销毁格：右列，避开底部「修复/拆除」
    private static final int TRASH_SIZE = 18;
    private static final int TRASH_RIGHT_MARGIN = 14;
    private static final int TRASH_BOTTOM_MARGIN = 66;

    private static final int TOOLBAR_H = 20;
    private static final int OVERVIEW_PAD = 8;
    private static final int FOOTER_RESERVE = 28;

    private static final WarehousePager PAGER = new WarehousePager(54);
    private static final Comparator<ItemEntry> BY_ID = Comparator.comparing(ItemEntry::itemId);
    private static final ResourceLocation CHEST_TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/gui/container/generic_54.png");

    private static final int GLASS_BOX_TOP = 0xBB423020;
    private static final int GLASS_BOX_BOTTOM = 0xBB1C1008;

    private static final String[] TAB_KEYS = {
            "gui.wandscape.warehouse.overview",
            "gui.wandscape.warehouse.exchange"
    };
    private static final String[] TAB_FALLBACK = {"Overview", "Exchange"};

    private int activeTab;
    private int page;
    private int totalPages = 1;
    private String query = "";

    // Data
    private List<ItemEntry> allItems = new ArrayList<>();
    private List<ItemEntry> visibleEntries = new ArrayList<>();
    private List<ItemStack> visibleStacks = new ArrayList<>();
    private Map<ElementType, Long> elements = new LinkedHashMap<>();

    // 仓库容量读数（随每次 WarehouseDataPacket 刷新；cap<=0 = 未设上限，不显示）
    private long usedCapacity;
    private long capacity;

    // ── Overview widgets ──
    private ElementPanel elementPanel;
    private SearchBox searchInput;
    private ScrollableList<ItemEntry> overviewList;

    // ── Exchange widgets ──
    private SearchBox exchangeSearchInput;

    // ── 浮动工具栏几何（tabs + 配方）──
    private int toolbarY;
    private final int[] tabX = new int[2];
    private final int[] tabW = new int[2];
    private int recipeBtnX, recipeBtnY, recipeBtnW, recipeBtnH;
    // ── Exchange 分页控件 / 销毁格几何 ──
    private int prevX, prevY, prevW, prevH;
    private int nextX, nextY, nextW, nextH;
    private boolean prevActive;
    private boolean nextActive;
    private int trashX, trashY;
    private UUID colonyId;

    public WarehouseScreen(WarehouseMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title, PANEL_W, PANEL_H);
        setTitleBar(I18n.name("gui.wandscape.warehouse.title", "Colony Warehouse"));
        this.showCloseButton = true;
        this.showHelpButton = true;
        this.helpDocumentPath = "warehouse_guide";
    }

    // ── 数据更新 ──

    public void updateItems(WarehouseDataPacket packet) {
        this.colonyId = packet.colonyId();
        if (packet.creator() != null && !packet.creator().isBlank()) {
            setCreator(packet.creator());
        }
        // 从仓库建筑打开时才有建筑上下文；便携终端打开时 buildingId 为空，头部只显示标题。
        if (packet.buildingId() != null && !isBuildingScreen) {
            setBuildingContext(packet.buildingId(), packet.buildingPos());
        }
        this.usedCapacity = packet.usedCapacity();
        this.capacity = packet.capacity();
        this.allItems = new ArrayList<>(packet.itemEntries());
        this.allItems.sort(BY_ID);
        this.elements = new LinkedHashMap<>(packet.elementMap());
        if (elementPanel != null) {
            elementPanel.setElements(elements);
        }
        if (searchInput != null) {
            overviewList.setItems(filterItems(searchInput.getValue()));
        }
        recomputeVisible();
    }

    // ── 初始化 / 切换 ──

    /** 浮动页签条位于面板之外，矮屏上贴顶保证它可见。 */
    @Override
    protected void positionPanel() {
        this.topPos = Math.max((this.height - imageHeight) / 2, TOOLBAR_H + 4);
    }

    @Override
    protected void initContent() {
        computeToolbar();
        buildOverviewTab();
        buildExchangeTab();
        showTab(activeTab);
        menu.bindSlots(this::getEntryStack, () -> activeTab == 1);
        recomputeVisible();
    }

    /** 创建者署名画在箱子贴图下方的窄条里（默认位置会压到贴图）。 */
    @Override
    protected int creatorFooterY() {
        return topPos + CHEST_TOP + CHEST_H + 2;
    }

    private void switchTab(int tabIndex) {
        if (tabIndex == activeTab) return;
        this.activeTab = tabIndex;
        // rebuildWidgets → clearWidgets + init()：重建尺寸/原点/控件
        this.rebuildWidgets();
    }

    private void buildOverviewTab() {
        int cx = leftPos + OVERVIEW_PAD;
        int cy = topPos + HEADER_H + OVERVIEW_PAD;
        int elementW = 130;

        elementPanel = new ElementPanel(cx, cy, elementW);
        elementPanel.setElements(elements);

        int rightX = cx + elementW + 6;
        int rightW = PANEL_W - 16 - elementW - 6;
        int searchH = font.lineHeight + 6;

        searchInput = new SearchBox(font, rightX + 1, cy + 2, rightW - 2,
                I18n.name("gui.wandscape.warehouse.search", "Search items..."));
        searchInput.setResponder(q -> overviewList.setItems(filterItems(q)));

        int listY = cy + searchH + 4;
        int listH = topPos + PANEL_H - listY - 6 - FOOTER_RESERVE;
        overviewList = buildItemList(rightX, listY, rightW, listH);
        overviewList.setItems(filterItems(""));
    }

    private void buildExchangeTab() {
        int x = leftPos + CHEST_W + 14;
        exchangeSearchInput = new SearchBox(font, x + 1, topPos + HEADER_H + 4, PANEL_W - CHEST_W - 30,
                I18n.name("gui.wandscape.warehouse.search", "Search items..."));
        exchangeSearchInput.setResponder(q -> {
            query = q;
            page = 0;
            recomputeVisible();
        });
    }

    private void showTab(int tabIndex) {
        if (elementPanel != null) removeWidget(elementPanel);
        if (searchInput != null) removeWidget(searchInput);
        if (overviewList != null) removeWidget(overviewList);
        if (exchangeSearchInput != null) removeWidget(exchangeSearchInput);
        if (tabIndex == 0) {
            addRenderableWidget(elementPanel);
            addRenderableWidget(searchInput);
            addRenderableWidget(overviewList);
        } else {
            addRenderableWidget(exchangeSearchInput);
        }
        // 仓库槽由 menu slots 原样渲染；分页/滚轮转移手工命中。
        recomputeVisible();
    }

    private ScrollableList<ItemEntry> buildItemList(int x, int y, int w, int h) {
        return new ScrollableList<>(x, y, w, h, 20) {
            @Override
            protected void renderRow(GuiGraphics g, ItemEntry item, int rx, int ry, int index,
                                     boolean selected, boolean hovered) {
                ItemStack icon = toStack(item);
                g.renderItem(icon, rx, ry + 2);
                Component name = icon.isEmpty() ? Component.literal(item.itemId()) : icon.getHoverName();
                int textColor = selected ? MedievalColors.BORDER_GOLD
                        : hovered ? MedievalColors.TEXT_WARM_WHITE
                        : MedievalColors.TEXT_MUTED;
                g.drawString(Minecraft.getInstance().font, name, rx + 20, ry + 3, textColor);

                String count = WarehousePager.formatCount(item.count());
                int countW = Minecraft.getInstance().font.width(count);
                g.drawString(Minecraft.getInstance().font, count,
                        rx + getWidth() - 6 - countW - 8, ry + 3,
                        MedievalColors.TEXT_MUTED);
            }
        };
    }

    // ── 浮动工具栏 ──

    /** 现算工具栏几何（render 与 click 用同一套坐标）。 */
    private void computeToolbar() {
        toolbarY = topPos - TOOLBAR_H - 2;
        int cx = leftPos + 4;
        for (int i = 0; i < 2; i++) {
            int tw = font.width(tabLabel(i)) + 16;
            tabX[i] = cx;
            tabW[i] = tw;
            cx += tw + 4;
        }

        String recipeLabel = I18n.name("gui.wandscape.warehouse.btn_recipes", "配方").getString();
        recipeBtnW = font.width(recipeLabel) + 14;
        recipeBtnH = 14;
        recipeBtnX = leftPos + imageWidth - recipeBtnW - 4;
        recipeBtnY = toolbarY + (TOOLBAR_H - recipeBtnH) / 2;
    }

    /** Exchange 右列分页控件几何。 */
    private void computePager() {
        int baseX = leftPos + CHEST_W + 14;
        prevW = 18;
        prevH = 12;
        prevY = topPos + HEADER_H + 34;
        prevX = baseX;
        nextW = 18;
        nextH = 12;
        nextY = prevY;
        nextX = baseX + prevW + 6;
    }

    /** X 销毁格几何：右列底部、与面板右下角留边距（避开修复/拆除按钮）。 */
    private void computeTrash() {
        trashX = leftPos + PANEL_W - TRASH_RIGHT_MARGIN - TRASH_SIZE;
        trashY = topPos + PANEL_H - TRASH_BOTTOM_MARGIN - TRASH_SIZE;
    }

    private void renderToolbar(GuiGraphics g, int mouseX, int mouseY) {
        computeToolbar();
        g.fillGradient(leftPos, toolbarY, leftPos + imageWidth, toolbarY + TOOLBAR_H,
                GLASS_BOX_TOP, GLASS_BOX_BOTTOM);
        drawGlowBorder(g, leftPos, toolbarY, imageWidth, TOOLBAR_H, MedievalColors.BORDER_GOLD);

        for (int i = 0; i < 2; i++) {
            boolean active = i == activeTab;
            boolean hovered = !active && isInRect(mouseX, mouseY, tabX[i], toolbarY, tabW[i], TOOLBAR_H);
            drawMinimalBox(g, tabX[i], toolbarY, tabW[i], TOOLBAR_H, active, hovered);
            int color = active ? MedievalColors.BORDER_GOLD
                    : hovered ? MedievalColors.TEXT_WARM_WHITE
                    : MedievalColors.TEXT_MUTED;
            g.drawString(font, tabLabel(i),
                    tabX[i] + (tabW[i] - font.width(tabLabel(i))) / 2,
                    toolbarY + (TOOLBAR_H - font.lineHeight) / 2, color);
        }

        String recipeLabel = I18n.name("gui.wandscape.warehouse.btn_recipes", "配方").getString();
        drawNavButton(g, recipeBtnX, recipeBtnY, recipeBtnW, recipeBtnH, recipeLabel, true, mouseX, mouseY);
    }

    private void drawNavButton(GuiGraphics g, int x, int y, int w, int h, String label,
                               boolean active, int mouseX, int mouseY) {
        boolean hovered = isInRect(mouseX, mouseY, x, y, w, h);
        drawMinimalBox(g, x, y, w, h, active && hovered, !active && hovered);
        int color = active ? MedievalColors.TEXT_WARM_WHITE : MedievalColors.TEXT_DIM;
        g.drawString(font, label, x + (w - font.width(label)) / 2,
                y + (h - font.lineHeight) / 2, color);
    }

    private String tabLabel(int i) {
        return I18n.name(TAB_KEYS[i], TAB_FALLBACK[i]).getString();
    }

    private boolean handleToolbarClick(double mouseX, double mouseY, int button) {
        if (button != 0) return false;
        computeToolbar();
        if (isInRect(mouseX, mouseY, tabX[0], toolbarY, tabW[0], TOOLBAR_H) && activeTab != 0) {
            switchTab(0);
            return true;
        }
        if (isInRect(mouseX, mouseY, tabX[1], toolbarY, tabW[1], TOOLBAR_H) && activeTab != 1) {
            switchTab(1);
            return true;
        }
        if (isInRect(mouseX, mouseY, recipeBtnX, recipeBtnY, recipeBtnW, recipeBtnH)) {
            openRecipeBook();
            return true;
        }
        return false;
    }

    private void openRecipeBook() {
        if (colonyId != null) {
            Net.toServer(new RequestRecipeBookPacket(colonyId));
        }
    }

    /** Exchange 右列分页按钮点击（仅 Exchange 页可命中）。 */
    private boolean handlePagerClick(double mouseX, double mouseY, int button) {
        if (button != 0 || activeTab != 1) return false;
        computePager();
        if (prevActive && isInRect(mouseX, mouseY, prevX, prevY, prevW, prevH)) {
            page--;
            recomputeVisible();
            return true;
        }
        if (nextActive && isInRect(mouseX, mouseY, nextX, nextY, nextW, nextH)) {
            page++;
            recomputeVisible();
            return true;
        }
        return false;
    }

    /** X 销毁格点击：左键销毁整叠、右键销毁 1 个（仅 Exchange 页、需光标持有物品）。 */
    private boolean handleTrashClick(double mouseX, double mouseY, int button) {
        if (button != 0 && button != 1) return false;
        if (activeTab != 1) return false;
        computeTrash();
        if (!isInRect(mouseX, mouseY, trashX, trashY, TRASH_SIZE, TRASH_SIZE)) return false;
        // 区域命中即吞掉点击；无光标物品时无事发生（服务端同款守卫）。
        if (menu.getCarried().isEmpty()) return true;
        String action = button == 1
                ? WarehouseActionPacket.ACTION_CURSOR_DESTROY_ONE
                : WarehouseActionPacket.ACTION_CURSOR_DESTROY_ALL;
        Net.toServer(new WarehouseActionPacket(
                menu.containerId, action, "", null, 0));
        return true;
    }

    // ── 渲染 ──

    @Override
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderToolbar(g, mouseX, mouseY);
        if (activeTab == 0) {
            drawOverviewCapacity(g);
        } else {
            renderExchangeContent(g, mouseX, mouseY);
        }
    }

    /** Exchange 背景：面板左侧贴原版 6 行箱（仓库格+玩家背包），右列放搜索/分页/容量/销毁格。 */
    private void renderExchangeContent(GuiGraphics g, int mouseX, int mouseY) {
        g.blit(CHEST_TEXTURE, leftPos, topPos + CHEST_TOP, 0, 0, CHEST_W, CHEST_H);
        drawExchangeCapacity(g);
        renderPager(g, mouseX, mouseY);
        renderTrash(g, mouseX, mouseY);
    }

    /** 总览页：元素 7 行下方画容量读数（未设上限则隐藏）。 */
    private void drawOverviewCapacity(GuiGraphics g) {
        if (capacity <= 0) return;
        int x = leftPos + OVERVIEW_PAD;
        int y = topPos + HEADER_H + OVERVIEW_PAD + 7 * 18 + 4;
        g.drawString(font, capacityText(), x, y, capacityColor());
    }

    /** Exchange 页：右列搜索框下方画容量读数（未设上限则隐藏）。 */
    private void drawExchangeCapacity(GuiGraphics g) {
        if (capacity <= 0) return;
        int x = leftPos + CHEST_W + 14;
        int y = topPos + HEADER_H + 4 + font.lineHeight + 6 + 3;
        g.drawString(font, capacityText(), x, y, capacityColor());
    }

    private String capacityText() {
        return I18n.name("gui.wandscape.warehouse.capacity", "Space %s/%s",
                usedCapacity, capacity).getString();
    }

    /** 已满（used>=cap>0）时用警示色，否则常规暗色；未设上限返回正常色（不显示）。 */
    private int capacityColor() {
        if (capacity <= 0) return MedievalColors.TEXT_MUTED;
        return usedCapacity >= capacity ? 0xFFFF6B5E : MedievalColors.TEXT_MUTED;
    }

    private void renderPager(GuiGraphics g, int mouseX, int mouseY) {
        computePager();
        int x = leftPos + CHEST_W + 14;
        drawNavButton(g, prevX, prevY, prevW, prevH, "◀", prevActive, mouseX, mouseY);
        drawNavButton(g, nextX, nextY, nextW, nextH, "▶", nextActive, mouseX, mouseY);
        String pageText = I18n.name("gui.wandscape.warehouse.page", "%s / %s",
                page + 1, totalPages).getString();
        g.drawString(font, pageText, x, prevY + prevH + 6, MedievalColors.TEXT_MUTED);
    }

    /** X 销毁格：槽位式小按钮 + 红 ×；光标有物品时点亮（可销毁），否则置灰提示先拾起。 */
    private void renderTrash(GuiGraphics g, int mouseX, int mouseY) {
        computeTrash();
        boolean hovered = isInRect(mouseX, mouseY, trashX, trashY, TRASH_SIZE, TRASH_SIZE);
        drawMinimalBox(g, trashX, trashY, TRASH_SIZE, TRASH_SIZE, false, hovered);
        int iconColor = menu.getCarried().isEmpty()
                ? MedievalColors.TEXT_DIM
                : hovered ? 0xFFFF7A6B : 0xFFE05040;
        drawTrashGlyph(g, iconColor);
    }

    /** 把字体 × 放大居中画进销毁格（18px 格内约居其 2/3）。 */
    private void drawTrashGlyph(GuiGraphics g, int color) {
        String glyph = "×";
        float scale = 1.8F;
        g.pose().pushPose();
        g.pose().translate(trashX + TRASH_SIZE / 2F, trashY + TRASH_SIZE / 2F, 100F);
        g.pose().scale(scale, scale, 1F);
        g.drawString(font, glyph, -font.width(glyph) / 2, -font.lineHeight / 2, color, false);
        g.pose().popPose();
    }

    @Override
    protected void renderForeground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderTrashTooltip(g, mouseX, mouseY);
    }

    /** 悬停 X 时提示用途（仅 Exchange 页）。 */
    private void renderTrashTooltip(GuiGraphics g, int mouseX, int mouseY) {
        if (activeTab != 1) return;
        computeTrash();
        if (!isInRect(mouseX, mouseY, trashX, trashY, TRASH_SIZE, TRASH_SIZE)) return;
        List<Component> lines = List.of(
                I18n.name("gui.wandscape.warehouse.trash", "Delete item"),
                I18n.name("gui.wandscape.warehouse.trash_hint",
                        "Pick up an item, then click here to delete it"));
        g.renderComponentTooltip(font, lines, mouseX, mouseY);
    }

    @Override
    protected void renderSlot(GuiGraphics g, Slot slot) {
        if (slot instanceof WarehouseSlot) {
            renderWarehouseSlot(g, slot);
        } else {
            super.renderSlot(g, slot);
        }
    }

    /** 原版箱子格样式：不画自绘边框（纹理自带槽框），数量按 RS 式白字描边显示在图标右上。 */
    private void renderWarehouseSlot(GuiGraphics g, Slot slot) {
        ItemStack stack = slot.getItem();
        if (stack.isEmpty()) return;
        g.renderItem(stack, slot.x, slot.y, slot.x + slot.y * imageWidth);
        long count = slot.index < visibleEntries.size() ? visibleEntries.get(slot.index).count() : 0;
        if (count > 1) {
            renderAmount(g, slot.x, slot.y, count);
        }
    }

    /** RS 式数量：z 抬到图标之上（避免被贴图盖住）、白字描边、长文本半尺寸。 */
    private void renderAmount(GuiGraphics g, int x, int y, long count) {
        String text = WarehousePager.formatCount(count);
        boolean large = font.width(text) <= 16;
        g.pose().pushPose();
        g.pose().translate(x + (large ? 1D : 0D), y + (large ? 1D : 0D), 300D);
        if (!large) {
            g.pose().scale(0.5F, 0.5F, 1F);
        }
        g.drawString(font, text, (large ? 16 : 30) - font.width(text), large ? 8 : 22, 0xFFFFFF, true);
        g.pose().popPose();
    }

    // ── 输入 ──

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 浮动工具栏（tabs/配方）优先；tab 必须在 super 前拦截，
        // 因原版容器屏的 mouseClicked 走到槽位逻辑后无条件返回 true。
        if (handleToolbarClick(mouseX, mouseY, button)) {
            return true;
        }
        if (handlePagerClick(mouseX, mouseY, button)) {
            return true;
        }
        if (handleTrashClick(mouseX, mouseY, button)) {
            return true;
        }
        if (activeTab == 1 && (button == 0 || button == 1)) {
            Slot slot = findWarehouseSlot(mouseX, mouseY);
            if (slot != null) {
                handleWarehouseSlotClick((WarehouseSlot) slot, button);
                return true;
            }
            // RS 语义：光标带物品时，点击存储区任意位置（含空白）都存入。
            if (!menu.getCarried().isEmpty() && isOverStorageArea(mouseX, mouseY)) {
                sendDepositAction(button);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 存储区矩形（原版 6 行箱格区，相对面板：8..170 × (HEADER_H+18)..(HEADER_H+126)）。 */
    private boolean isOverStorageArea(double mouseX, double mouseY) {
        int x0 = leftPos + 8;
        int y0 = topPos + HEADER_H + 18;
        return mouseX >= x0 && mouseX < x0 + 9 * 18
                && mouseY >= y0 && mouseY < y0 + 6 * 18;
    }

    /** 空白区存入：左键整叠、右键单个（服务端按光标操作，无需 itemId）。 */
    private void sendDepositAction(int button) {
        String action = button == 1
                ? WarehouseActionPacket.ACTION_CURSOR_DEPOSIT_ONE
                : WarehouseActionPacket.ACTION_CURSOR_DEPOSIT_ALL;
        Net.toServer(new WarehouseActionPacket(
                menu.containerId, action, "", null, 0));
    }

    private Slot findWarehouseSlot(double mouseX, double mouseY) {
        for (Slot slot : menu.slots) {
            if (slot instanceof WarehouseSlot && slot.isActive() && slot.hasItem()
                    && isHovering(slot.x, slot.y, 16, 16, mouseX, mouseY)) {
                return slot;
            }
        }
        return null;
    }

    private void handleWarehouseSlotClick(WarehouseSlot slot, int button) {
        if (slot.index >= visibleEntries.size()) return;
        ItemEntry entry = visibleEntries.get(slot.index);

        String action;
        if (button == 0) {
            if (hasShiftDown()) {
                action = WarehouseActionPacket.ACTION_TAKE_TO_INVENTORY;
            } else if (menu.getCarried().isEmpty()) {
                action = WarehouseActionPacket.ACTION_CURSOR_TAKE_ALL;
            } else {
                action = WarehouseActionPacket.ACTION_CURSOR_DEPOSIT_ALL;
            }
        } else {
            action = menu.getCarried().isEmpty()
                    ? WarehouseActionPacket.ACTION_CURSOR_TAKE_HALF
                    : WarehouseActionPacket.ACTION_CURSOR_DEPOSIT_ONE;
        }
        sendAction(entry, action, 0);
    }

    private void sendAction(ItemEntry entry, String action, int param) {
        Net.toServer(new WarehouseActionPacket(
                menu.containerId, action, entry.itemId(), entry.nbt(), param));
    }

    /** RS 式滚轮转移：网格区 Shift+上滚=背包→仓库、Shift+下滚=仓库→背包、Ctrl+下滚=仓库→光标；玩家槽区 Shift 滚轮同理。 */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (activeTab == 1) {
            // MC 语义：scrollY > 0 = 上滚（MouseHandler 直接传 GLFW yOffset）
            double delta = scrollX != 0 ? scrollX : scrollY;
            boolean up = delta > 0;
            // 无修饰滚轮：翻页（Shift/Ctrl 滚轮保留转移功能）
            if (!hasShiftDown() && !Screen.hasControlDown()) {
                page += up ? -1 : 1;
                recomputeVisible(); // PAGER 自动 clamp 到有效页
                return true;
            }
            if (hoveredSlot instanceof WarehouseSlot ws && ws.index < visibleEntries.size()) {
                ItemEntry entry = visibleEntries.get(ws.index);
                if (up && hasShiftDown()) {
                    sendAction(entry, WarehouseActionPacket.ACTION_DEPOSIT_INVENTORY_TYPE, 0);
                    return true;
                }
                if (!up) {
                    if (hasShiftDown()) {
                        sendAction(entry, WarehouseActionPacket.ACTION_TAKE_TO_INVENTORY, 0);
                        return true;
                    }
                    if (Screen.hasControlDown()) {
                        sendAction(entry, WarehouseActionPacket.ACTION_CURSOR_TAKE_ALL, 0);
                        return true;
                    }
                }
            } else if (hasShiftDown() && hoveredSlot != null && hoveredSlot.hasItem()
                    && !(hoveredSlot instanceof WarehouseSlot)) {
                int slotIndex = hoveredSlot.getContainerSlot();
                if (up) {
                    Net.toServer(new WarehouseActionPacket(menu.containerId,
                            WarehouseActionPacket.ACTION_DEPOSIT_SLOT, "", null, slotIndex));
                    return true;
                }
                ItemStack stack = hoveredSlot.getItem();
                var rl = BuiltInRegistries.ITEM.getKey(stack.getItem());
                if (rl != null) {
                    // 发送完整物品键（含全部组件），服务端据此与账本条目精确匹配。
                    CompoundTag nbt = (minecraft != null && minecraft.level != null && !stack.isEmpty())
                            ? ItemKey.fromStack(stack, minecraft.level.registryAccess()).nbt()
                            : null;
                    Net.toServer(new WarehouseActionPacket(menu.containerId,
                            WarehouseActionPacket.ACTION_TAKE_TO_SLOT, rl.toString(), nbt, slotIndex));
                    return true;
                }
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // ── 分页 / 显示数据 ──

    private void recomputeVisible() {
        var result = PAGER.page(allItems, this::matchesQuery, BY_ID, page);
        this.page = result.page();
        this.totalPages = result.totalPages();
        this.visibleEntries = result.entries();
        this.visibleStacks = new ArrayList<>(visibleEntries.size());
        for (ItemEntry entry : visibleEntries) {
            visibleStacks.add(toStack(entry));
        }
        this.prevActive = result.hasPrev();
        this.nextActive = result.hasNext();
    }

    private boolean matchesQuery(ItemEntry entry) {
        return SearchBox.matches(SearchBox.itemSearchText(entry.itemId()), query);
    }

    private List<ItemEntry> filterItems(String query) {
        return SearchBox.filter(allItems, query, e -> SearchBox.itemSearchText(e.itemId()));
    }

    private ItemStack getEntryStack(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= visibleStacks.size()) {
            return ItemStack.EMPTY;
        }
        return visibleStacks.get(slotIndex);
    }

    private ItemStack toStack(ItemEntry entry) {
        var registryItem = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(entry.itemId()));
        if (registryItem == null || registryItem == Items.AIR) return ItemStack.EMPTY;
        int count = (int) Math.min(Math.max(entry.count(), 1), Integer.MAX_VALUE);
        if (minecraft == null || minecraft.level == null) return new ItemStack(registryItem, count);
        // 账本载荷是完整物品序列化（ItemKey 语义）；用当前 level 的 registry 解码还原全组件。
        return ItemKey.of(entry.itemId(), entry.nbt()).toStack(count, minecraft.level.registryAccess());
    }
}
