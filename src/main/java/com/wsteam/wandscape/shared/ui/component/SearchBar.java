package com.wsteam.wandscape.shared.ui.component;

import java.util.function.Consumer;

import com.wsteam.wandscape.shared.ui.skin.SkinRender;
import com.wsteam.wandscape.shared.ui.theme.MedievalColors;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * Search text input with custom medieval styling.
 * Wraps an {@link EditBox} with no vanilla border, drawing a themed background instead.
 */
public class SearchBar extends AbstractWidget {

    private final EditBox input;
    private final Consumer<String> onTextChanged;

    public SearchBar(int x, int y, int width, int height, String placeholder, Consumer<String> onTextChanged) {
        super(x, y, width, height, Component.empty());
        this.onTextChanged = onTextChanged;

        Font font = Minecraft.getInstance().font;
        // Header left cap is 32px — shift input past decorative caps
        this.input = new EditBox(font, x + INSET_L, y + 4, width - INSET_L - INSET_R, height - 8, Component.literal(placeholder));
        this.input.setBordered(false);
        this.input.setHint(Component.literal(placeholder));
        this.input.setTextColor(MedievalColors.ACCENT_GOLD);
        this.input.setTextColorUneditable(MedievalColors.TEXT_MUTED);
        this.input.setResponder(s -> {
            if (onTextChanged != null) onTextChanged.accept(s);
        });
    }

    private static final int INSET_L = 34;
    private static final int INSET_R = 35;

    public String getValue() {
        return input.getValue();
    }

    public void setValue(String value) {
        input.setValue(value);
    }

    @Override
    public void setX(int x) {
        super.setX(x);
        input.setX(x + INSET_L);
    }

    @Override
    public void setY(int y) {
        super.setY(y);
        input.setY(y + 4);
    }

    @Override
    public void setWidth(int w) {
        super.setWidth(w);
        input.setWidth(w - INSET_L - INSET_R);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return input.keyPressed(keyCode, scanCode, modifiers) || super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        return input.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return input.mouseClicked(mouseX, mouseY, button) || super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void setFocused(boolean focused) {
        super.setFocused(focused);
        input.setFocused(focused);
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (!visible) return;

        // Header sprite as decorative background (3-part, gold-trimmed)
        SkinRender.drawHeader(g, getX(), getY(), width, height);

        // Delegate to EditBox for text rendering
        input.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.USAGE, Component.literal("Search input"));
    }
}
