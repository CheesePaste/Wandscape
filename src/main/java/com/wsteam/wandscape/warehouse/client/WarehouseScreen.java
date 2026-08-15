package com.wsteam.wandscape.warehouse.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.wsteam.wandscape.shared.data.ElementType;
import com.wsteam.wandscape.shared.ui.I18n;
import com.wsteam.wandscape.shared.ui.component.ElementPanel;
import com.wsteam.wandscape.shared.ui.component.MedievalScreen;
import com.wsteam.wandscape.shared.ui.component.ScrollableList;
import com.wsteam.wandscape.shared.ui.theme.MedievalColors;
import com.wsteam.wandscape.warehouse.network.WarehouseActionPacket;
import com.wsteam.wandscape.warehouse.network.WarehouseDataPacket;
import com.wsteam.wandscape.warehouse.network.WarehouseDataPacket.ItemEntry;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Warehouse GUI with two tabs — Overview (element display + searchable item list)
 * and Exchange (warehouse ↔ player inventory).
 * Uses {@link MedievalScreen} MINIMAL theme with {@link MedievalColors}.
 */
public class WarehouseScreen extends MedievalScreen {

    private static final int PW = 380;
    private static final int PH = 250;
    private static final int TAB_H = 18;
    private static final int MAX_QTY = 64;
    private static final int SLOT_SIZE = 18;
    private static final int SCROLLBAR_W = 6;
    /** Bottom space reserved for the creator footer (content must end above it). */
    private static final int FOOTER_RESERVE = CREATOR_FOOTER_H + 4;

    private BlockPos buildingPos = BlockPos.ZERO;
    private UUID colonyId = new UUID(0, 0);
    private int activeTab;

    // Data
    private List<ItemEntry> allItems = new ArrayList<>();
    private List<ItemEntry> filteredItems = new ArrayList<>();
    private List<ItemEntry> exchangeFilteredItems = new ArrayList<>();
    private Map<ElementType, Long> elements = new LinkedHashMap<>();

    // ── Tab 0: Overview widgets ──
    private EditBox searchInput;
    private ElementPanel elementPanel;
    private ScrollableList<ItemEntry> overviewList;

    // ── Tab 1: Exchange widgets ──
    private ScrollableList<ItemEntry> exchangeList;
    private EditBox exchangeSearchInput;

    public WarehouseScreen() {
        super(Component.literal("Colony Warehouse"), PW, PH);
        setTitleBar(I18n.name("gui.wandscape.warehouse.title", "Colony Warehouse"));
        this.showCloseButton = true;
        this.showHelpButton = true;
        this.helpDocumentPath = "warehouse_guide";
        this.headerHeight = 22;
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
        this.allItems.sort(Comparator.comparing(ItemEntry::itemId));
        this.elements = new LinkedHashMap<>(packet.elementMap());
        applyFilter(searchInput != null ? searchInput.getValue() : "");
        applyExchangeFilter(exchangeSearchInput != null ? exchangeSearchInput.getValue() : "");
    }

    // ── Init ──

    @Override
    protected void init() {
        super.init();

        int contentX = leftPos + 8;
        int tabContentY = topPos + headerHeight + 2 + TAB_H + 5;

        buildOverviewTab(contentX, tabContentY);
        buildExchangeTab(contentX, tabContentY);
        showTab(activeTab);
    }

    // ── Tab 0: Overview (read-only) ──

    private void buildOverviewTab(int contentX, int tabY) {
        int tabH = topPos + PH - tabY - 6 - FOOTER_RESERVE;
        int elementPanelW = 130;

        elementPanel = new ElementPanel(contentX, tabY, elementPanelW);
        elementPanel.setElements(elements);

        int rightX = contentX + elementPanelW + 6;
        int rightW = PW - 16 - elementPanelW - 6;
        int searchH = font.lineHeight + 6;

        searchInput = new EditBox(font, rightX + 1, tabY + 2, rightW - 2, font.lineHeight,
                I18n.name("gui.wandscape.warehouse.search", "Search items..."));
        searchInput.setBordered(false);
        searchInput.setTextColor(MedievalColors.TEXT_WARM_WHITE);
        searchInput.setTextColorUneditable(MedievalColors.TEXT_MUTED);
        searchInput.setHint(I18n.name("gui.wandscape.warehouse.search", "Search items..."));
        searchInput.setCanLoseFocus(true);
        searchInput.setResponder(this::applyFilter);

        int listY = tabY + searchH + 4;
        int listH = tabH - searchH - 4;
        overviewList = buildItemList(rightX, listY, rightW, listH, false);
        overviewList.setItems(filteredItems);
    }

    // ── Tab 1: Exchange (warehouse ↔ player inventory) ──

    private void buildExchangeTab(int contentX, int tabY) {
        int tabH = topPos + PH - tabY - 6 - FOOTER_RESERVE;
        int rightW = PW - 16;

        int invSectionH = 10 + SLOT_SIZE * 4;
        int bottomY = tabY + tabH - invSectionH;

        int listH = bottomY - tabY - 2;
        exchangeList = buildItemList(contentX, tabY, rightW, listH, true);
        exchangeList.setItems(exchangeFilteredItems);
        exchangeList.setOnRowClick((entry, index, button) -> {
            if (entry.count() <= 0) return;
            int take = button == 1
                    ? (int) Math.min(entry.count(), MAX_QTY)
                    : 1;
            PacketDistributor.sendToServer(new WarehouseActionPacket(
                    buildingPos, "withdraw", entry.itemId(), entry.nbt(), take, -1));
        });

        int invRight = leftPos + 8 + 9 * SLOT_SIZE;
        int sbX = invRight + 6;
        int sbW = (leftPos + PW - 8) - sbX;
        int sbY = getInventoryY();
        exchangeSearchInput = new EditBox(font, sbX + 1, sbY + 1, sbW - 2, font.lineHeight,
                I18n.name("gui.wandscape.warehouse.search", "Search items..."));
        exchangeSearchInput.setBordered(false);
        exchangeSearchInput.setTextColor(MedievalColors.TEXT_WARM_WHITE);
        exchangeSearchInput.setTextColorUneditable(MedievalColors.TEXT_MUTED);
        exchangeSearchInput.setHint(I18n.name("gui.wandscape.warehouse.search", "Search items..."));
        exchangeSearchInput.setCanLoseFocus(true);
        exchangeSearchInput.setResponder(this::applyExchangeFilter);
    }

    /** Build a scrollable item list with themed row rendering. */
    private ScrollableList<ItemEntry> buildItemList(int x, int y, int w, int h, boolean showHint) {
        return new ScrollableList<>(x, y, w, h, 20) {
            @Override
            protected void renderRow(GuiGraphics g, ItemEntry item, int rx, int ry, int index,
                                     boolean selected, boolean hovered) {
                var registryItem = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(item.itemId()));
                Component name;
                if (registryItem != null && registryItem != Items.AIR) {
                    ItemStack icon = new ItemStack(registryItem);
                    if (item.nbt() != null && !item.nbt().isEmpty()) {
                        icon.set(DataComponents.CUSTOM_DATA,
                                net.minecraft.world.item.component.CustomData.of(item.nbt().copy()));
                    }
                    g.renderItem(icon, rx, ry + 2);
                    name = icon.getHoverName();
                } else {
                    name = Component.literal(item.itemId());
                }
                int textColor = selected ? MedievalColors.BORDER_GOLD
                        : hovered ? MedievalColors.TEXT_WARM_WHITE
                        : MedievalColors.TEXT_MUTED;
                g.drawString(Minecraft.getInstance().font, name, rx + 20, ry + 3, textColor);

                String count = formatCount(item.count());
                int countW = Minecraft.getInstance().font.width(count);
                g.drawString(Minecraft.getInstance().font, count,
                        rx + getWidth() - SCROLLBAR_W - countW - 8, ry + 3,
                        MedievalColors.TEXT_MUTED);

                if (showHint && hovered) {
                    String hint = I18n.name("gui.wandscape.warehouse.withdraw_hint",
                            "L-click: withdraw 1 | R-click: withdraw %s", MAX_QTY).getString();
                    g.drawString(Minecraft.getInstance().font, hint, rx + 20, ry + 14,
                            MedievalColors.TEXT_MUTED);
                }
            }
        };
    }

    private int getInventoryY() {
        return topPos + PH - 10 - SLOT_SIZE * 4 - 6 - FOOTER_RESERVE;
    }

    // ── Tab switching ──

    private void showTab(int tabIndex) {
        if (elementPanel != null) removeWidget(elementPanel);
        if (searchInput != null) removeWidget(searchInput);
        if (overviewList != null) removeWidget(overviewList);
        if (exchangeList != null) removeWidget(exchangeList);
        if (exchangeSearchInput != null) removeWidget(exchangeSearchInput);

        if (tabIndex == 0) {
            if (elementPanel != null) addRenderableWidget(elementPanel);
            if (searchInput != null) addRenderableWidget(searchInput);
            if (overviewList != null) addRenderableWidget(overviewList);
        } else {
            if (exchangeList != null) addRenderableWidget(exchangeList);
            if (exchangeSearchInput != null) addRenderableWidget(exchangeSearchInput);
        }
    }

    // ── Render ──

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        renderMinimalHeader(g);
        renderCloseButton(g, mouseX, mouseY);
        renderTabs(g, mouseX, mouseY);
        renderDecorations(g);

        for (Renderable r : this.renderables) {
            r.render(g, mouseX, mouseY, partialTick);
        }

        if (activeTab == 1) {
            renderPlayerInventory(g, mouseX, mouseY);
        }

        renderCreatorFooter(g);
    }

    // ── Tabs ──

    private void renderTabs(GuiGraphics g, int mouseX, int mouseY) {
        String[] tabs = {
                I18n.name("gui.wandscape.warehouse.overview", "Overview").getString(),
                I18n.name("gui.wandscape.warehouse.exchange", "Exchange").getString()
        };
        int ty = topPos + headerHeight + 2;
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

    // ── Decorations ──

    private void renderDecorations(GuiGraphics g) {
        if (activeTab == 0 && searchInput != null) {
            drawInsetField(g, searchInput.getX() - 1, searchInput.getY() - 2,
                    searchInput.getWidth() + 2, searchInput.getHeight() + 4);
        } else if (activeTab == 1 && exchangeSearchInput != null) {
            drawInsetField(g, exchangeSearchInput.getX() - 1, exchangeSearchInput.getY() - 2,
                    exchangeSearchInput.getWidth() + 2, exchangeSearchInput.getHeight() + 4);

            int invY = getInventoryY();
            g.fill(leftPos + 8, invY - 2, leftPos + PW - 8, invY - 1,
                    MedievalColors.BORDER_GOLD_DARK);
            g.drawString(font, I18n.name("gui.wandscape.warehouse.player_inventory", "Player Inventory"),
                    leftPos + 8, invY, MedievalColors.TEXT_MUTED);
        }
    }

    // ── Player inventory (Exchange tab) ──

    private void renderPlayerInventory(GuiGraphics g, int mouseX, int mouseY) {
        int invY = getInventoryY() + 10;
        int invX = leftPos + 8;

        var player = Minecraft.getInstance().player;
        if (player == null) return;
        var inventory = player.getInventory();

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int slot = 9 + row * 9 + col;
                int sx = invX + col * SLOT_SIZE;
                int sy = invY + row * SLOT_SIZE;
                renderSlot(g, inventory.getItem(slot), sx, sy,
                        isInRect(mouseX, mouseY, sx, sy, SLOT_SIZE, SLOT_SIZE));
            }
        }

        for (int col = 0; col < 9; col++) {
            int sx = invX + col * SLOT_SIZE;
            int sy = invY + 3 * SLOT_SIZE;
            renderSlot(g, inventory.getItem(col), sx, sy,
                    isInRect(mouseX, mouseY, sx, sy, SLOT_SIZE, SLOT_SIZE));
        }

        renderInventoryTooltip(g, mouseX, mouseY, invX, invY);
    }

    private void renderSlot(GuiGraphics g, ItemStack stack, int x, int y, boolean hovered) {
        int bg = hovered ? MedievalColors.BUTTON_BG_HOVER : MedievalColors.PARCHMENT_DEEPEST;
        int border = hovered ? MedievalColors.BORDER_GOLD : MedievalColors.BORDER_GOLD_DARK;

        g.fill(x, y, x + SLOT_SIZE, y + SLOT_SIZE, bg);
        g.fill(x, y, x + SLOT_SIZE, y + 1, border);
        g.fill(x, y + SLOT_SIZE - 1, x + SLOT_SIZE, y + SLOT_SIZE, border);
        g.fill(x, y, x + 1, y + SLOT_SIZE, border);
        g.fill(x + SLOT_SIZE - 1, y, x + SLOT_SIZE, y + SLOT_SIZE, border);

        if (!stack.isEmpty()) {
            g.renderItem(stack, x + 1, y + 1);
            g.renderItemDecorations(Minecraft.getInstance().font, stack, x + 1, y + 1);
        }
    }

    private void renderInventoryTooltip(GuiGraphics g, int mouseX, int mouseY, int invX, int invY) {
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        var inventory = player.getInventory();

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int slot = 9 + row * 9 + col;
                int sx = invX + col * SLOT_SIZE;
                int sy = invY + row * SLOT_SIZE;
                if (isInRect(mouseX, mouseY, sx, sy, SLOT_SIZE, SLOT_SIZE)) {
                    showHoverText(g, inventory.getItem(slot), mouseX, mouseY);
                    return;
                }
            }
        }
        for (int col = 0; col < 9; col++) {
            int sx = invX + col * SLOT_SIZE;
            int sy = invY + 3 * SLOT_SIZE;
            if (isInRect(mouseX, mouseY, sx, sy, SLOT_SIZE, SLOT_SIZE)) {
                showHoverText(g, inventory.getItem(col), mouseX, mouseY);
                return;
            }
        }
    }

    private void showHoverText(GuiGraphics g, ItemStack stack, int mx, int my) {
        if (!stack.isEmpty()) {
            g.drawString(Minecraft.getInstance().font, stack.getHoverName().getString(),
                    mx + 8, my - 12, MedievalColors.TEXT_WARM_WHITE);
        }
    }

    // ── Input ──

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Base class handles close button + widget clicks
        if (super.mouseClicked(mouseX, mouseY, button)) return true;

        // Tab clicks
        if (button == 0) {
            int tabIdx = getTabAt(mouseX, mouseY);
            if (tabIdx >= 0 && tabIdx != activeTab) {
                activeTab = tabIdx;
                showTab(activeTab);
                return true;
            }
        }

        // Player inventory (exchange tab)
        if (activeTab == 1 && isOverInventoryArea(mouseX, mouseY)) {
            if (handleInventoryClick((int) mouseX, (int) mouseY, button)) return true;
        }

        return false;
    }

    private int getTabAt(double mouseX, double mouseY) {
        String[] tabs = {
                I18n.name("gui.wandscape.warehouse.overview", "Overview").getString(),
                I18n.name("gui.wandscape.warehouse.exchange", "Exchange").getString()
        };
        int ty = topPos + headerHeight + 2;
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

    private boolean isOverInventoryArea(double mouseX, double mouseY) {
        int invY = getInventoryY() + 10;
        int invX = leftPos + 8;
        return mouseX >= invX && mouseX < invX + 9 * SLOT_SIZE
                && mouseY >= invY && mouseY < invY + 4 * SLOT_SIZE;
    }

    private boolean handleInventoryClick(int mouseX, int mouseY, int button) {
        int invY = getInventoryY() + 10;
        int invX = leftPos + 8;

        var player = Minecraft.getInstance().player;
        if (player == null) return false;

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int slot = 9 + row * 9 + col;
                int sx = invX + col * SLOT_SIZE;
                int sy = invY + row * SLOT_SIZE;
                if (isInRect(mouseX, mouseY, sx, sy, SLOT_SIZE, SLOT_SIZE)) {
                    return depositFromSlot(slot, button);
                }
            }
        }
        for (int col = 0; col < 9; col++) {
            int slot = col;
            int sx = invX + col * SLOT_SIZE;
            int sy = invY + 3 * SLOT_SIZE;
            if (isInRect(mouseX, mouseY, sx, sy, SLOT_SIZE, SLOT_SIZE)) {
                return depositFromSlot(slot, button);
            }
        }
        return false;
    }

    private boolean depositFromSlot(int slot, int button) {
        var player = Minecraft.getInstance().player;
        if (player == null) return false;

        ItemStack stack = player.getInventory().getItem(slot);
        if (stack.isEmpty()) return false;

        int qty = button == 1 ? stack.getCount() : 1;
        var rl = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (rl == null) return false;
        CompoundTag nbt = null;
        var customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData != null) {
            nbt = customData.copyTag();
        }

        PacketDistributor.sendToServer(new WarehouseActionPacket(
                buildingPos, "deposit_from_slot", rl.toString(), nbt, qty, slot));
        return true;
    }

    // ── Filters ──

    private void applyFilter(String query) {
        filteredItems = filterItems(query);
        if (overviewList != null) {
            overviewList.setItems(filteredItems);
        }
    }

    private void applyExchangeFilter(String query) {
        exchangeFilteredItems = filterItems(query);
        if (exchangeList != null) {
            exchangeList.setItems(exchangeFilteredItems);
        }
    }

    private List<ItemEntry> filterItems(String query) {
        if (query == null || query.isEmpty()) {
            return new ArrayList<>(allItems);
        }
        String lower = query.toLowerCase();
        List<ItemEntry> result = new ArrayList<>();
        for (ItemEntry item : allItems) {
            if (item.itemId().toLowerCase().contains(lower)) {
                result.add(item);
            }
        }
        return result;
    }

    // ── Helpers ──

    private static String formatCount(long n) {
        if (n < 1000) return String.valueOf(n);
        if (n < 1_000_000) return String.format("%.1fK", n / 1000.0);
        return String.format("%.1fM", n / 1_000_000.0);
    }
}
