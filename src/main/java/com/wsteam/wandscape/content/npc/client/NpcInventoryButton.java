package com.wsteam.wandscape.content.npc.client;

import com.wsteam.wandscape.foundation.ui.theme.MedievalColors;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * 法师背包按钮：10×10 像素精致中世纪皮质背包图标，放置于法师 3D 模型左上角，
 * 点击打开法师背包（SimpleContainer 27 格）。
 */
public class NpcInventoryButton extends AbstractButton {

    private final Runnable onClick;

    public NpcInventoryButton(int x, int y, Runnable onClick) {
        super(x, y, 10, 10, Component.empty());
        this.onClick = onClick;
    }

    @Override
    public void onPress() {
        if (onClick != null) {
            onClick.run();
        }
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (!visible) return;

        int x = getX();
        int y = getY();
        boolean hovered = isHoveredOrFocused();

        int borderColor = hovered ? MedievalColors.BORDER_GOLD : MedievalColors.BORDER_GOLD_DARK;
        int bgColor = hovered ? MedievalColors.BUTTON_BG_HOVER : 0xDD1E1410;

        // 边框 (10x10)
        g.fill(x, y, x + 10, y + 1, borderColor);
        g.fill(x, y + 9, x + 10, y + 10, borderColor);
        g.fill(x, y + 1, x + 1, y + 9, borderColor);
        g.fill(x + 9, y + 1, x + 10, y + 9, borderColor);

        // 背景
        g.fill(x + 1, y + 1, x + 9, y + 9, bgColor);

        // 内部像素画背包 (8x8 区域: x+1..x+8, y+1..y+8)
        int handleColor = 0xFF5A3012;
        int leatherDark = 0xFF6A3E18;
        int leatherMid = 0xFF8B5A2B;
        int leatherLight = 0xFFA67038;
        int buckleColor = hovered ? 0xFFFFE066 : 0xFFD4A840;

        // 提手 (y+2, 4..5)
        g.fill(x + 4, y + 2, x + 6, y + 3, handleColor);
        // 包盖顶部 (y+3, 3..6)
        g.fill(x + 3, y + 3, x + 7, y + 4, leatherLight);
        // 包盖翻折层 (y+4, 2..7)
        g.fill(x + 2, y + 4, x + 8, y + 5, leatherMid);
        // 皮带与主体 (y+5, 2..7)
        g.fill(x + 2, y + 5, x + 8, y + 6, leatherMid);
        g.fill(x + 3, y + 5, x + 4, y + 6, buckleColor);
        g.fill(x + 6, y + 5, x + 7, y + 6, buckleColor);
        // 包身下部 (y+6, 2..7)
        g.fill(x + 2, y + 6, x + 8, y + 7, leatherDark);
        g.fill(x + 3, y + 6, x + 4, y + 7, handleColor);
        g.fill(x + 6, y + 6, x + 7, y + 7, handleColor);
        // 包底 (y+7, 3..6)
        g.fill(x + 3, y + 7, x + 7, y + 8, handleColor);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.USAGE, Component.literal("Mage Inventory"));
    }
}
