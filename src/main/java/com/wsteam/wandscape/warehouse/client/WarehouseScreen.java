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
 * Warehouse GUI: item/element management.
 *
 * <p>Left-click → withdraw 1, right-click → withdraw 64,
 * stepper + buttons for custom quantities, deposit current held item.
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
    private int withdrawQty = 1;

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

    // ── Init ──

    @Override
    protected void init() {
        super.init();

        int contentX = leftPos + 8;
        int contentY = topPos + headerHeight + 4;

        buildInventoryTab(contentX, contentY);
    }

    private void buildInventoryTab(int contentX, int contentY) {
        int elementPanelW = 130;

        // ── Left: Element panel ──
        elementPanel = new ElementPanel(contentX, contentY, elementPanelW);
        elementPanel.setElements(elements);
        addRenderableWidget(elementPanel);

        // ── Right: Item list + controls ──
        int rightX = contentX + elementPanelW + 6;
        int rightW = PW - 16 - elementPanelW - 6;

        searchBar = new SearchBar(rightX, contentY, rightW, 14,
                "Search items...", this::applyFilter);
        addRenderableWidget(searchBar);

        int listY = contentY + 18;
        int controlH = 28;
        int listH = PH - headerHeight - 4 - 18 - controlH - 4;
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
        };
        itemList.setItems(filteredItems);
        itemList.setOnRowClick((entry, index, button) -> {
            if (entry.count() <= 0) return;
            int take = button == 1
                    ? (int) Math.min(entry.count(), MAX_QTY)
                    : 1;
            PacketDistributor.sendToServer(new WarehouseActionPacket(
                    buildingPos, "withdraw", entry.itemId(), entry.nbt(), take));
        });
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
    // ── Inventory rendering (stepper overlay) ──

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        renderInventoryOverlay(g);
    }

    private void renderInventoryOverlay(GuiGraphics g) {
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
    private static String formatCount(long n) {
        if (n < 1000) return String.valueOf(n);
        if (n < 1_000_000) return String.format("%.1fK", n / 1000.0);
        return String.format("%.1fM", n / 1_000_000.0);
    }

}
