package com.wsteam.wandscape.warehouse.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.wsteam.wandscape.shared.data.ElementType;
import com.wsteam.wandscape.shared.ui.component.ElementPanel;
import com.wsteam.wandscape.shared.ui.component.MedievalScreen;
import com.wsteam.wandscape.shared.ui.component.ScrollableList;
import com.wsteam.wandscape.shared.ui.component.SearchBar;
import com.wsteam.wandscape.shared.ui.component.TabBar;
import com.wsteam.wandscape.shared.ui.theme.MedievalColors;
import com.wsteam.wandscape.warehouse.network.WarehouseActionPacket;
import com.wsteam.wandscape.warehouse.network.WarehouseDataPacket;
import com.wsteam.wandscape.warehouse.network.WarehouseDataPacket.ItemEntry;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
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
 * Warehouse GUI with two tabs.
 *
 * <p><b>Overview tab:</b> Read-only display of elements and items — browse only, no interaction.
 * <br><b>Exchange tab:</b> Two-panel layout: warehouse items (click to withdraw) above
 * player inventory (click to deposit). Resembles a vanilla container interaction.
 */
public class WarehouseScreen extends MedievalScreen {

    private static final int PW = 380;
    private static final int PH = 248;
    private static final int MAX_QTY = 64;
    private static final int SLOT_SIZE = 18;

    private BlockPos buildingPos = BlockPos.ZERO;
    private UUID colonyId = new UUID(0, 0);
    private int activeTab = 0;

    // Data
    private List<ItemEntry> allItems = new ArrayList<>();
    private List<ItemEntry> filteredItems = new ArrayList<>();
    private List<ItemEntry> exchangeFilteredItems = new ArrayList<>();
    private Map<ElementType, Long> elements = new LinkedHashMap<>();

    // ── Tab 0: Overview widgets ──
    private SearchBar searchBar;
    private ElementPanel elementPanel;
    private ScrollableList<ItemEntry> overviewList;

    // ── Tab 1: Exchange widgets ──
    private ScrollableList<ItemEntry> exchangeList;
    private SearchBar exchangeSearchBar;

    public WarehouseScreen() {
        super(Component.literal("Colony Warehouse"), PW, PH);
        setTitleBar("Colony Warehouse");
    }

    // ── Data updates from server ──

    public void updateItems(WarehouseDataPacket packet) {
        this.buildingPos = packet.buildingPos();
        this.colonyId = packet.colonyId();
        this.allItems = packet.itemEntries();
        this.allItems.sort(Comparator.comparing(ItemEntry::itemId));
        this.elements = packet.elementMap();
        applyFilter(searchBar != null ? searchBar.getValue() : "");
        applyExchangeFilter(exchangeSearchBar != null ? exchangeSearchBar.getValue() : "");
    }

    // ── Init ──

    @Override
    protected void init() {
        super.init();

        int contentX = leftPos + 8;
        int contentY = topPos + headerHeight + 4;

        // Tab bar
        var tabBar = new TabBar(contentX, contentY, PW - 16,
                List.of("Overview", "Exchange"), activeTab, this::onTabChanged);
        addRenderableWidget(tabBar);

        // Build both tabs
        buildOverviewTab(contentX, contentY);
        buildExchangeTab(contentX, contentY);

        // Show active tab
        showTab(activeTab);

    }

    // ── Tab 0: Overview (read-only) ──

    private void buildOverviewTab(int contentX, int contentY) {
        int tabY = contentY + 20; // below tab bar
        int tabH = PH - headerHeight - 4 - 20;
        int elementPanelW = 130;

        // Left: Element panel
        elementPanel = new ElementPanel(contentX, tabY, elementPanelW);
        elementPanel.setElements(elements);

        // Right: Search bar + item list (read-only)
        int rightX = contentX + elementPanelW + 6;
        int rightW = PW - 16 - elementPanelW - 6;

        searchBar = new SearchBar(rightX, tabY, rightW, 14,
                "Search items...", this::applyFilter);

        int listY = tabY + 18;
        int listH = tabH - 18 - 8;
        overviewList = new ScrollableList<>(rightX, listY, rightW, listH, 20) {
            @Override
            protected void renderRow(GuiGraphics g, ItemEntry item, int x, int y, int index,
                                     boolean selected, boolean hovered) {
                var registryItem = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(item.itemId()));
                if (registryItem != null && registryItem != Items.AIR) {
                    ItemStack icon = new ItemStack(registryItem);
                    if (item.nbt() != null && !item.nbt().isEmpty()) {
                        icon.set(DataComponents.CUSTOM_DATA,
                                net.minecraft.world.item.component.CustomData.of(item.nbt().copy()));
                    }
                    g.renderItem(icon, x, y + 2);
                }
                String name = formatItemName(item.itemId());
                int textColor = selected ? MedievalColors.ACCENT_GOLD
                        : hovered ? 0xFFFFEEAA : MedievalColors.TEXT_WARM_WHITE;
                g.drawString(Minecraft.getInstance().font, name, x + 20, y + 3, textColor);
                String count = formatCount(item.count());
                int countW = Minecraft.getInstance().font.width(count);
                g.drawString(Minecraft.getInstance().font, count,
                        x + getWidth() - scrollbarWidth - countW - 8, y + 3, MedievalColors.TEXT_DIM);
            }
        };
        overviewList.setItems(filteredItems);
        // No row click handler — read-only tab
    }

    // ── Tab 1: Exchange (warehouse ↔ player inventory interaction) ──

    private void buildExchangeTab(int contentX, int contentY) {
        int tabY = contentY + 20; // below tab bar
        int tabH = PH - headerHeight - 4 - 20;
        int rightW = PW - 16;

        // Player inventory section height: label (10px) + 4 rows of slots (4 * SLOT_SIZE)
        int invSectionH = 10 + SLOT_SIZE * 4;
        int listH = tabH - invSectionH - 4; // remaining space minus gap

        // Top: warehouse items list
        exchangeList = new ScrollableList<>(contentX, tabY, rightW, listH, 20) {
            @Override
            protected void renderRow(GuiGraphics g, ItemEntry item, int x, int y, int index,
                                     boolean selected, boolean hovered) {
                var registryItem = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(item.itemId()));
                if (registryItem != null && registryItem != Items.AIR) {
                    ItemStack icon = new ItemStack(registryItem);
                    if (item.nbt() != null && !item.nbt().isEmpty()) {
                        icon.set(DataComponents.CUSTOM_DATA,
                                net.minecraft.world.item.component.CustomData.of(item.nbt().copy()));
                    }
                    g.renderItem(icon, x, y + 2);
                }
                String name = formatItemName(item.itemId());
                int textColor = selected ? MedievalColors.ACCENT_GOLD
                        : hovered ? 0xFFFFEEAA : MedievalColors.TEXT_WARM_WHITE;
                g.drawString(Minecraft.getInstance().font, name, x + 20, y + 3, textColor);
                String count = formatCount(item.count());
                int countW = Minecraft.getInstance().font.width(count);
                g.drawString(Minecraft.getInstance().font, count,
                        x + getWidth() - scrollbarWidth - countW - 8, y + 3, MedievalColors.TEXT_DIM);
                if (hovered) {
                    String hint = "L-click: withdraw 1  |  R-click: withdraw " + MAX_QTY;
                    g.drawString(Minecraft.getInstance().font, hint, x + 20, y + 14, 0xFFAAAAAA);
                }
            }
        };
        exchangeList.setItems(exchangeFilteredItems);
        exchangeList.setOnRowClick((entry, index, button) -> {
            if (entry.count() <= 0) return;
            int take = button == 1
                    ? (int) Math.min(entry.count(), MAX_QTY)
                    : 1;
            PacketDistributor.sendToServer(new WarehouseActionPacket(
                    buildingPos, "withdraw", entry.itemId(), entry.nbt(), take, -1));
        });

        // Bottom-right: Search bar next to player inventory slots
        int invSlotsW = 9 * SLOT_SIZE;
        int sbX = leftPos + 8 + invSlotsW + 8;
        int sbY = getInventoryY();
        int sbW = (leftPos + PW - 16) - sbX;
        exchangeSearchBar = new SearchBar(sbX, sbY, sbW, 14,
                "Search items...", this::applyExchangeFilter);
    }

    /** Y-position of the player inventory label (exchange tab). */
    private int getInventoryY() {
        int contentY = topPos + headerHeight + 4;
        int tabY = contentY + 20;
        int tabH = PH - headerHeight - 4 - 20;
        return tabY + tabH - (10 + SLOT_SIZE * 4);
    }

    // ── Tab switching ──

    private void onTabChanged(int tabIndex) {
        activeTab = tabIndex;
        showTab(tabIndex);
    }

    private void showTab(int tabIndex) {
        removeWidget(elementPanel);
        removeWidget(searchBar);
        removeWidget(overviewList);
        removeWidget(exchangeList);
        removeWidget(exchangeSearchBar);

        if (tabIndex == 0) {
            addRenderableWidget(elementPanel);
            addRenderableWidget(searchBar);
            addRenderableWidget(overviewList);
        } else {
            addRenderableWidget(exchangeList);
            addRenderableWidget(exchangeSearchBar);
        }
    }

    // ── Render ──

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        if (activeTab == 1) {
            renderPlayerInventory(g, mouseX, mouseY);
        }
    }

    private void renderPlayerInventory(GuiGraphics g, int mouseX, int mouseY) {
        int invY = getInventoryY();
        int invX = leftPos + 8;

        // Separator line
        g.fill(invX, invY - 2, invX + 9 * SLOT_SIZE, invY - 1, MedievalColors.BORDER_GOLD_DARK);

        // Label
        g.drawString(font, "Player Inventory", invX, invY, MedievalColors.TEXT_MUTED);
        invY += 10;

        var player = Minecraft.getInstance().player;
        if (player == null) return;
        var inventory = player.getInventory();

        // Main inventory (slots 9-35, 3 rows of 9)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int slot = 9 + row * 9 + col;
                int sx = invX + col * SLOT_SIZE;
                int sy = invY + row * SLOT_SIZE;
                boolean hovered = mouseX >= sx && mouseX < sx + SLOT_SIZE
                        && mouseY >= sy && mouseY < sy + SLOT_SIZE;
                renderSlot(g, inventory.getItem(slot), sx, sy, hovered);
            }
        }

        // Hotbar (slots 0-8)
        for (int col = 0; col < 9; col++) {
            int sx = invX + col * SLOT_SIZE;
            int sy = invY + 3 * SLOT_SIZE;
            boolean hovered = mouseX >= sx && mouseX < sx + SLOT_SIZE
                    && mouseY >= sy && mouseY < sy + SLOT_SIZE;
            renderSlot(g, inventory.getItem(col), sx, sy, hovered);
        }

        // Hover tooltip
        renderInventoryTooltip(g, mouseX, mouseY, invX, invY);
    }

    private void renderSlot(GuiGraphics g, ItemStack stack, int x, int y, boolean hovered) {
        // Slot background
        g.fill(x, y, x + SLOT_SIZE, y + SLOT_SIZE,
                hovered ? MedievalColors.PARCHMENT_LIGHT : MedievalColors.PARCHMENT_DEEPEST);
        g.fill(x + 1, y + 1, x + SLOT_SIZE - 1, y + SLOT_SIZE - 1,
                hovered ? MedievalColors.PARCHMENT_MID : MedievalColors.PARCHMENT_DARK);

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
                if (hitTest(mouseX, mouseY, sx, sy)) {
                    showHoverText(g, inventory.getItem(slot), mouseX, mouseY);
                    return;
                }
            }
        }
        for (int col = 0; col < 9; col++) {
            int sx = invX + col * SLOT_SIZE;
            int sy = invY + 3 * SLOT_SIZE;
            if (hitTest(mouseX, mouseY, sx, sy)) {
                showHoverText(g, inventory.getItem(col), mouseX, mouseY);
                return;
            }
        }
    }

    private static boolean hitTest(double mx, double my, int x, int y) {
        return mx >= x && mx < x + SLOT_SIZE && my >= y && my < y + SLOT_SIZE;
    }

    private static void showHoverText(GuiGraphics g, ItemStack stack, int mx, int my) {
        if (!stack.isEmpty()) {
            g.drawString(Minecraft.getInstance().font, stack.getHoverName().getString(),
                    mx + 8, my - 12, MedievalColors.TEXT_WARM_WHITE);
        }
    }

    // ── Mouse input ──

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Try player inventory click first (exchange tab only)
        if (activeTab == 1 && isOverInventoryArea(mouseX, mouseY)) {
            if (handleInventoryClick((int) mouseX, (int) mouseY, button)) return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean isOverInventoryArea(double mouseX, double mouseY) {
        int invY = getInventoryY() + 10; // after label
        int invX = leftPos + 8;
        return mouseX >= invX && mouseX < invX + 9 * SLOT_SIZE
                && mouseY >= invY && mouseY < invY + 4 * SLOT_SIZE;
    }

    private boolean handleInventoryClick(int mouseX, int mouseY, int button) {
        int invY = getInventoryY() + 10;
        int invX = leftPos + 8;

        var player = Minecraft.getInstance().player;
        if (player == null) return false;

        // Main inventory (slots 9-35)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int slot = 9 + row * 9 + col;
                int sx = invX + col * SLOT_SIZE;
                int sy = invY + row * SLOT_SIZE;
                if (hitTest(mouseX, mouseY, sx, sy)) {
                    return depositFromSlot(slot, button);
                }
            }
        }

        // Hotbar (slots 0-8)
        for (int col = 0; col < 9; col++) {
            int slot = col;
            int sx = invX + col * SLOT_SIZE;
            int sy = invY + 3 * SLOT_SIZE;
            if (hitTest(mouseX, mouseY, sx, sy)) {
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

    // ── Filter ──

    private void applyFilter(String query) {
        if (query == null || query.isEmpty()) {
            filteredItems = new ArrayList<>(allItems);
        } else {
            String lower = query.toLowerCase();
            filteredItems = new ArrayList<>();
            for (ItemEntry item : allItems) {
                if (item.itemId().toLowerCase().contains(lower)) {
                    filteredItems.add(item);
                }
            }
        }
        if (overviewList != null) {
            overviewList.setItems(filteredItems);
        }
    }

    /** Filter the exchange tab's warehouse item list by query. */
    private void applyExchangeFilter(String query) {
        if (query == null || query.isEmpty()) {
            exchangeFilteredItems = new ArrayList<>(allItems);
        } else {
            String lower = query.toLowerCase();
            exchangeFilteredItems = new ArrayList<>();
            for (ItemEntry item : allItems) {
                if (item.itemId().toLowerCase().contains(lower)) {
                    exchangeFilteredItems.add(item);
                }
            }
        }
        if (exchangeList != null) {
            exchangeList.setItems(exchangeFilteredItems);
        }
    }

    // ── Formatting helpers ──

    private static String formatItemName(String itemId) {
        int colon = itemId.indexOf(':');
        String path = colon >= 0 ? itemId.substring(colon + 1) : itemId;
        return path.replace('_', ' ');
    }

    private static String formatCount(long n) {
        if (n < 1000) return String.valueOf(n);
        if (n < 1_000_000) return String.format("%.1fK", n / 1000.0);
        return String.format("%.1fM", n / 1_000_000.0);
    }
}
