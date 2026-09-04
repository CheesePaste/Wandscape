package com.wsteam.wandscape.content.warehouse.client;
import com.wsteam.wandscape.content.task.ecs.World;

import com.wsteam.wandscape.WandscapeClient;
import com.wsteam.wandscape.content.element.data.ElementType;
import com.wsteam.wandscape.foundation.ui.I18n;
import com.wsteam.wandscape.foundation.util.ItemKey;
import com.wsteam.wandscape.foundation.ui.ReplayProtectedScreen;
import com.wsteam.wandscape.foundation.ui.component.ElementPanel;
import com.wsteam.wandscape.foundation.ui.component.HelpButton;
import com.wsteam.wandscape.foundation.ui.component.ScrollableList;
import com.wsteam.wandscape.foundation.ui.component.SearchBox;
import com.wsteam.wandscape.foundation.ui.skin.SkinRender;
import com.wsteam.wandscape.foundation.ui.theme.MedievalColors;
import com.wsteam.wandscape.content.warehouse.WarehouseMenu;
import com.wsteam.wandscape.content.warehouse.WarehousePager;
import com.wsteam.wandscape.content.warehouse.WarehouseSlot;
import com.wsteam.wandscape.content.warehouse.network.WarehouseActionPacket;
import com.wsteam.wandscape.content.warehouse.network.WarehouseDataPacket;
import com.wsteam.wandscape.content.warehouse.network.WarehouseDataPacket.ItemEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.*;

import static com.wsteam.wandscape.content.warehouse.WarehouseMenu.PANEL_H;
import static com.wsteam.wandscape.content.warehouse.WarehouseMenu.PANEL_W;

/**
 * Warehouse GUI with two tabs.
 *
 * <p><b>Overview</b>: compact medieval panel — element storage + searchable item
 * list (search driven, no slots).
 *
 * <p><b>Exchange</b>: a real vanilla 6-row chest (the {@code generic_54}
 * texture) — warehouse read-only slots on top, vanilla player inventory below.
 * Warehouse interactions go through {@link WarehouseActionPacket}; the player
 * slots are real vanilla {@link Slot}s so all shortcuts and inventory-sorting
 * mods work on them.
 *
 * <p>A floating toolbar above the panel hosts the tabs, the help/close buttons
 * and (on Exchange) the pager controls. Extends {@link AbstractContainerScreen}
 * so the panel is centred like a vanilla container; on short screens the top is
 * clamped so the toolbar stays visible.
 */
public class WarehouseScreen extends AbstractContainerScreen<WarehouseMenu>
        implements ReplayProtectedScreen, com.wsteam.wandscape.foundation.ui.component.ScreenFeedbackHost {

    // ── 面板：与市政厅统一 300×230；Exchange 左侧贴原版 6 行箱(generic_54) 纹理 ──
    private static final int CHEST_W = 176;
    private static final int CHEST_H = 222;

    // X 销毁格：Exchange 右列底部 18×18（照抄创造模式 X，销毁光标上的物品）
    private static final int TRASH_SIZE = 18;
    private static final int TRASH_RIGHT_MARGIN = 14;
    private static final int TRASH_BOTTOM_MARGIN = 14;

    private static final int TOOLBAR_H = 20;
    private static final int OVERVIEW_PAD = 8;
    private static final int FOOTER_RESERVE = 28;

    private static final WarehousePager PAGER = new WarehousePager(54);
    private static final Comparator<ItemEntry> BY_ID = Comparator.comparing(ItemEntry::itemId);
    private static final ResourceLocation CHEST_TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/gui/container/generic_54.png");

    private static final int GLASS_TOP = 0xBB483828;
    private static final int GLASS_BOTTOM = 0xBB1E1410;
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

    // ── 顶部工具栏命中区域（computeToolbar 现算，render 与 click 共用）──
    private int toolbarY;
    private final int[] tabX = new int[2];
    private final int[] tabW = new int[2];
    private int closeX, closeY, closeW, closeH;
    private int helpX, helpY, helpW, helpH;
    private int prevX, prevY, prevW, prevH;
    private int nextX, nextY, nextW, nextH;
    private boolean prevActive;
    private boolean nextActive;
    private int trashX, trashY;

    // ── 通用皮肤状态 ──
    private String buildingCreator = "";
    private final boolean showCloseButton = true;
    private final boolean showHelpButton = true;
    private final String helpDocumentPath = "warehouse_guide";
    private HelpButton helpButton;
    private Component feedback;
    private int feedbackColor;
    private long feedbackExpireTick;
    private static final long FEEDBACK_DURATION_MS = 3000L;

    public WarehouseScreen(WarehouseMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
    }

    // ── 数据更新 ──

    public void updateItems(WarehouseDataPacket packet) {
        if (packet.creator() != null && !packet.creator().isBlank()) {
            setCreator(packet.creator());
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

    public void setCreator(String creator) {
        this.buildingCreator = creator != null ? creator : "";
    }

    public void showFeedback(Component message, int color) {
        this.feedback = message;
        this.feedbackColor = color;
        this.feedbackExpireTick = System.currentTimeMillis() + FEEDBACK_DURATION_MS;
    }

    // ── 初始化 / 切换 ──

    @Override
    protected void init() {
        configureLayout();
        computeToolbar();
        if (showHelpButton && helpDocumentPath != null) {
            helpButton = new HelpButton(helpX, helpY, helpW, helpH, this::openHelpDocument);
            addRenderableWidget(helpButton);
        }
        buildOverviewTab();
        buildExchangeTab();
        showTab(activeTab);
        menu.bindSlots(this::getEntryStack, () -> activeTab == 1);
        recomputeVisible();
    }

    /** 两个页签共用同一面板（与市政厅统一尺寸）；矮屏贴顶保证顶部工具栏可见。 */
    private void configureLayout() {
        this.imageWidth = PANEL_W;
        this.imageHeight = PANEL_H;
        this.leftPos = (this.width - this.imageWidth) / 2;
        this.topPos = Math.max((this.height - this.imageHeight) / 2, TOOLBAR_H + 4);
    }

    private void switchTab(int tabIndex) {
        if (tabIndex == activeTab) return;
        this.activeTab = tabIndex;
        // rebuildWidgets → clearWidgets + init()：重建尺寸/原点/控件
        this.rebuildWidgets();
    }

    private void buildOverviewTab() {
        int cx = leftPos + OVERVIEW_PAD;
        int cy = topPos + OVERVIEW_PAD;
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
        exchangeSearchInput = new SearchBox(font, x + 1, topPos + 10, PANEL_W - CHEST_W - 30,
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

    // ── 顶部工具栏 ──

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
        closeW = 18;
        closeH = 14;
        closeX = leftPos + imageWidth - closeW - 4;
        closeY = toolbarY + (TOOLBAR_H - closeH) / 2;
        helpW = 14;
        helpH = 14;
        helpX = closeX - helpW - 4;
        helpY = toolbarY + (TOOLBAR_H - helpH) / 2;
    }

    /** Exchange 右区分页控件几何（面板内、箱子纹理右侧留白区）。 */
    private void computePager() {
        int baseX = leftPos + CHEST_W + 14;
        prevW = 18;
        prevH = 12;
        prevY = topPos + 40;
        prevX = baseX;
        nextW = 18;
        nextH = 12;
        nextY = prevY;
        nextX = baseX + prevW + 6;
    }

    /** X 销毁格几何：Exchange 右列底部、与面板右下角留边距。 */
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

        if (showCloseButton) {
            int state = isInRect(mouseX, mouseY, closeX, closeY, closeW, closeH) ? 1 : 0;
            SkinRender.drawCloseButton(g, closeX, closeY, closeW, closeH, state);
        }
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
        if (showCloseButton && isInRect(mouseX, mouseY, closeX, closeY, closeW, closeH)) {
            onClose();
            return true;
        }
        return false;
    }

    /** Exchange 右区分页按钮点击（仅 Exchange 页可命中）。 */
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
        PacketDistributor.sendToServer(new WarehouseActionPacket(
                menu.containerId, action, "", null, 0));
        return true;
    }

    // ── 渲染 ──

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        renderCreatorFooter(g);
        renderFeedback(g);
        renderTooltip(g, mouseX, mouseY);
        renderTrashTooltip(g, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        renderToolbar(g, mouseX, mouseY);
        if (activeTab == 0) {
            g.fillGradient(leftPos, topPos, leftPos + PANEL_W, topPos + PANEL_H,
                    GLASS_TOP, GLASS_BOTTOM);
            drawGlowBorder(g, leftPos, topPos, PANEL_W, PANEL_H, MedievalColors.BORDER_GOLD);
            drawOverviewCapacity(g);
        } else {
            renderChest(g, mouseX, mouseY);
        }
    }

    /** Exchange 背景：面板左侧贴原版 6 行箱（仓库格+玩家背包），右侧放分页控件。 */
    private void renderChest(GuiGraphics g, int mouseX, int mouseY) {
        g.blit(CHEST_TEXTURE, leftPos, topPos, 0, 0, CHEST_W, CHEST_H);
        g.drawString(font, I18n.name("gui.wandscape.warehouse.title", "Colony Warehouse").getString(),
                leftPos + 8, topPos + 6, 0x404040);
        drawExchangeCapacity(g);
        renderPager(g, mouseX, mouseY);
        renderTrash(g, mouseX, mouseY);
    }

    /** 总览页：元素 7 行下方画容量读数（未设上限则隐藏）。 */
    private void drawOverviewCapacity(GuiGraphics g) {
        if (capacity <= 0) return;
        int x = leftPos + OVERVIEW_PAD;
        int y = topPos + OVERVIEW_PAD + 7 * 18 + 4;
        g.drawString(font, capacityText(), x, y, capacityColor());
    }

    /** Exchange 页：\"仓库\"标题右侧画容量读数（未设上限则隐藏）。 */
    private void drawExchangeCapacity(GuiGraphics g) {
        if (capacity <= 0) return;
        String title = I18n.name("gui.wandscape.warehouse.title", "Colony Warehouse").getString();
        int titleW = font.width(title);
        g.drawString(font, capacityText(), leftPos + 8 + titleW + 12, topPos + 6, capacityColor());
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
        g.drawString(font, pageText, x, topPos + 64, MedievalColors.TEXT_MUTED);
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
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        // 标题/工具条由皮肤绘制；不画 vanilla 标签。
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

    private void renderCreatorFooter(GuiGraphics g) {
        if (buildingCreator.isBlank() || activeTab != 0) return;
        String text = I18n.name("gui.wandscape.common.creator_label", "Creator").getString()
                + ": " + buildingCreator;
        g.drawString(font, text, leftPos + 16, topPos + PANEL_H - 24,
                MedievalColors.TEXT_DIM);
    }

    private void renderFeedback(GuiGraphics g) {
        if (feedback == null) return;
        if (System.currentTimeMillis() > feedbackExpireTick) {
            feedback = null;
            return;
        }
        int textW = font.width(feedback);
        int pad = 8;
        int w = textW + pad * 2;
        int h = font.lineHeight + 6;
        int x = (this.width - w) / 2;
        int y = Math.max(6, topPos - h - 3);

        g.fillGradient(x, y, x + w, y + h, 0xEE2A1C14, 0xEE120804);
        int borderCol = (feedbackColor & 0x00FFFFFF) | 0xDD000000;
        g.fill(x, y, x + w, y + 1, borderCol);
        g.fill(x, y + h - 1, x + w, y + h, borderCol);
        g.fill(x, y, x + 1, y + h, borderCol);
        g.fill(x + w - 1, y, x + w, y + h, borderCol);

        g.drawString(font, feedback, x + pad, y + (h - font.lineHeight) / 2, feedbackColor);
    }

    @Override
    protected void renderTooltip(GuiGraphics g, int x, int y) {
        // 仓库格走标准物品 tooltip（与 RS 一致，不附加数量行）。
        super.renderTooltip(g, x, y);
    }

    // ── 输入 ──

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 顶部工具栏（tabs/帮助/关闭）优先；tab 必须在 super 前拦截，
        // 因 AbstractContainerScreen.mouseClicked 走到槽位逻辑后无条件返回 true。
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

    /** 存储区矩形（原版 6 行箱格区，相对面板：8..170 × 18..126）。 */
    private boolean isOverStorageArea(double mouseX, double mouseY) {
        int x0 = leftPos + 8;
        int y0 = topPos + 18;
        return mouseX >= x0 && mouseX < x0 + 9 * 18
                && mouseY >= y0 && mouseY < y0 + 6 * 18;
    }

    /** 空白区存入：左键整叠、右键单个（服务端按光标操作，无需 itemId）。 */
    private void sendDepositAction(int button) {
        String action = button == 1
                ? WarehouseActionPacket.ACTION_CURSOR_DEPOSIT_ONE
                : WarehouseActionPacket.ACTION_CURSOR_DEPOSIT_ALL;
        PacketDistributor.sendToServer(new WarehouseActionPacket(
                menu.containerId, action, "", null, 0));
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (super.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        if (showHelpButton && helpDocumentPath != null
                && !(getFocused() instanceof EditBox box && box.canConsumeInput())
                && WandscapeClient.GUIDEBOOK_TOGGLE.matches(keyCode, scanCode)) {
            openHelpDocument();
            return true;
        }
        return false;
    }

    public void openHelpDocument() {
        if (helpDocumentPath != null && minecraft != null) {
            String content = com.wsteam.wandscape.foundation.ui.markdown.navigation.DocumentLoader
                    .loadMarkdown(helpDocumentPath);
            var screen = new com.wsteam.wandscape.foundation.ui.guidebook.GuidebookScreen(
                    this, content, helpDocumentPath);
            minecraft.setScreen(screen);
        }
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
        PacketDistributor.sendToServer(new WarehouseActionPacket(
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
                    PacketDistributor.sendToServer(new WarehouseActionPacket(menu.containerId,
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
                    PacketDistributor.sendToServer(new WarehouseActionPacket(menu.containerId,
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

    // ── 皮肤绘制工具 ──

    private static void drawGlowBorder(GuiGraphics g, int x, int y, int w, int h, int color) {
        int c0 = color;
        int c1 = (color & 0x00FFFFFF) | 0x66000000;
        g.fill(x, y, x + w, y + 1, c0);
        g.fill(x, y + h - 1, x + w, y + h, c0);
        g.fill(x, y, x + 1, y + h, c0);
        g.fill(x + w - 1, y, x + w, y + h, c0);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, c1);
        g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, c1);
        g.fill(x + 1, y + 1, x + 2, y + h - 1, c1);
        g.fill(x + w - 2, y + 1, x + w - 1, y + h - 1, c1);
    }

    private static void drawMinimalBox(GuiGraphics g, int x, int y, int w, int h,
                                       boolean active, boolean hovered) {
        if (active) {
            g.fillGradient(x, y, x + w, y + h, GLASS_BOX_TOP, GLASS_BOX_BOTTOM);
            drawGlowBorder(g, x, y, w, h, MedievalColors.BORDER_GOLD);
        } else if (hovered) {
            g.fillGradient(x, y, x + w, y + h,
                    MedievalColors.BUTTON_BG_HOVER, MedievalColors.PANEL_TITLE_BG);
            drawGlowBorder(g, x, y, w, h, MedievalColors.BORDER_GOLD_DARK);
        } else {
            g.fillGradient(x, y, x + w, y + h, 0x992A1E18, 0x991A0E08);
            g.fill(x, y, x + w, y + 1, MedievalColors.BORDER_GOLD_DARK);
            g.fill(x, y + h - 1, x + w, y + h, MedievalColors.BORDER_GOLD_DARK);
            g.fill(x, y, x + 1, y + h, MedievalColors.BORDER_GOLD_DARK);
            g.fill(x + w - 1, y, x + w, y + h, MedievalColors.BORDER_GOLD_DARK);
        }
    }

    private static boolean isInRect(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}