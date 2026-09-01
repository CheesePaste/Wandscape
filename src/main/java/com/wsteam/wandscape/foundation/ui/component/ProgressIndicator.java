package com.wsteam.wandscape.foundation.ui.component;

import com.wsteam.wandscape.foundation.ui.skin.SkinRender;
import com.wsteam.wandscape.foundation.ui.theme.MedievalColors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
/**
 * Horizontal progress bar with gold fill on dark track.
 * Progress is a float from 0.0 to 1.0.
 * Optionally displays a text label (e.g. "60%" or "3s remaining").
 */
public class ProgressIndicator extends AbstractWidget {

    private float progress; // 0.0 .. 1.0
    private String label;
    private boolean showLabel;

    public ProgressIndicator(int x, int y, int width, int height, float initialProgress) {
        super(x, y, width, height, Component.empty());
        this.progress = Math.clamp(initialProgress, 0f, 1f);
    }

    public float getProgress() {
        return progress;
    }

    public void setProgress(float progress) {
        this.progress = Math.clamp(progress, 0f, 1f);
    }

    public void setLabel(String label) {
        this.label = label;
        this.showLabel = label != null && !label.isEmpty();
    }

    // Inset from bar edges to keep fill inside the decorative frame
    private static final int FILL_INSET_X = 4;
    private static final int FILL_INSET_Y = 4;

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (!visible) return;

        // Fill drawn FIRST — behind the bar sprite's transparent interior
        int innerW = width - 2 * FILL_INSET_X;
        int fillWidth = (int) (innerW * progress);
        if (fillWidth > 0) {
            g.fill(getX() + FILL_INSET_X, getY() + FILL_INSET_Y,
                    getX() + FILL_INSET_X + fillWidth, getY() + height - FILL_INSET_Y,
                    MedievalColors.PROGRESS_FILL);
        }

        // Bar sprite on TOP — its frame overlays the fill edges
        SkinRender.drawBar(g, getX(), getY(), width, height);

        // Label (centered over the bar)
        if (showLabel && label != null) {
            var font = Minecraft.getInstance().font;
            int labelY = getY() + (height - 9) / 2;
            g.drawCenteredString(font, label, getX() + width / 2, labelY, MedievalColors.TEXT_WARM_WHITE);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        int pct = (int) (progress * 100);
        output.add(NarratedElementType.USAGE,
                Component.literal("Progress " + pct + "%"));
    }
}
