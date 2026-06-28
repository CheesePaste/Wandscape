package com.wsteam.wandscape.shared.ui.component;

import com.wsteam.wandscape.shared.ui.skin.SkinRender;
import com.wsteam.wandscape.shared.ui.skin.SkinSprite;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
/**
 * Help (?) button using the help_button sprite sheet.
 * Default native size is 30×32.
 */
public class HelpButton extends AbstractButton {

    private final MedievalButton.OnPress onPress;

    public HelpButton(int x, int y, MedievalButton.OnPress onPress) {
        super(x, y, SkinSprite.HELP_STATES[0].width(), SkinSprite.HELP_STATES[0].height(), Component.empty());
        this.onPress = onPress;
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

        if (active && isHoveredOrFocused()) {
            g.fill(getX() + 2, getY() + 2, getX() + width - 2, getY() + height - 2, 0x30FFFFFF);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.USAGE, Component.literal("Help button"));
    }
}
