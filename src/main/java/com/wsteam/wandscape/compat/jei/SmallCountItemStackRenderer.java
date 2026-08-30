package com.wsteam.wandscape.compat.jei;

import mezz.jei.api.ingredients.IIngredientRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * 元素物品槽位渲染器：图标同 vanilla，数量角标缩小到 0.5 倍并右对齐槽位内右缘。
 *
 * <p>JEI 默认用 {@code GuiGraphics.renderItemDecorations} 画固定 8px 角标，五六位数元素成本
 * （如 fortress 25000 / oblivion 60000）会溢出 16px 槽位。此渲染器覆盖数量角标绘制：
 * 以槽位右缘为锚点缩放 0.5，数字整体缩进槽内，大数量不再出格。
 */
public class SmallCountItemStackRenderer implements IIngredientRenderer<ItemStack> {

    @Override
    public void render(GuiGraphics gui, ItemStack ingredient) {
        render(gui, ingredient, 0, 0);
    }

    @Override
    public void render(GuiGraphics gui, ItemStack ingredient, int posX, int posY) {
        if (ingredient == null || ingredient.isEmpty()) return;
        gui.renderFakeItem(ingredient, posX, posY);
        int count = ingredient.getCount();
        if (count <= 1) return;
        Font font = Minecraft.getInstance().font;
        String s = String.valueOf(count);
        gui.pose().pushPose();
        // z 层级与 vanilla renderItemDecorations 一致，压在图标之上
        gui.pose().translate(0, 0, 200);
        // 右对齐锚点：槽位内右缘（右缘=posX+16），纵向取角标常驻位
        gui.pose().translate(posX + 16, posY + 9, 0);
        gui.pose().scale(0.5f, 0.5f, 1f);
        gui.drawString(font, s, -font.width(s) - 1, 0, 0xFFFFFF, true);
        gui.pose().popPose();
    }

    @Override
    public List<Component> getTooltip(ItemStack ingredient, TooltipFlag tooltipFlag) {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        Item.TooltipContext context = Item.TooltipContext.of(minecraft.level);
        return ingredient.getTooltipLines(context, player, tooltipFlag);
    }
}
