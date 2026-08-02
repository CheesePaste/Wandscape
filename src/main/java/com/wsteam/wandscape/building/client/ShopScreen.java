package com.wsteam.wandscape.building.client;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.wsteam.wandscape.building.network.ShopMaxStockPacket;
import com.wsteam.wandscape.shared.ui.component.MedievalButton;
import com.wsteam.wandscape.shared.ui.component.MedievalScreen;
import com.wsteam.wandscape.shared.ui.component.Slider;
import com.wsteam.wandscape.shared.ui.theme.MedievalColors;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
/**
 * Shop GUI — per-good max-stock slider (0–64) with −/+ buttons.
 * Uses the shared {@link Slider} component with blue/black theme.
 */
public class ShopScreen extends MedievalScreen {

    private static final int PW = 300;
    private static final int PH = 230;

    private static final int ROW_H = 26;
    private static final int ICON_SIZE = 16;
    private static final int SLIDER_X = 128;
    private static final int SLIDER_W = 72;
    private static final int BTN_W = 16;
    private static final int BTN_H = 14;

    private final BlockPos buildingPos;
    private final UUID colonyId;
    private final UUID buildingId;
    private Map<String, Integer> stock;
    private Map<String, Integer> maxStocks;
    private String[] itemIds;
    private ItemStack[] icons;
    private Component[] displayNames;

    public ShopScreen(BlockPos buildingPos, UUID colonyId, UUID buildingId,
                      Map<String, Integer> stock, Map<String, Integer> maxStocks) {
        super(Component.literal("Shop"), PW, PH);
        setTitleBar("Shop");
        this.showCloseButton = true;
        this.showHelpButton = true;
        this.helpDocumentPath = "shop_guide";
        this.buildingPos = buildingPos;
        this.colonyId = colonyId;
        this.buildingId = buildingId;
        this.stock = new LinkedHashMap<>(stock);
        this.maxStocks = new LinkedHashMap<>(maxStocks);
        this.itemIds = this.maxStocks.keySet().toArray(new String[0]);
        resolveIcons();
    }

    public void updateFrom(Map<String, Integer> newStock, Map<String, Integer> newMaxStocks) {
        this.stock = new LinkedHashMap<>(newStock);
        this.maxStocks = new LinkedHashMap<>(newMaxStocks);
        this.itemIds = this.maxStocks.keySet().toArray(new String[0]);
        resolveIcons();
        clearWidgets();
        init();
    }

    private void resolveIcons() {
        icons = new ItemStack[itemIds.length];
        displayNames = new Component[itemIds.length];
        for (int i = 0; i < itemIds.length; i++) {
            ResourceLocation rl = ResourceLocation.tryParse(itemIds[i]);
            var item = rl != null ? BuiltInRegistries.ITEM.get(rl) : null;
            if (item != null) {
                icons[i] = new ItemStack(item);
                displayNames[i] = icons[i].getHoverName();
            } else {
                icons[i] = ItemStack.EMPTY;
                displayNames[i] = Component.literal(itemIds[i]);
            }
        }
    }

    // ── Y‑coordinate ──

    private int firstRowY() {
        return topPos + headerHeight + 8;
    }

    private int rowCenterY(int index) {
        return firstRowY() + index * ROW_H + ROW_H / 2;
    }

    // ── init ──

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(new MedievalButton(
                leftPos + PW - 54, topPos + PH - 20, 46, 16,
                Component.literal("Close"), this::onClose));

        for (int i = 0; i < itemIds.length; i++) {
            int cy = rowCenterY(i);
            String itemId = itemIds[i];
            int max = maxStocks.getOrDefault(itemId, 0);

            int sX = leftPos + SLIDER_X;
            int sY = cy - 10; // Slider total height ~22, center it

            // [-] button
            addRenderableWidget(new MedievalButton(
                    sX - BTN_W - 2, cy - BTN_H / 2,
                    BTN_W, BTN_H, Component.literal("−"),
                    () -> adjustMaxStock(itemId, max - 1)));

            // Slider — shared component with blue/black theme
            Slider slider = new Slider(sX, sY, SLIDER_W, 0, 64, max,
                    newVal -> adjustMaxStock(itemId, newVal));
            addRenderableWidget(slider);

            // [+] button
            addRenderableWidget(new MedievalButton(
                    sX + SLIDER_W + 2, cy - BTN_H / 2,
                    BTN_W, BTN_H, Component.literal("+"),
                    () -> adjustMaxStock(itemId, max + 1)));

            if (firstRowY() + (i + 1) * ROW_H > topPos + PH - 40) break;
        }
    }

    // ── render ──

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        var font = Minecraft.getInstance().font;
        int x = leftPos + 16;

        if (itemIds.length == 0) {
            g.drawString(font, "No goods configured.", x, firstRowY() + 4, MedievalColors.TEXT_MUTED);
        }

        for (int i = 0; i < itemIds.length; i++) {
            int cy = rowCenterY(i);
            if (firstRowY() + (i + 1) * ROW_H > topPos + PH - 40) break;

            String itemId = itemIds[i];
            int max = maxStocks.getOrDefault(itemId, 0);
            int cur = stock.getOrDefault(itemId, 0);
            int textColor = cur > 0 ? MedievalColors.TEXT_WARM_WHITE : MedievalColors.TEXT_MUTED;

            // Item icon
            if (i < icons.length && !icons[i].isEmpty()) {
                g.renderItem(icons[i], x, cy - ICON_SIZE / 2);
            }

            // Item display name
            Component name = (i < displayNames.length) ? displayNames[i]
                    : Component.literal(itemId);
            g.drawString(font, name, x + 20, cy - font.lineHeight / 2, textColor);

            // ×cur/max to the right of [+]
            int rightX = leftPos + SLIDER_X + SLIDER_W + BTN_W + 6;
            g.drawString(font, "×" + cur + "/" + max, rightX, cy - font.lineHeight / 2, textColor);
        }

        String bldText = "Building: " + buildingId.toString().substring(0, 8);
        g.drawString(font, bldText, leftPos + 16, topPos + PH - 26, MedievalColors.TEXT_DIM);
    }

    private void adjustMaxStock(String itemId, int newMax) {
        newMax = Math.clamp(newMax, 0, 64);
        PacketDistributor.sendToServer(new ShopMaxStockPacket(
                buildingId, buildingPos, colonyId, itemId, newMax));
    }
}
