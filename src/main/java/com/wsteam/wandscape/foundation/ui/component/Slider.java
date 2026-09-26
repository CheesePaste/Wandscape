package com.wsteam.wandscape.foundation.ui.component;

import com.wsteam.wandscape.foundation.ui.theme.MedievalColors;
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
        int old = this.value;
        this.value = Math.clamp(value, minValue, maxValue);
        if (this.value != old && onValueChanged != null) {
            onValueChanged.accept(this.value);
        }
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
        int trackX = getX();
        int trackY = getY() + LABEL_H + 2;
        int trackW = width;
        int trackH = TRACK_H;

        // Value label centered above track
        g.drawCenteredString(font, String.valueOf(value),
                trackX + trackW / 2, getY(), MedievalColors.TEXT_WARM_WHITE);

        // Track outline / inset border
        g.fill(trackX - 1, trackY - 1, trackX + trackW + 1, trackY + trackH + 1, 0xFF3D2A14);

        // Track — dark background
        g.fill(trackX, trackY, trackX + trackW, trackY + trackH, MedievalColors.SLIDER_TRACK);

        // Fill — blue progress proportional to value
        double ratio = maxValue > minValue
                ? (double) (value - minValue) / (maxValue - minValue) : 0;
        int fillW = (int) Math.round(trackW * ratio);
        if (fillW > 0) {
            g.fill(trackX, trackY, trackX + fillW, trackY + trackH, MedievalColors.SLIDER_FILL);
        }

        // Prominent draggable thumb / handle
        int thumbW = 8;
        int thumbH = trackH + 4; // 12px tall, extends 2px above and below track
        int thumbX = trackX + (int) Math.round((trackW - thumbW) * ratio);
        int thumbY = trackY - 2;

        boolean hovered = isMouseOver(mouseX, mouseY);
        int borderColor = hovered ? 0xFFE0C068 : 0xFF9A7A40;
        int bodyColor = hovered ? 0xFF4A3820 : 0xFF2A1A0A;
        int ridgeColor = hovered ? 0xFFFFFFFF : 0xFFC8A040;

        // Thumb outer border
        g.fill(thumbX, thumbY, thumbX + thumbW, thumbY + thumbH, borderColor);
        // Thumb body
        g.fill(thumbX + 1, thumbY + 1, thumbX + thumbW - 1, thumbY + thumbH - 1, bodyColor);
        // Vertical center grip ridge
        g.fill(thumbX + thumbW / 2, thumbY + 2, thumbX + thumbW / 2 + 1, thumbY + thumbH - 2, ridgeColor);
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
