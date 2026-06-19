package com.wsteam.wandscape.shared.ui.component;

import java.util.function.Consumer;

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
        this.input = new EditBox(font, x + 2, y + 2, width - 4, height - 4, Component.literal(placeholder));
        this.input.setBordered(false);
        this.input.setHint(Component.literal(placeholder));
        this.input.setTextColor(MedievalColors.TEXT_WARM_WHITE);
        this.input.setTextColorUneditable(MedievalColors.TEXT_MUTED);
        this.input.setResponder(s -> {
            if (onTextChanged != null) onTextChanged.accept(s);
        });
    }

    public String getValue() {
        return input.getValue();
    }

    public void setValue(String value) {
        input.setValue(value);
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

        // Background
        g.fill(getX(), getY(), getX() + width, getY() + height, MedievalColors.PARCHMENT_DARK);
        g.renderOutline(getX(), getY(), width, height,
                input.isFocused() ? MedievalColors.ACCENT_GOLD : MedievalColors.BORDER_GOLD);

        // Delegate to EditBox for text rendering
        input.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.USAGE, Component.literal("Search input"));
    }
}
