package com.wsteam.wandscape.shared.ui.component;

import com.wsteam.wandscape.shared.ui.theme.MedievalColors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;
/**
 * Minimal integer slider — solid track with pure-blue fill, no handle, no highlights.
 * Range: [min, max]. Value label centered above the track.
 */
public class Slider extends AbstractWidget {

    private int minValue;
    private int maxValue;
    private int value;
    private final IntConsumer onValueChanged;

    private static final int TRACK_H = 8;
    private static final int LABEL_H = 10;

    public Slider(int x, int y, int width, int minValue, int maxValue,
                  int initialValue, IntConsumer onValueChanged) {
        super(x, y, width, LABEL_H + TRACK_H + 4, Component.empty());
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

    /** Set both bounds of the slider range, clamping the current value into it. */
    public void setRange(int newMin, int newMax) {
        this.minValue = newMin;
        this.maxValue = Math.max(newMin, newMax);
        this.value = Math.clamp(value, minValue, maxValue);
    }

    // ── render ──

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (!visible) return;

        var font = Minecraft.getInstance().font;
        int trackY = getY() + LABEL_H + 2;

        // Value label centered above track
        g.drawCenteredString(font, String.valueOf(value),
                getX() + width / 2, getY(), MedievalColors.TEXT_WARM_WHITE);

        // Track — dark blue-black
        g.fill(getX(), trackY, getX() + width, trackY + TRACK_H, MedievalColors.SLIDER_TRACK);

        // Fill — pure blue, proportional to value
        double ratio = maxValue > minValue
                ? (double) (value - minValue) / (maxValue - minValue) : 0;
        int fillW = (int) (width * ratio);
        if (fillW > 0) {
            g.fill(getX(), trackY, getX() + fillW, trackY + TRACK_H, MedievalColors.SLIDER_FILL);
        }
    }

    // ── input ──

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
        double ratio = (mouseX - getX()) / (double) width;
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
