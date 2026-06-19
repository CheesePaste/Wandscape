package com.wsteam.wandscape.shared.ui.animation;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Animation interface for future effects (particles, glow, transitions).
 * No implementations yet — reserved for phase 4-5.
 *
 * <p>Usage:
 * <pre>{@code
 * screen.addAnimation(new MedievalAnimation() {
 *     int ticks = 0;
 *     public boolean isComplete() { return ticks >= 60; }
 *     public void tick() { ticks++; }
 *     public void render(GuiGraphics g, int mx, int my, float pt) { ... }
 * });
 * }</pre>
 */
public interface MedievalAnimation {

    /** Whether this animation has finished and can be removed. */
    boolean isComplete();

    /** Called once per render tick. Advance state here. */
    void tick();

    /** Render the animation. Called after widgets. */
    void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick);
}
