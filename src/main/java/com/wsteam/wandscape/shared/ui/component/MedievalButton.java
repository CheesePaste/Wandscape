package com.wsteam.wandscape.shared.ui.component;

import com.wsteam.wandscape.shared.ui.skin.SkinRender;
import com.wsteam.wandscape.shared.ui.theme.MedievalColors;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * Medieval-themed button using sprite-sheet textures.
 * Falls back to programmatic rendering if skin assets are unavailable.
 */
public class MedievalButton extends AbstractButton {

    @FunctionalInterface
    public interface OnPress {
        void onPress();
    }

    private final OnPress onPress;

    public MedievalButton(int x, int y, int width, int height, Component message, OnPress onPress) {
        super(x, y, width, height, message);
        this.onPress = onPress;
    }

    @Override
    public void onPress() {
        if (onPress != null) {
            onPress.onPress();
        }
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (!visible) return;

        int state = active ? 0 : 3;
        int textColor = active ? MedievalColors.TEXT_WARM_WHITE : MedievalColors.TEXT_DIM;

        // Base sprite (always state 0 for active, 3 for disabled)
        SkinRender.drawButton(g, getX(), getY(), width, height, state);

        // Hover brightening — only the interior, leaving the sprite's border untouched
        if (active && isHoveredOrFocused()) {
            g.fill(getX() + 2, getY() + 2, getX() + width - 2, getY() + height - 2, 0x30FFFFFF);
            textColor = MedievalColors.ACCENT_GOLD;
        }

        int textY = getY() + (height - 9) / 2;
        g.drawCenteredString(Minecraft.getInstance().font, getMessage(),
                getX() + width / 2, textY, textColor);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
