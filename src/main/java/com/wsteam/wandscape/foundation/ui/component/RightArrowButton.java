package com.wsteam.wandscape.foundation.ui.component;

import com.wsteam.wandscape.foundation.ui.skin.SkinRender;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
/**
 * Right arrow button using the right_arrow sprite sheet.
 * Default native size is 20×14.
 */
public class RightArrowButton extends AbstractButton {

    private final MedievalButton.OnPress onPress;

    public RightArrowButton(int x, int y, MedievalButton.OnPress onPress) {
        super(x, y, 20, 14, Component.empty());
        this.onPress = onPress;
    }

    public RightArrowButton(int x, int y, int width, int height, MedievalButton.OnPress onPress) {
        super(x, y, width, height, Component.empty());
        this.onPress = onPress;
    }

    @Override
    public void onPress() {
        if (onPress != null) onPress.onPress();
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (!visible) return;

        int state;
        if (!active) state = 2;
        else if (isHoveredOrFocused()) state = 1;
        else state = 0;

        SkinRender.drawRightArrow(g, getX(), getY(), state);

        if (active && isHoveredOrFocused()) {
            g.fill(getX() + 1, getY() + 1, getX() + width - 1, getY() + height - 1, 0x30FFFFFF);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.USAGE, Component.literal("Right arrow button"));
    }
}
