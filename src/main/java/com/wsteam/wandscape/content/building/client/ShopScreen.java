package com.wsteam.wandscape.content.building.client;

import com.wsteam.wandscape.content.building.network.ShopMaxStockPacket;
import com.wsteam.wandscape.foundation.networking.Net;
import com.wsteam.wandscape.foundation.ui.I18n;
import com.wsteam.wandscape.foundation.ui.component.MedievalButton;
import com.wsteam.wandscape.foundation.ui.component.MedievalScreen;
import com.wsteam.wandscape.foundation.ui.component.Slider;
import com.wsteam.wandscape.foundation.ui.theme.MedievalColors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
/**
 * Shop GUI — per-good max-stock slider (0–64) with −/+ buttons.
 * Uses the shared {@link Slider} component with blue/black theme.
 *
 * <p>Goods whose synthesize recipe is still locked carry a 「配方未解锁」 hint at the row's
 * right edge, explaining why the colony can never restock them.
 */
public class ShopScreen extends MedievalScreen {

    private static final int PW = 300;
    private static final int PH = 230;

    private static final int ROW_H = 26;
    private static final int ICON_SIZE = 16;
    private static final int SLIDER_X = 128;
    /** Narrower than before: the locked-recipe hint needs the right part of the row. */
    private static final int SLIDER_W = 56;
    private static final int BTN_W = 16;
    private static final int BTN_H = 14;
    private static final int ROW_RIGHT_MARGIN = 8;

    private final BlockPos buildingPos;
    private final UUID colonyId;
    private final UUID buildingId;
    private Map<String, Integer> stock;
    private Map<String, Integer> maxStocks;
    private Set<String> lockedGoods;
    private String[] itemIds;
    private ItemStack[] icons;
    private Component[] displayNames;

    public ShopScreen(BlockPos buildingPos, UUID colonyId, UUID buildingId, String creator,
                      Map<String, Integer> stock, Map<String, Integer> maxStocks,
                      Set<String> lockedGoods) {
        super(Component.literal("Shop"), PW, PH);
        setTitleBar(I18n.name("gui.wandscape.shop.title", "Shop"));
        this.showCloseButton = true;
        this.showHelpButton = true;
        this.helpDocumentPath = "shop_guide";
        this.buildingPos = buildingPos;
        this.colonyId = colonyId;
        this.buildingId = buildingId;
        setCreator(creator);
        setBuildingContext(buildingId, buildingPos);
        this.stock = new LinkedHashMap<>(stock);
        this.maxStocks = new LinkedHashMap<>(maxStocks);
        this.lockedGoods = new LinkedHashSet<>(lockedGoods);
        this.itemIds = this.maxStocks.keySet().toArray(new String[0]);
        resolveIcons();
    }

    public void updateFrom(Map<String, Integer> newStock, Map<String, Integer> newMaxStocks,
                           Set<String> newLockedGoods) {
        this.stock = new LinkedHashMap<>(newStock);
        this.maxStocks = new LinkedHashMap<>(newMaxStocks);
        this.lockedGoods = new LinkedHashSet<>(newLockedGoods);
        String[] newKeys = this.maxStocks.keySet().toArray(new String[0]);
        if (!java.util.Arrays.equals(this.itemIds, newKeys)) {
            this.itemIds = newKeys;
            resolveIcons();
            clearWidgets();
            init();
        }
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
                I18n.name("gui.wandscape.common.close", "Close"), this::onClose));

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
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        var font = Minecraft.getInstance().font;
        int x = leftPos + 16;

        if (itemIds.length == 0) {
            g.drawString(font, I18n.name("gui.wandscape.shop.no_goods", "No goods configured."),
                    x, firstRowY() + 4, MedievalColors.TEXT_MUTED);
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
            String count = "×" + cur + "/" + max;
            g.drawString(font, count, rightX, cy - font.lineHeight / 2, textColor);

            // 配方未解锁：该货物没有任何补货途径，贴行右缘说明原因。
            // 译文过长放不下时宁可整条不画，也不压到库存数字上。
            if (lockedGoods.contains(itemId)) {
                Component hint = I18n.name("gui.wandscape.shop.recipe_locked", "Locked");
                int hintX = leftPos + PW - ROW_RIGHT_MARGIN - font.width(hint);
                if (hintX >= rightX + font.width(count) + 2) {
                    g.drawString(font, hint, hintX, cy - font.lineHeight / 2, MedievalColors.TEXT_DIM);
                }
            }
        }

        // 货架由殖民地补货：仓库没有的货会在工作站下合成单，玩家常不知道来源。
        // 提示固定在货物列表下方、creator 页脚（PH-24）之上那一行。
        g.drawString(font, I18n.name("gui.wandscape.shop.craft_hint",
                        "Goods can be crafted at the workstation"),
                leftPos + 16, topPos + PH - 36, MedievalColors.TEXT_DIM);
    }

    private void adjustMaxStock(String itemId, int newMax) {
        newMax = Math.clamp(newMax, 0, 64);
        Net.toServer(new ShopMaxStockPacket(
                buildingId, buildingPos, colonyId, itemId, newMax));
    }
}
