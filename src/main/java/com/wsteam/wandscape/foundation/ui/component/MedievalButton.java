package com.wsteam.wandscape.foundation.ui.component;

import com.wsteam.wandscape.foundation.ui.skin.SkinRender;
import com.wsteam.wandscape.foundation.ui.theme.MedievalColors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
/**
 * Medieval-themed button using code-driven canvas rendering.
 * Provides dark obsidian gradient, chamfered gold borders,
 * glowing hover states, and precise typography.
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

        int state;
        if (!active) {
            state = 3;
        } else if (isHoveredOrFocused()) {
            state = 1;
        } else {
            state = 0;
        }

        SkinRender.drawButton(g, getX(), getY(), width, height, state);

        int textColor = !active ? MedievalColors.TEXT_DIM
                : (isHoveredOrFocused() ? MedievalColors.ACCENT_GOLD : MedievalColors.TEXT_WARM_WHITE);

        var font = Minecraft.getInstance().font;
        int textY = getY() + (height - font.lineHeight) / 2 + 1;
        g.drawCenteredString(font, getMessage(),
                getX() + width / 2, textY, textColor);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
