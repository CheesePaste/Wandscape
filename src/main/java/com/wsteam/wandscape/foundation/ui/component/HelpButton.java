package com.wsteam.wandscape.foundation.ui.component;

import com.wsteam.wandscape.foundation.ui.skin.SkinRender;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * Help (?) button using code-driven gem badge rendering.
 * Native size is 14×14.
 */
public class HelpButton extends AbstractButton {

    private final MedievalButton.OnPress onPress;

    public HelpButton(int x, int y, MedievalButton.OnPress onPress) {
        this(x, y, 14, 14, onPress);
    }

    public HelpButton(int x, int y, int width, int height, MedievalButton.OnPress onPress) {
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
        if (!active) state = 3;
        else if (isHoveredOrFocused()) state = 1;
        else state = 0;

        SkinRender.drawHelpButton(g, getX(), getY(), width, height, state);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.USAGE, Component.literal("Help button"));
    }
}
