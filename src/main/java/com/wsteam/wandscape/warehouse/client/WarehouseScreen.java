package com.wsteam.wandscape.warehouse.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.wsteam.wandscape.shared.data.ElementType;
import com.wsteam.wandscape.shared.ui.component.ElementPanel;
import com.wsteam.wandscape.shared.ui.component.MedievalButton;
import com.wsteam.wandscape.shared.ui.component.MedievalScreen;
import com.wsteam.wandscape.shared.ui.component.ScrollableList;
import com.wsteam.wandscape.shared.ui.component.SearchBar;
import com.wsteam.wandscape.shared.ui.component.TabBar;
import com.wsteam.wandscape.shared.ui.theme.MedievalColors;
import com.wsteam.wandscape.warehouse.network.SetWarehouseThresholdPacket;
import com.wsteam.wandscape.warehouse.network.WarehouseActionPacket;
import com.wsteam.wandscape.warehouse.network.WarehouseDataPacket;
import com.wsteam.wandscape.warehouse.network.WarehouseDataPacket.ItemEntry;
import com.wsteam.wandscape.warehouse.network.WarehouseThresholdDataPacket;

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
 * Warehouse GUI with two tabs: Inventory (item/element management) and
 * Thresholds (auto-production thresholds).
 *
 * <p>Inventory tab: left-click → withdraw 1, right-click → withdraw 64,
 * stepper + buttons for custom quantities, deposit current held item.
 *
 * <p>Thresholds tab: list of tracked resources with adjustable values.
 */
public class WarehouseScreen extends MedievalScreen {

    private static final int PW = 380;
    private static final int PH = 248;
    private static final int MAX_QTY = 64;

    /** Resource IDs tracked by WarehouseSource, shown in the threshold tab. */
    private static final List<String> KNOWN_RESOURCES = List.of(
            "wood", "stone", "stone_bricks", "glass", "iron_ingot", "dirt", "wheat"
    );

    // ── Tabs ──
    private static final int TAB_INVENTORY = 0;
    private static final int TAB_THRESHOLDS = 1;
    private int activeTab = TAB_INVENTORY;

    private BlockPos buildingPos = BlockPos.ZERO;
    private UUID colonyId = new UUID(0, 0);

    // Inventory tab data
    private List<ItemEntry> allItems = new ArrayList<>();
    private List<ItemEntry> filteredItems = new ArrayList<>();
    private Map<ElementType, Long> elements = new LinkedHashMap<>();
    private ScrollableList<ItemEntry> itemList;
    private SearchBar searchBar;
    private ElementPanel elementPanel;
    private int withdrawQty = 1;

    // Threshold tab data
    private Map<String, Long> thresholds = new LinkedHashMap<>();
    private ScrollableList<ThresholdEntry> thresholdList;
    /** Temporary edit values for the threshold list (resourceId → edited value). */
    private final Map<String, Integer> editValues = new LinkedHashMap<>();

    // Shared widgets
    private TabBar tabBar;

    public WarehouseScreen() {
        super(Component.literal("Colony Warehouse"), PW, PH);
        setTitleBar("Colony Warehouse");
    }

    // ── Data updates from server ──

    public void updateItems(WarehouseDataPacket packet) {
        this.buildingPos = packet.buildingPos();
        this.colonyId = packet.colonyId();
        this.allItems = packet.itemEntries();
        this.elements = packet.elementMap();
        applyFilter(searchBar != null ? searchBar.getValue() : "");
    }

    public void updateThresholds(WarehouseThresholdDataPacket packet) {
        this.buildingPos = packet.buildingPos();
        this.colonyId = packet.colonyId();
        this.thresholds = packet.thresholdMap();
        // Reset edit values to match received data
        editValues.clear();
        for (String res : KNOWN_RESOURCES) {
            long val = thresholds.getOrDefault(res, 0L);
            if (val > 0) {
                editValues.put(res, (int) Math.min(val, 9999));
            }
        }
        refreshThresholdList();
    }

    // ── Init ──

    @Override
    protected void init() {
        super.init();

        int contentX = leftPos + 8;
        int contentY = topPos + headerHeight + 4;

        // ── Tab bar ──
        tabBar = new TabBar(contentX, contentY, PW - 16, List.of("Inventory", "Thresholds"),
                activeTab, this::onTabSelected);
        addRenderableWidget(tabBar);

        int tabContentY = contentY + 22;

        // Build inventory tab widgets (hidden when threshold tab is active)
        buildInventoryTab(contentX, tabContentY);

        // Build threshold tab widgets
        buildThresholdTab(contentX, tabContentY);

        // Show correct tab
        updateTabVisibility();
    }

    private void buildInventoryTab(int contentX, int tabContentY) {
        int elementPanelW = 130;

        // ── Left: Element panel ──
        elementPanel = new ElementPanel(contentX, tabContentY, elementPanelW);
        elementPanel.setElements(elements);
        addRenderableWidget(elementPanel);

        // ── Right: Item list + controls ──
        int rightX = contentX + elementPanelW + 6;
        int rightW = PW - 16 - elementPanelW - 6;

        searchBar = new SearchBar(rightX, tabContentY, rightW, 14,
                "Search items...", this::applyFilter);
        addRenderableWidget(searchBar);

        int listY = tabContentY + 18;
        int controlH = 28;
        int listH = PH - headerHeight - 4 - 22 - 18 - controlH - 4;
        itemList = new ScrollableList<>(rightX, listY, rightW, listH, 20) {
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
                    String hint = "L-click: +1  |  R-click: +" + MAX_QTY;
                    g.drawString(Minecraft.getInstance().font, hint, x + 20, y + 14, 0xFFAAAAAA);
                }
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                if (!visible || !active) return false;
                int contentRight = getX() + width - scrollbarWidth;
                if (mouseX < getX() || mouseX >= contentRight) return false;
                int relY = (int) mouseY - getY();
                int row = (relY / rowHeight) + (scrollOffset / rowHeight);
                if (row >= 0 && row < items.size()) {
                    selectedIndex = row;
                    ItemEntry entry = items.get(row);
                    if (button == 0 && entry.count() > 0) {
                        PacketDistributor.sendToServer(new WarehouseActionPacket(
                                buildingPos, "withdraw", entry.itemId(), entry.nbt(), 1));
                    } else if (button == 1 && entry.count() > 0) {
                        int take = (int) Math.min(entry.count(), MAX_QTY);
                        PacketDistributor.sendToServer(new WarehouseActionPacket(
                                buildingPos, "withdraw", entry.itemId(), entry.nbt(), take));
                    }
                    return true;
                }
                return false;
            }
        };
        itemList.setItems(filteredItems);
        addRenderableWidget(itemList);

        // Control row
        int controlY = listY + listH + 4;
        addRenderableWidget(new MedievalButton(rightX, controlY, 18, 16,
                Component.literal("-"), this::onDecrement));
        addRenderableWidget(new MedievalButton(rightX + 20, controlY, 18, 16,
                Component.literal("+"), this::onIncrement));
        addRenderableWidget(new MedievalButton(rightX + 42, controlY, 64, 16,
                Component.literal("Withdraw"), this::onWithdraw));
        addRenderableWidget(new MedievalButton(rightX + rightW - 72, controlY, 64, 16,
                Component.literal("Deposit"), this::onDeposit));
        addRenderableWidget(new MedievalButton(
                leftPos + PW - 54, topPos + PH - 22, 46, 16,
                Component.literal("Close"), this::onClose));
    }

    private void buildThresholdTab(int contentX, int tabContentY) {
        int rightW = PW - 16;
        int listY = tabContentY;
        int listH = PH - headerHeight - 4 - 22 - 4;

        // Title hint
        // The list is built below

        thresholdList = new ScrollableList<>(contentX, listY, rightW, listH, 24) {
            @Override
            protected void renderRow(GuiGraphics g, ThresholdEntry entry, int x, int y, int index,
                                     boolean selected, boolean hovered) {
                // Resource name
                String name = formatResourceName(entry.resourceId);
                g.drawString(Minecraft.getInstance().font, name, x + 4, y + 3, MedievalColors.TEXT_WARM_WHITE);

                // Current value display
                int editVal = editValues.getOrDefault(entry.resourceId, 0);
                String valStr = String.valueOf(editVal);
                if (editVal <= 0) valStr = "0 (disabled)";
                int valColor = editVal > 0 ? MedievalColors.ACCENT_GOLD : MedievalColors.TEXT_MUTED;
                g.drawString(Minecraft.getInstance().font, valStr, x + 120, y + 3, valColor);

                // [-] button
                int btnY = y + 1;
                int btnW = 14;
                String minus = "<";
                int minusX = x + rightW - 70;
                boolean canMinus = editVal > 0;
                int minusColor = canMinus ? MedievalColors.TEXT_WARM_WHITE : MedievalColors.TEXT_MUTED;
                g.drawString(Minecraft.getInstance().font, minus, minusX + 3, btnY + 3, minusColor);

                // [+] button
                String plus = ">";
                int plusX = x + rightW - 30;
                boolean canPlus = editVal < 9999;
                int plusColor = canPlus ? MedievalColors.TEXT_WARM_WHITE : MedievalColors.TEXT_MUTED;
                g.drawString(Minecraft.getInstance().font, plus, plusX + 3, btnY + 3, plusColor);

                // Draw button backgrounds
                g.fill(minusX, btnY, minusX + btnW, btnY + 14, canMinus ? 0x40000000 : 0x20000000);
                g.fill(plusX, btnY, plusX + btnW, btnY + 14, canPlus ? 0x40000000 : 0x20000000);

                // Save button rectangle for click detection
                entry.minusBtnX = minusX;
                entry.minusBtnW = btnW;
                entry.plusBtnX = plusX;
                entry.plusBtnW = btnW;

                // Divider
                g.fill(x, y + 23, x + rightW, y + 24, MedievalColors.BORDER_GOLD_DARK);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                if (!visible || !active) return false;
                if (mouseX < getX() || mouseX >= getX() + width) return false;

                int relY = (int) mouseY - getY();
                int row = (relY / rowHeight) + (scrollOffset / rowHeight);
                if (row < 0 || row >= items.size()) return false;

                ThresholdEntry entry = items.get(row);

                if (button == 0) {
                    // Check minus button
                    if (mouseX >= entry.minusBtnX && mouseX < entry.minusBtnX + entry.minusBtnW) {
                        int current = editValues.getOrDefault(entry.resourceId, 0);
                        if (current > 0) {
                            int newVal = Math.max(0, current - 8);
                            if (newVal <= 0) { newVal = 0; }
                            setThresholdValue(entry.resourceId, newVal);
                        }
                        return true;
                    }
                    // Check plus button
                    if (mouseX >= entry.plusBtnX && mouseX < entry.plusBtnX + entry.plusBtnW) {
                        int current = editValues.getOrDefault(entry.resourceId, 0);
                        if (current < 9999) {
                            int newVal = Math.min(9999, current + 8);
                            setThresholdValue(entry.resourceId, newVal);
                        }
                        return true;
                    }
                    // Click on row — toggle between 0 and default 64
                    int current = editValues.getOrDefault(entry.resourceId, 0);
                    int newVal = current > 0 ? 0 : 64;
                    setThresholdValue(entry.resourceId, newVal);
                    return true;
                }
                return false;
            }
        };
        thresholdList.visible = false;
        refreshThresholdList();
        addRenderableWidget(thresholdList);
    }

    /** Send threshold update to server. */
    private void setThresholdValue(String resourceId, int newValue) {
        editValues.put(resourceId, newValue);
        PacketDistributor.sendToServer(new SetWarehouseThresholdPacket(
                buildingPos, colonyId, resourceId, newValue));
        refreshThresholdList();
    }

    private void refreshThresholdList() {
        if (thresholdList == null) return;
        List<ThresholdEntry> entries = new ArrayList<>();
        for (String res : KNOWN_RESOURCES) {
            entries.add(new ThresholdEntry(res));
        }
        thresholdList.setItems(entries);
    }

    // ── Tab switching ──

    private void onTabSelected(int index) {
        activeTab = index;
        updateTabVisibility();
    }

    private void updateTabVisibility() {
        boolean inv = activeTab == TAB_INVENTORY;

        // Inventory widgets
        if (elementPanel != null) elementPanel.visible = inv;
        if (searchBar != null) searchBar.visible = inv;
        if (itemList != null) itemList.visible = inv;

        // Threshold widgets
        if (thresholdList != null) thresholdList.visible = !inv;

        // Close button is always visible
    }

    // ── Inventory tab rendering (stepper overlay) ──

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        if (activeTab == TAB_INVENTORY) {
            renderInventoryOverlay(g);
        }
    }

    private void renderInventoryOverlay(GuiGraphics g) {
        int contentX = leftPos + 8;
        int contentY = topPos + headerHeight + 4;
        int elementPanelW = 130;
        int rightX = contentX + elementPanelW + 6;
        int rightW = PW - 16 - elementPanelW - 6;
        int listY = contentY + 22 + 18;
        int controlH = 28;
        int listH = PH - headerHeight - 4 - 22 - 18 - controlH - 4;
        int controlY = listY + listH + 4;

        // Quantity display between - and + buttons
        String qtyStr = String.valueOf(withdrawQty);
        int qtyW = Minecraft.getInstance().font.width(qtyStr);
        g.fill(rightX + 38, controlY, rightX + 42 + 64, controlY + 16, 0x60000000);
        g.drawString(Minecraft.getInstance().font, qtyStr,
                rightX + 38 + (64 - qtyW) / 2, controlY + 4, MedievalColors.ACCENT_GOLD);

        // Hand item hint
        var player = Minecraft.getInstance().player;
        if (player != null) {
            ItemStack hand = player.getMainHandItem();
            int handX = rightX + 110;
            if (!hand.isEmpty()) {
                g.renderItem(hand, handX, controlY - 1);
                String handCount = "x" + hand.getCount();
                g.drawString(Minecraft.getInstance().font, handCount,
                        handX + 18, controlY + 4, MedievalColors.TEXT_WARM_WHITE);
            } else {
                g.drawString(Minecraft.getInstance().font, "Hand: empty",
                        handX, controlY + 4, MedievalColors.TEXT_DIM);
            }
        }

        // Threshold hint in bottom-left
        g.drawString(Minecraft.getInstance().font,
                "Thresholds: set auto-production limits",
                leftPos + 8, topPos + PH - 16, MedievalColors.TEXT_MUTED);
    }

    // ── Stepper ──

    private void onIncrement() {
        if (withdrawQty < MAX_QTY) {
            withdrawQty = Math.min(withdrawQty * 2, MAX_QTY);
        }
    }

    private void onDecrement() {
        if (withdrawQty > 1) {
            withdrawQty = Math.max(withdrawQty / 2, 1);
        }
    }

    // ── Actions ──

    private void onWithdraw() {
        ItemEntry sel = itemList != null ? itemList.getSelected() : null;
        if (sel == null || sel.count() <= 0) return;
        PacketDistributor.sendToServer(new WarehouseActionPacket(
                buildingPos, "withdraw", sel.itemId(), sel.nbt(), withdrawQty));
    }

    private void onDeposit() {
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        ItemStack hand = player.getMainHandItem();
        if (hand.isEmpty()) return;
        var rl = BuiltInRegistries.ITEM.getKey(hand.getItem());
        if (rl == null) return;
        CompoundTag nbt = null;
        var customData = hand.get(DataComponents.CUSTOM_DATA);
        if (customData != null) {
            nbt = customData.copyTag();
        }
        PacketDistributor.sendToServer(new WarehouseActionPacket(
                buildingPos, "deposit", rl.toString(), nbt, hand.getCount()));
    }

    public void onClose() {
        Minecraft.getInstance().setScreen(null);
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
        if (itemList != null) {
            itemList.setItems(filteredItems);
        }
    }

    // ── Formatting helpers ──

    private static String formatItemName(String itemId) {
        int colon = itemId.indexOf(':');
        String path = colon >= 0 ? itemId.substring(colon + 1) : itemId;
        return path.replace('_', ' ');
    }

    private static String formatResourceName(String resourceId) {
        return resourceId.replace('_', ' ');
    }

    private static String formatCount(long n) {
        if (n < 1000) return String.valueOf(n);
        if (n < 1_000_000) return String.format("%.1fK", n / 1000.0);
        return String.format("%.1fM", n / 1_000_000.0);
    }

    // ── Threshold list entry ──

    private static class ThresholdEntry {
        final String resourceId;
        int minusBtnX, minusBtnW, plusBtnX, plusBtnW;

        ThresholdEntry(String resourceId) {
            this.resourceId = resourceId;
        }
    }
}
