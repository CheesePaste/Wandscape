package com.wsteam.wandscape.warehouse.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.wsteam.wandscape.WandscapeClient;
import com.wsteam.wandscape.shared.data.ElementType;
import com.wsteam.wandscape.shared.ui.I18n;
import com.wsteam.wandscape.shared.ui.ReplayProtectedScreen;
import com.wsteam.wandscape.shared.ui.component.ElementPanel;
import com.wsteam.wandscape.shared.ui.component.HelpButton;
import com.wsteam.wandscape.shared.ui.component.MedievalButton;
import com.wsteam.wandscape.shared.ui.component.ScrollableList;
import com.wsteam.wandscape.shared.ui.skin.SkinRender;
import com.wsteam.wandscape.shared.ui.theme.MedievalColors;
import com.wsteam.wandscape.warehouse.WarehouseMenu;
import com.wsteam.wandscape.warehouse.WarehousePager;
import com.wsteam.wandscape.warehouse.WarehouseSlot;
import com.wsteam.wandscape.warehouse.network.WarehouseActionPacket;
import com.wsteam.wandscape.warehouse.network.WarehouseDataPacket;
import com.wsteam.wandscape.warehouse.network.WarehouseDataPacket.ItemEntry;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.network.PacketDistributor;

import static com.wsteam.wandscape.warehouse.WarehouseMenu.CONTENT_Y;
import static com.wsteam.wandscape.warehouse.WarehouseMenu.ELEMENT_PANEL_W;
import static com.wsteam.wandscape.warehouse.WarehouseMenu.GRID_COLS;
import static com.wsteam.wandscape.warehouse.WarehouseMenu.GRID_ROWS;
import static com.wsteam.wandscape.warehouse.WarehouseMenu.GRID_X;
import static com.wsteam.wandscape.warehouse.WarehouseMenu.HEADER_H;
import static com.wsteam.wandscape.warehouse.WarehouseMenu.PANEL_H;
import static com.wsteam.wandscape.warehouse.WarehouseMenu.PANEL_W;
import static com.wsteam.wandscape.warehouse.WarehouseMenu.PLAYER_INV_Y;
import static com.wsteam.wandscape.warehouse.WarehouseMenu.SLOT;
import static com.wsteam.wandscape.warehouse.WarehouseMenu.TAB_H;

/**
 * Warehouse GUI with two tabs — Overview (element display + searchable item list)
 * and Exchange (warehouse slot grid ↔ player inventory).
 *
 * <p>Extends {@link AbstractContainerScreen} so the player inventory slots are
 * real vanilla slots: all vanilla shortcuts (1-9 hotbar, Q drop, shift-click,
 * drag-split) and inventory-sorting mods work out of the box. Warehouse items are
 * {@link WarehouseSlot}s — read-only, rendered chest-style with a count badge in
 * the bottom-right corner; their clicks go through {@link WarehouseActionPacket}.
 */
public class WarehouseScreen extends AbstractContainerScreen<WarehouseMenu> implements ReplayProtectedScreen {

    /** Bottom space reserved for the creator footer (content must end above it). */
    private static final int FOOTER_RESERVE = 24 + 4;

    private static final WarehousePager PAGER = new WarehousePager(54);
    private static final Comparator<ItemEntry> BY_ID = Comparator.comparing(ItemEntry::itemId);

    private BlockPos buildingPos = BlockPos.ZERO;
    private UUID colonyId = new UUID(0, 0);
    private int activeTab;
    private int page;
    private int totalPages = 1;
    private String query = "";

    // Data
    private List<ItemEntry> allItems = new ArrayList<>();
    private List<ItemEntry> visibleEntries = new ArrayList<>();
    private List<ItemStack> visibleStacks = new ArrayList<>();
    private Map<ElementType, Long> elements = new LinkedHashMap<>();

    // ── Tab 0: Overview widgets ──
    private EditBox searchInput;
    private ElementPanel elementPanel;
    private ScrollableList<ItemEntry> overviewList;

    // ── Tab 1: Exchange widgets ──
    private EditBox exchangeSearchInput;
    private MedievalButton prevPageBtn;
    private MedievalButton nextPageBtn;

    // ── Medieval skin state ──
    private Component titleBarText;
    private String buildingCreator = "";
    private boolean showCloseButton = true;
    private int closeBtnX, closeBtnY;
    private final int closeBtnW = 18, closeBtnH = 14;
    private boolean showHelpButton = true;
    private String helpDocumentPath = "warehouse_guide";
    private HelpButton helpButton;
    private Component feedback;
    private int feedbackColor;
    private long feedbackExpireTick;
    private static final long FEEDBACK_DURATION_MS = 3000L;

    private static final int GLASS_TOP = 0xBB483828;
    private static final int GLASS_BOTTOM = 0xBB1E1410;
    private static final int GLASS_BOX_TOP = 0xBB423020;
    private static final int GLASS_BOX_BOTTOM = 0xBB1C1008;

    public WarehouseScreen(WarehouseMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = PANEL_W;
        this.imageHeight = PANEL_H;
        this.titleBarText = I18n.name("gui.wandscape.warehouse.title", "Colony Warehouse");
    }

    // ── Data updates from server ──

    public void updateItems(WarehouseDataPacket packet) {
        this.buildingPos = packet.buildingPos();
        this.colonyId = packet.colonyId();
        // Refresh packets may carry a blank creator — keep the one from the initial open.
        if (packet.creator() != null && !packet.creator().isBlank()) {
            setCreator(packet.creator());
        }
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

    /** Show a transient message at the top-center of the screen for ~3s. */
    public void showFeedback(Component message, int color) {
        this.feedback = message;
        this.feedbackColor = color;
        this.feedbackExpireTick = System.currentTimeMillis() + FEEDBACK_DURATION_MS;
    }

    // ── Init ──

    @Override
    protected void init() {
        super.init();
        this.closeBtnX = leftPos + PANEL_W - closeBtnW - 6;
        this.closeBtnY = topPos + (HEADER_H - closeBtnH) / 2;
        if (showHelpButton && helpDocumentPath != null) {
            int helpW = 14;
            int helpH = 14;
            int helpX = showCloseButton ? closeBtnX - helpW - 4 : leftPos + PANEL_W - helpW - 6;
            int helpY = topPos + (HEADER_H - helpH) / 2;
            helpButton = new HelpButton(helpX, helpY, helpW, helpH, this::openHelpDocument);
            addRenderableWidget(helpButton);
        }

        buildOverviewTab();
        buildExchangeTab();
        showTab(activeTab);
        menu.bindSlots(this::getEntryStack, () -> activeTab == 1);
        recomputeVisible();
    }

    private void buildOverviewTab() {
        int contentX = leftPos + 8;
        int tabY = topPos + CONTENT_Y;
        int tabH = topPos + PANEL_H - tabY - 6 - FOOTER_RESERVE;

        elementPanel = new ElementPanel(contentX, tabY, ELEMENT_PANEL_W);
        elementPanel.setElements(elements);

        int rightX = contentX + ELEMENT_PANEL_W + 6;
        int rightW = PANEL_W - 16 - ELEMENT_PANEL_W - 6;
        int searchH = font.lineHeight + 6;

        searchInput = new EditBox(font, rightX + 1, tabY + 2, rightW - 2, font.lineHeight,
                I18n.name("gui.wandscape.warehouse.search", "Search items..."));
        searchInput.setBordered(false);
        searchInput.setTextColor(MedievalColors.TEXT_WARM_WHITE);
        searchInput.setTextColorUneditable(MedievalColors.TEXT_MUTED);
        searchInput.setHint(I18n.name("gui.wandscape.warehouse.search", "Search items..."));
        searchInput.setCanLoseFocus(true);
        searchInput.setResponder(q -> overviewList.setItems(filterItems(q)));

        int listY = tabY + searchH + 4;
        int listH = tabH - searchH - 4;
        overviewList = buildItemList(rightX, listY, rightW, listH);
        overviewList.setItems(filterItems(""));
    }

    private void buildExchangeTab() {
        int gridTop = topPos + CONTENT_Y;
        int gridBottom = gridTop + GRID_ROWS * SLOT;

        int searchY = gridBottom + 6;
        int searchW = 130;

        exchangeSearchInput = new EditBox(font, leftPos + GRID_X, searchY + 1, searchW, font.lineHeight,
                I18n.name("gui.wandscape.warehouse.search", "Search items..."));
        exchangeSearchInput.setBordered(false);
        exchangeSearchInput.setTextColor(MedievalColors.TEXT_WARM_WHITE);
        exchangeSearchInput.setTextColorUneditable(MedievalColors.TEXT_MUTED);
        exchangeSearchInput.setHint(I18n.name("gui.wandscape.warehouse.search", "Search items..."));
        exchangeSearchInput.setCanLoseFocus(true);
        exchangeSearchInput.setResponder(q -> {
            query = q;
            page = 0;
            recomputeVisible();
        });

        int btnW = 16;
        int btnH = 12;
        int btnY = searchY + (14 - btnH) / 2;
        prevPageBtn = new MedievalButton(leftPos + GRID_X + searchW + 6, btnY, btnW, btnH,
                Component.literal("◀"), () -> {
            page--;
            recomputeVisible();
        });
        nextPageBtn = new MedievalButton(prevPageBtn.getX() + btnW + 4, btnY, btnW, btnH,
                Component.literal("▶"), () -> {
            page++;
            recomputeVisible();
        });
    }

    /** Build a scrollable item list with themed row rendering (Overview tab). */
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

    // ── Tab switching ──

    private void showTab(int tabIndex) {
        this.activeTab = tabIndex;
        if (elementPanel != null) removeWidget(elementPanel);
        if (searchInput != null) removeWidget(searchInput);
        if (overviewList != null) removeWidget(overviewList);
        if (exchangeSearchInput != null) removeWidget(exchangeSearchInput);
        if (prevPageBtn != null) removeWidget(prevPageBtn);
        if (nextPageBtn != null) removeWidget(nextPageBtn);

        if (tabIndex == 0) {
            addRenderableWidget(elementPanel);
            addRenderableWidget(searchInput);
            addRenderableWidget(overviewList);
        } else {
            addRenderableWidget(elementPanel);
            addRenderableWidget(exchangeSearchInput);
            addRenderableWidget(prevPageBtn);
            addRenderableWidget(nextPageBtn);
        }
    }

    // ── Display data for the read-only warehouse slots ──

    private ItemStack getEntryStack(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= visibleStacks.size()) {
            return ItemStack.EMPTY;
        }
        return visibleStacks.get(slotIndex);
    }

    private void recomputeVisible() {
        var result = PAGER.page(allItems, this::matchesQuery, BY_ID, page);
        this.page = result.page();
        this.totalPages = result.totalPages();
        this.visibleEntries = result.entries();
        this.visibleStacks = new ArrayList<>(visibleEntries.size());
        for (ItemEntry entry : visibleEntries) {
            visibleStacks.add(toStack(entry));
        }
        if (prevPageBtn != null) prevPageBtn.active = result.hasPrev();
        if (nextPageBtn != null) nextPageBtn.active = result.hasNext();
    }

    private boolean matchesQuery(ItemEntry entry) {
        if (query == null || query.isEmpty()) return true;
        return entry.itemId().toLowerCase().contains(query.toLowerCase());
    }

    private List<ItemEntry> filterItems(String query) {
        String lower = query == null ? "" : query.toLowerCase();
        List<ItemEntry> result = new ArrayList<>();
        for (ItemEntry item : allItems) {
            if (lower.isEmpty() || item.itemId().toLowerCase().contains(lower)) {
                result.add(item);
            }
        }
        return result;
    }

    /** Build a display stack for an entry (count capped at int max for the icon). */
    private static ItemStack toStack(ItemEntry entry) {
        var registryItem = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(entry.itemId()));
        if (registryItem == null || registryItem == Items.AIR) return ItemStack.EMPTY;
        int count = (int) Math.min(Math.max(entry.count(), 1), Integer.MAX_VALUE);
        ItemStack stack = new ItemStack(registryItem, count);
        if (entry.nbt() != null && !entry.nbt().isEmpty()) {
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(entry.nbt().copy()));
        }
        return stack;
    }

    // ── Render ──

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        renderCreatorFooter(g);
        renderFeedback(g);
        renderTooltip(g, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        // Glass panel + glow border (MedievalScreen skin)
        g.fillGradient(leftPos, topPos, leftPos + PANEL_W, topPos + PANEL_H,
                GLASS_TOP, GLASS_BOTTOM);
        drawGlowBorder(g, leftPos, topPos, PANEL_W, PANEL_H, MedievalColors.BORDER_GOLD);

        renderMinimalHeader(g);
        if (showCloseButton) {
            renderCloseButton(g, mouseX, mouseY);
        }
        renderTabs(g, mouseX, mouseY);
        renderDecorations(g);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        // Header/tabs are drawn by the skin; skip the vanilla title labels.
    }

    @Override
    protected void renderSlot(GuiGraphics g, Slot slot) {
        if (slot instanceof WarehouseSlot) {
            renderWarehouseSlot(g, slot);
        } else {
            super.renderSlot(g, slot);
        }
    }

    @Override
    protected void renderSlotHighlight(GuiGraphics g, Slot slot, int mouseX, int mouseY, float partialTick) {
        // Warehouse slots draw their own themed hover border; skip the vanilla white box.
        if (slot instanceof WarehouseSlot) return;
        super.renderSlotHighlight(g, slot, mouseX, mouseY, partialTick);
    }

    private void renderWarehouseSlot(GuiGraphics g, Slot slot) {
        int x = slot.x;
        int y = slot.y;
        boolean hovered = slot == hoveredSlot;
        int bg = hovered ? MedievalColors.BUTTON_BG_HOVER : MedievalColors.PARCHMENT_DEEPEST;
        int border = hovered ? MedievalColors.BORDER_GOLD : MedievalColors.BORDER_GOLD_DARK;

        g.fill(x, y, x + 16, y + 16, bg);
        g.fill(x, y, x + 16, y + 1, border);
        g.fill(x, y + 15, x + 16, y + 16, border);
        g.fill(x, y, x + 1, y + 16, border);
        g.fill(x + 15, y, x + 16, y + 16, border);

        ItemStack stack = slot.getItem();
        if (stack.isEmpty()) return;

        g.renderItem(stack, x, y, x + y * imageWidth);
        long count = slot.index < visibleEntries.size() ? visibleEntries.get(slot.index).count() : 0;
        if (count > 1) {
            String text = WarehousePager.formatCount(count);
            g.drawString(font, text, x + 16 - font.width(text), y + 10, 0xFFFFFF);
        }
    }

    private void renderTabs(GuiGraphics g, int mouseX, int mouseY) {
        String[] tabs = {
                I18n.name("gui.wandscape.warehouse.overview", "Overview").getString(),
                I18n.name("gui.wandscape.warehouse.exchange", "Exchange").getString()
        };
        int ty = topPos + HEADER_H + 2;
        int tx = leftPos + 8;
        int padH = 10;

        for (int i = 0; i < tabs.length; i++) {
            int tw = font.width(tabs[i]) + padH * 2;
            boolean active = i == activeTab;
            boolean hovered = !active && isInRect(mouseX, mouseY, tx, ty, tw, TAB_H);

            drawMinimalBox(g, tx, ty, tw, TAB_H, active, hovered);

            int textColor = active ? MedievalColors.BORDER_GOLD
                    : hovered ? MedievalColors.TEXT_WARM_WHITE
                    : MedievalColors.TEXT_MUTED;
            g.drawString(font, tabs[i],
                    tx + (tw - font.width(tabs[i])) / 2,
                    ty + (TAB_H - font.lineHeight) / 2,
                    textColor);
            tx += tw + 4;
        }
    }

    private void renderDecorations(GuiGraphics g) {
        if (activeTab == 0 && searchInput != null) {
            drawInsetField(g, searchInput.getX() - 1, searchInput.getY() - 2,
                    searchInput.getWidth() + 2, searchInput.getHeight() + 4);
            return;
        }
        if (activeTab != 1 || exchangeSearchInput == null) return;

        drawInsetField(g, exchangeSearchInput.getX() - 1, exchangeSearchInput.getY() - 2,
                exchangeSearchInput.getWidth() + 2, exchangeSearchInput.getHeight() + 4);

        int invY = topPos + PLAYER_INV_Y;
        g.fill(leftPos + 8, invY - 2, leftPos + PANEL_W - 8, invY - 1,
                MedievalColors.BORDER_GOLD_DARK);
        g.drawString(font, I18n.name("gui.wandscape.warehouse.player_inventory", "Player Inventory"),
                leftPos + 8, invY, MedievalColors.TEXT_MUTED);

        String pageText = I18n.name("gui.wandscape.warehouse.page", "%s / %s",
                page + 1, totalPages).getString();
        g.drawString(font, pageText, nextPageBtn.getX() + nextPageBtn.getWidth() + 8,
                exchangeSearchInput.getY() + 1, MedievalColors.TEXT_MUTED);

        g.drawString(font,
                I18n.name("gui.wandscape.warehouse.exchange_hint",
                        "L-click: take | R-click: half | Shift: quick move"),
                leftPos + 8, topPos + PLAYER_INV_Y + 4 * SLOT + 4, MedievalColors.TEXT_MUTED);
    }

    @Override
    protected void renderTooltip(GuiGraphics g, int x, int y) {
        if (menu.getCarried().isEmpty() && hoveredSlot != null && hoveredSlot.hasItem()) {
            if (hoveredSlot instanceof WarehouseSlot ws && ws.index < visibleEntries.size()) {
                long count = visibleEntries.get(ws.index).count();
                if (count > 0) {
                    ItemStack stack = hoveredSlot.getItem();
                    List<Component> lines = new ArrayList<>(getTooltipFromContainerItem(stack));
                    lines.add(Component.literal(WarehousePager.formatCount(count))
                            .withStyle(ChatFormatting.GRAY));
                    g.renderTooltip(font, lines, stack.getTooltipImage(), stack, x, y);
                    return;
                }
            }
        }
        super.renderTooltip(g, x, y);
    }

    // ── Input ──

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Warehouse slot clicks are custom (AE2-style) — intercept before vanilla.
        if (activeTab == 1 && (button == 0 || button == 1)) {
            Slot slot = findWarehouseSlot(mouseX, mouseY);
            if (slot != null) {
                handleWarehouseSlotClick((WarehouseSlot) slot, button);
                return true;
            }
        }
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button == 0) {
            int tabIdx = getTabAt(mouseX, mouseY);
            if (tabIdx >= 0 && tabIdx != activeTab) {
                showTab(tabIdx);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (super.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        if (showHelpButton && helpDocumentPath != null
                && !(getFocused() instanceof EditBox box && box.canConsumeInput())
                && WandscapeClient.GUIDE_TOGGLE.matches(keyCode, scanCode)) {
            openHelpDocument();
            return true;
        }
        return false;
    }

    public void openHelpDocument() {
        if (helpDocumentPath != null && minecraft != null) {
            String content = com.wsteam.wandscape.shared.ui.markdown.navigation.DocumentLoader
                    .loadMarkdown(helpDocumentPath);
            var screen = new com.wsteam.wandscape.shared.ui.guide.GuideTestScreen(
                    this, content, helpDocumentPath);
            minecraft.setScreen(screen);
        }
    }

    private Slot findWarehouseSlot(double mouseX, double mouseY) {
        for (Slot slot : menu.slots) {
            if (slot instanceof WarehouseSlot && slot.isActive()
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
        PacketDistributor.sendToServer(new WarehouseActionPacket(
                menu.containerId, action, entry.itemId(), entry.nbt()));
    }

    private int getTabAt(double mouseX, double mouseY) {
        String[] tabs = {
                I18n.name("gui.wandscape.warehouse.overview", "Overview").getString(),
                I18n.name("gui.wandscape.warehouse.exchange", "Exchange").getString()
        };
        int ty = topPos + HEADER_H + 2;
        int tx = leftPos + 8;
        int padH = 10;
        for (int i = 0; i < tabs.length; i++) {
            int tw = font.width(tabs[i]) + padH * 2;
            if (isInRect(mouseX, mouseY, tx, ty, tw, TAB_H)) {
                return i;
            }
            tx += tw + 4;
        }
        return -1;
    }

    // ── Medieval skin helpers ──

    private void renderMinimalHeader(GuiGraphics g) {
        int hx = leftPos + 1;
        int hy = topPos + 1;
        int hw = PANEL_W - 2;

        g.fillGradient(hx, hy, hx + hw, hy + HEADER_H, 0xFF502870, 0xFF1A0830);

        int sepY = hy + HEADER_H;
        int sc = MedievalColors.BORDER_GOLD;
        g.fill(hx, sepY, hx + hw, sepY + 1, sc);
        g.fill(hx, sepY + 1, hx + hw, sepY + 2, (sc & 0x00FFFFFF) | 0x66000000);

        g.fillGradient(hx, hy, hx + 3, hy + HEADER_H, 0xFFD4A840, 0xFF6A4020);

        if (titleBarText != null) {
            g.drawString(font, titleBarText, hx + 10,
                    hy + (HEADER_H - font.lineHeight) / 2,
                    MedievalColors.TEXT_WARM_WHITE);
        }
    }

    private void renderCloseButton(GuiGraphics g, int mouseX, int mouseY) {
        int state = isInRect(mouseX, mouseY, closeBtnX, closeBtnY, closeBtnW, closeBtnH) ? 1 : 0;
        SkinRender.drawCloseButton(g, closeBtnX, closeBtnY, closeBtnW, closeBtnH, state);
    }

    private void renderCreatorFooter(GuiGraphics g) {
        if (buildingCreator.isBlank()) return;
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

    private static void drawInsetField(GuiGraphics g, int x, int y, int w, int h) {
        g.fillGradient(x, y, x + w, y + h, 0x44000000, 0x33000000);
        g.fill(x, y, x + w, y + 1, 0x55000000);
        g.fill(x, y, x + 1, y + h, 0x55000000);
        g.fill(x, y + h - 1, x + w, y + h, 0x22FFFFFF);
        g.fill(x + w - 1, y, x + w, y + h, 0x22FFFFFF);
    }

    private static boolean isInRect(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
