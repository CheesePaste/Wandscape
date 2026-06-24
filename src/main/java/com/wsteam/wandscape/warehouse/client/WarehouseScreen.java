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
 * Warehouse GUI with vanilla-style slot interactions.
 *
 * <p>Left-click an item row → withdraw 1.
 * Right-click an item row → withdraw a stack (64).
 * Use the stepper + Withdraw button for custom quantities.
 * Deposit: puts the player's held item into the warehouse.
 */
public class WarehouseScreen extends MedievalScreen {

    private static final int PW = 380;
    private static final int PH = 248;
    private static final int MAX_QTY = 64;

    private BlockPos buildingPos = BlockPos.ZERO;
    private UUID colonyId = new UUID(0, 0);

    private List<ItemEntry> allItems = new ArrayList<>();
    private List<ItemEntry> filteredItems = new ArrayList<>();
    private Map<ElementType, Long> elements = new LinkedHashMap<>();

    private ScrollableList<ItemEntry> itemList;
    private SearchBar searchBar;
    private ElementPanel elementPanel;

    // Stepper for custom withdraw quantity
    private int withdrawQty = 1;

    public WarehouseScreen() {
        super(Component.literal("Colony Warehouse"), PW, PH);
        setTitleBar("Colony Warehouse");
    }

    public void updateItems(WarehouseDataPacket packet) {
        this.buildingPos = packet.buildingPos();
        this.colonyId = packet.colonyId();
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

        // ── Left: Element panel ──
        elementPanel = new ElementPanel(contentX, contentY, elementPanelW);
        elementPanel.setElements(elements);
        addRenderableWidget(elementPanel);

        // ── Right: Item list + controls ──
        int rightX = contentX + elementPanelW + 6;
        int rightW = PW - 16 - elementPanelW - 6;

        // Search bar
        searchBar = new SearchBar(rightX, contentY, rightW, 14,
                "Search items...", this::applyFilter);
        addRenderableWidget(searchBar);

        // Item list — leave room for control row at bottom
        int listY = contentY + 18;
        int controlH = 28; // height reserved for the stepper + buttons row
        int listH = PH - headerHeight - 4 - 18 - controlH - 4;
        itemList = new ScrollableList<>(rightX, listY, rightW, listH, 20) {
            @Override
            protected void renderRow(GuiGraphics g, ItemEntry item, int x, int y, int index,
                                     boolean selected, boolean hovered) {
                // Render item icon like a vanilla slot
                var registryItem = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(item.itemId()));
                if (registryItem != null && registryItem != Items.AIR) {
                    ItemStack icon = new ItemStack(registryItem);
                    if (item.nbt() != null && !item.nbt().isEmpty()) {
                        icon.set(DataComponents.CUSTOM_DATA,
                                net.minecraft.world.item.component.CustomData.of(item.nbt().copy()));
                    }
                    g.renderItem(icon, x, y + 2);
                }

                // Item name
                String name = formatItemName(item.itemId());
                int textColor = selected ? MedievalColors.ACCENT_GOLD
                        : hovered ? 0xFFFFEEAA
                        : MedievalColors.TEXT_WARM_WHITE;
                g.drawString(Minecraft.getInstance().font, name, x + 20, y + 3, textColor);

                // Count overlay (right-aligned, like vanilla slot count)
                String count = formatCount(item.count());
                int countW = Minecraft.getInstance().font.width(count);
                g.drawString(Minecraft.getInstance().font, count,
                        x + getWidth() - scrollbarWidth - countW - 8, y + 3,
                        MedievalColors.TEXT_DIM);

                // Hover hint
                if (hovered) {
                    String hint = "L-click: +1  |  R-click: +" + MAX_QTY;
                    g.drawString(Minecraft.getInstance().font, hint,
                            x + 20, y + 14, 0xFFAAAAAA);
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

                    if (button == 0) { // Left click → withdraw 1
                        if (entry.count() > 0) {
                            PacketDistributor.sendToServer(new WarehouseActionPacket(
                                    buildingPos, "withdraw", entry.itemId(), entry.nbt(), 1));
                        }
                    } else if (button == 1) { // Right click → withdraw 64
                        if (entry.count() > 0) {
                            int take = (int) Math.min(entry.count(), MAX_QTY);
                            PacketDistributor.sendToServer(new WarehouseActionPacket(
                                    buildingPos, "withdraw", entry.itemId(), entry.nbt(), take));
                        }
                    }
                    return true;
                }
                return false;
            }
        };
        itemList.setItems(filteredItems);
        addRenderableWidget(itemList);

        // ── Control row: stepper + buttons ──
        int controlY = listY + listH + 4;

        // [-] button
        addRenderableWidget(new MedievalButton(rightX, controlY, 18, 16,
                Component.literal("-"), this::onDecrement));

        // Quantity label background + centered number
        // Rendered in render(); we just add an invisible widget for bounds
        // Actually, use a static label — render in the screen's render

        // [+] button
        addRenderableWidget(new MedievalButton(rightX + 20, controlY, 18, 16,
                Component.literal("+"), this::onIncrement));

        // Withdraw button (custom quantity)
        addRenderableWidget(new MedievalButton(rightX + 42, controlY, 64, 16,
                Component.literal("Withdraw"), this::onWithdraw));

        // Deposit button
        addRenderableWidget(new MedievalButton(rightX + rightW - 72, controlY, 64, 16,
                Component.literal("Deposit"), this::onDeposit));

        // Close button
        addRenderableWidget(new MedievalButton(
                leftPos + PW - 54, topPos + PH - 22, 46, 16,
                Component.literal("Close"), this::onClose));
    }

    // ── Overlay renders ──

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        // Draw the stepper quantity label and hand item hint
        int contentX = leftPos + 8;
        int contentY = topPos + headerHeight + 4;
        int elementPanelW = 130;
        int rightX = contentX + elementPanelW + 6;
        int rightW = PW - 16 - elementPanelW - 6;
        int listY = contentY + 18;
        int controlH = 28;
        int listH = PH - headerHeight - 4 - 18 - controlH - 4;
        int controlY = listY + listH + 4;

        // Quantity display between - and + buttons
        int qtyX = rightX + 40;
        int qtyY = controlY + 4;
        String qtyStr = String.valueOf(withdrawQty);
        int qtyW = Minecraft.getInstance().font.width(qtyStr);
        // dark bg behind number
        g.fill(rightX + 38, controlY, rightX + 42 + 64, controlY + 16,
                0x60000000);
        g.drawString(Minecraft.getInstance().font, qtyStr,
                rightX + 38 + (64 - qtyW) / 2, qtyY, MedievalColors.ACCENT_GOLD);

        // Hand item hint (right of quantity, left of deposit button)
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
    }

    // ── Stepper ───────────────────────────────────────────────────────────

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

    // ── Actions ───────────────────────────────────────────────────────────

    private void onWithdraw() {
        ItemEntry sel = itemList.getSelected();
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

    // ── Filter ────────────────────────────────────────────────────────────

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
