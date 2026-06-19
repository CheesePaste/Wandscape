package com.wsteam.wandscape.shared.ui.component;

import com.wsteam.wandscape.shared.ui.skin.SkinRender;
import com.wsteam.wandscape.shared.ui.skin.SkinSprite;
import com.wsteam.wandscape.shared.ui.theme.MedievalColors;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Small square icon button. Supports both sprite-sheet icons (via
 * {@link SkinRender}) and character-based icons (e.g. "X") with
 * programmatic rendering as fallback.
 */
public class IconButton extends AbstractButton {

    private final MedievalButton.OnPress onPress;
    private final String iconChar;
    private final int iconColor;

    /** Optional sprite sheet for rendering. If null, uses programmatic rendering. */
    private final ResourceLocation spriteSheet;

    public IconButton(int x, int y, int size, String iconChar, int iconColor,
                      Component tooltip, MedievalButton.OnPress onPress) {
        this(x, y, size, iconChar, iconColor, tooltip, onPress, null);
    }

    /**
     * @param spriteSheet if non-null, render using this sprite sheet instead of
     *                    programmatic rendering (e.g. SkinSprite.CLOSE_BTN)
     */
    public IconButton(int x, int y, int size, String iconChar, int iconColor,
                      Component tooltip, MedievalButton.OnPress onPress,
                      ResourceLocation spriteSheet) {
        super(x, y, size, size, tooltip);
        this.iconChar = iconChar;
        this.iconColor = iconColor;
        this.onPress = onPress;
        this.spriteSheet = spriteSheet;
    }

    @Override
    public void onPress() {
        if (onPress != null) onPress.onPress();
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (!visible) return;

        if (spriteSheet != null) {
            renderSprite(g);
        } else {
            renderProgrammatic(g);
        }
    }

    private void renderSprite(GuiGraphics g) {
        int state;
        if (!active) state = 3;
        else if (isHoveredOrFocused()) state = 1;
        else state = 0;

        if (spriteSheet == SkinSprite.CLOSE_BTN || SkinSprite.CLOSE_BTN.equals(spriteSheet)) {
            SkinRender.drawCloseButton(g, getX(), getY(), width, height, state);
        } else {
            // Generic sprite rendering — scale to button size
            // Fallback to programmatic if sheet unknown
            renderProgrammatic(g);
        }
    }

    private void renderProgrammatic(GuiGraphics g) {
        int bgColor;
        int borderColor;
        int charColor;

        if (!active) {
            bgColor = MedievalColors.BUTTON_BG_DISABLED;
            borderColor = MedievalColors.TEXT_DIM;
            charColor = MedievalColors.TEXT_DIM;
        } else if (isHoveredOrFocused()) {
            bgColor = MedievalColors.BUTTON_BG_HOVER;
            borderColor = MedievalColors.ACCENT_GOLD;
            charColor = iconColor;
        } else {
            bgColor = MedievalColors.PARCHMENT_DARK;
            borderColor = MedievalColors.BORDER_GOLD_DARK;
            charColor = iconColor;
        }

        g.fill(getX(), getY(), getX() + width, getY() + height, bgColor);
        g.renderOutline(getX(), getY(), width, height, borderColor);

        var font = Minecraft.getInstance().font;
        int charW = font.width(iconChar);
        g.drawString(font, iconChar,
                getX() + (width - charW) / 2,
                getY() + (height - 9) / 2,
                charColor);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.USAGE, getMessage());
    }
}
