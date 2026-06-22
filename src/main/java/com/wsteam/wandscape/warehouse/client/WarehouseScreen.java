package com.wsteam.wandscape.warehouse.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.wsteam.wandscape.shared.data.ElementType;
import com.wsteam.wandscape.shared.ui.component.ElementPanel;
import com.wsteam.wandscape.shared.ui.component.MedievalButton;
import com.wsteam.wandscape.shared.ui.component.MedievalScreen;
import com.wsteam.wandscape.shared.ui.component.ScrollableList;
import com.wsteam.wandscape.shared.ui.component.SearchBar;
import com.wsteam.wandscape.shared.ui.theme.MedievalColors;
import com.wsteam.wandscape.warehouse.network.WarehouseDataPacket;
import com.wsteam.wandscape.warehouse.network.WarehouseDataPacket.ItemEntry;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class WarehouseScreen extends MedievalScreen {

    private static final int PW = 360;
    private static final int PH = 220;

    private List<ItemEntry> allItems = new ArrayList<>();
    private List<ItemEntry> filteredItems = new ArrayList<>();
    private Map<ElementType, Long> elements = new LinkedHashMap<>();

    private ScrollableList<ItemEntry> itemList;
    private SearchBar searchBar;
    private ElementPanel elementPanel;

    public WarehouseScreen() {
        super(Component.literal("Colony Warehouse"), PW, PH);
        setTitleBar("Colony Warehouse");
    }

    public void updateItems(WarehouseDataPacket packet) {
        this.allItems = packet.itemEntries();
        this.elements = packet.elementMap();
        applyFilter(searchBar != null ? searchBar.getValue() : "");
    }

    @Override
    protected void init() {
        super.init();

        int contentX = leftPos + 8;
        int contentY = topPos + headerHeight + 4;
        int elementPanelW = 130;

        // Element panel (left side)
        elementPanel = new ElementPanel(contentX, contentY, elementPanelW);
        elementPanel.setElements(elements);
        addRenderableWidget(elementPanel);

        // Right side for items
        int rightX = contentX + elementPanelW + 6;
        int rightW = PW - 16 - elementPanelW - 6;

        // Search bar
        searchBar = new SearchBar(rightX, contentY, rightW, 14,
                "Search items...", this::applyFilter);
        addRenderableWidget(searchBar);

        // Item list
        int listY = contentY + 18;
        int listH = PH - headerHeight - 4 - 18 - 28;
        itemList = new ScrollableList<>(rightX, listY, rightW, listH, 20) {
            @Override
            protected void renderRow(GuiGraphics g, ItemEntry item, int x, int y, int index,
                                     boolean selected, boolean hovered) {
                var registryItem = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(item.itemId()));
                if (registryItem != null && registryItem != Items.AIR) {
                    g.renderItem(new ItemStack(registryItem), x, y + 1);
                }

                String name = formatItemName(item.itemId());
                int textColor = selected ? MedievalColors.ACCENT_GOLD
                        : hovered ? MedievalColors.TEXT_WARM_WHITE
                        : MedievalColors.TEXT_MUTED;
                g.drawString(Minecraft.getInstance().font, name, x + 20, y + 2, textColor);

                String count = "x" + formatCount(item.count());
                int countW = Minecraft.getInstance().font.width(count);
                g.drawString(Minecraft.getInstance().font, count,
                        x + getWidth() - scrollbarWidth - countW - 6, y + 2,
                        MedievalColors.TEXT_DIM);
            }
        };
        itemList.setItems(filteredItems);
        addRenderableWidget(itemList);

        // Close button
        addRenderableWidget(new MedievalButton(
                leftPos + PW - 54, topPos + PH - 22, 46, 16,
                Component.literal("Close"), this::onClose));
    }

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
