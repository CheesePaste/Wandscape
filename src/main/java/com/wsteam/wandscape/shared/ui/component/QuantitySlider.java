package com.wsteam.wandscape.shared.ui.component;

import java.util.function.IntConsumer;

import com.wsteam.wandscape.shared.ui.theme.MedievalColors;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * Integer quantity slider with themed gold track and handle.
 * Range: [min, max]. Displays current value above the track.
 */
public class QuantitySlider extends AbstractWidget {

    private final int minValue;
    private int maxValue;
    private int value;
    private final IntConsumer onValueChanged;

    private static final int HANDLE_WIDTH = 8;
    private static final int TRACK_HEIGHT = 6;
    private static final int LABEL_HEIGHT = 10;

    public QuantitySlider(int x, int y, int width, int minValue, int maxValue,
                          int initialValue, IntConsumer onValueChanged) {
        super(x, y, width, LABEL_HEIGHT + TRACK_HEIGHT + 4, Component.empty());
        this.minValue = minValue;
        this.maxValue = maxValue;
        this.value = initialValue;
        this.onValueChanged = onValueChanged;
    }

    public int getValue() {
        return value;
    }

    public void setValue(int value) {
        this.value = Math.clamp(value, minValue, maxValue);
    }

    public void setMax(int newMax) {
        this.maxValue = Math.max(minValue, newMax);
        if (this.value > this.maxValue) {
            this.value = this.maxValue;
        }
    }

    public int getMax() {
        return maxValue;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (!visible) return;

        var font = Minecraft.getInstance().font;
        int trackY = getY() + LABEL_HEIGHT + 2;
        int trackWidth = width - HANDLE_WIDTH;

        // Value label centered above track
        String label = String.valueOf(value);
        g.drawCenteredString(font, label, getX() + width / 2, getY(), MedievalColors.TEXT_WARM_WHITE);

        // Track background
        g.fill(getX() + HANDLE_WIDTH / 2, trackY,
                getX() + width - HANDLE_WIDTH / 2, trackY + TRACK_HEIGHT,
                MedievalColors.SLIDER_TRACK);

        // Filled portion
        double ratio = (double) (value - minValue) / (maxValue - minValue);
        int fillWidth = (int) (trackWidth * ratio);
        if (fillWidth > 0) {
            g.fill(getX() + HANDLE_WIDTH / 2, trackY,
                    getX() + HANDLE_WIDTH / 2 + fillWidth, trackY + TRACK_HEIGHT,
                    MedievalColors.SLIDER_FILL);
        }

        // Handle
        int handleX = getX() + HANDLE_WIDTH / 2 + (int) (trackWidth * ratio) - HANDLE_WIDTH / 2;
        g.fill(handleX, trackY - 1, handleX + HANDLE_WIDTH, trackY + TRACK_HEIGHT + 1,
                isHoveredOrFocused() ? MedievalColors.ACCENT_GOLD : MedievalColors.BORDER_GOLD);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || !active || button != 0) return false;
        if (isMouseOver(mouseX, mouseY)) {
            updateValueFromMouse(mouseX);
            return true;
        }
        return false;
    }

    @Override
    protected void onDrag(double mouseX, double mouseY, double dragX, double dragY) {
        updateValueFromMouse(mouseX);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 263) { // left arrow
            setValue(value - 1);
            if (onValueChanged != null) onValueChanged.accept(value);
            return true;
        }
        if (keyCode == 262) { // right arrow
            setValue(value + 1);
            if (onValueChanged != null) onValueChanged.accept(value);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void updateValueFromMouse(double mouseX) {
        double ratio = (mouseX - getX() - HANDLE_WIDTH / 2.0) / (double) (width - HANDLE_WIDTH);
        int newValue = minValue + (int) Math.round(ratio * (maxValue - minValue));
        newValue = Math.clamp(newValue, minValue, maxValue);
        if (newValue != value) {
            value = newValue;
            if (onValueChanged != null) onValueChanged.accept(value);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.USAGE,
                Component.literal("Slider value " + value + " of " + maxValue));
    }
}
