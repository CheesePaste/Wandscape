package com.wsteam.wandscape.shared.ui.component;

import java.util.ArrayList;
import java.util.List;

import com.wsteam.wandscape.shared.ui.animation.MedievalAnimation;
import com.wsteam.wandscape.shared.ui.skin.SkinRender;
import com.wsteam.wandscape.shared.ui.skin.SkinSprite;
import com.wsteam.wandscape.shared.ui.util.RenderUtil;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Base screen for all Wandscape UIs.
 * Provides parchment background, gold border, corner decorations,
 * centered panel layout, and animation hooks.
 *
 * <p>Subclasses set {@code panelWidth} and {@code panelHeight} in their
 * constructor, then add widgets in {@link #init()}.
 */
public abstract class MedievalScreen extends Screen {

    /** Panel position — computed in init() as ((width-panelWidth)/2, (height-panelHeight)/2). */
    protected int leftPos, topPos;

    /** Panel content area size. Subclasses set these in the constructor. */
    protected final int panelWidth;
    protected final int panelHeight;

    /** Height of the title bar header in pixels. Default 20. */
    protected int headerHeight = 20;

    /** Optional title bar text. Empty or null = no title bar. */
    private String titleBarText;

    /** Active animations. Ticked and rendered each frame. */
    protected final List<MedievalAnimation> animations = new ArrayList<>();

    protected MedievalScreen(Component title, int panelWidth, int panelHeight) {
        super(title);
        this.panelWidth = panelWidth;
        this.panelHeight = panelHeight;
    }

    /** Set the title bar text. Pass null or empty to hide the title bar. */
    protected void setTitleBar(String text) {
        this.titleBarText = (text != null && !text.isEmpty()) ? text : null;
    }

    @Override
    protected void init() {
        this.leftPos = (this.width - panelWidth) / 2;
        this.topPos = (this.height - panelHeight) / 2;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Do NOT call super.render() — it calls renderBackground() again.
        // Instead, replicate the widget loop ourselves after our custom background.
        renderBackground(g, mouseX, mouseY, partialTick);

        // Title bar (drawn after background, before widgets)
        if (titleBarText != null) {
            SkinRender.drawHeader(g, leftPos, topPos, panelWidth, headerHeight);
            g.drawCenteredString(font, titleBarText,
                    leftPos + panelWidth / 2, topPos + (headerHeight - 8) / 2,
                    0xFFFFD700); // gold text
        }

        // Tick animations
        for (MedievalAnimation a : animations) {
            a.tick();
        }

        // Render widgets
        for (Renderable renderable : this.renderables) {
            renderable.render(g, mouseX, mouseY, partialTick);
        }

        // Render active animations on top
        animations.removeIf(MedievalAnimation::isComplete);
        for (MedievalAnimation a : animations) {
            a.render(g, mouseX, mouseY, partialTick);
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Dark translucent overlay over game world
        renderTransparentBackground(g);

        // 9-slice panel background from sprite sheet
        SkinRender.drawPanel9Slice(g, SkinSprite.PANEL_A, leftPos, topPos, panelWidth, panelHeight);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Register an animation. It will be ticked and rendered each frame until complete. */
    public void addAnimation(MedievalAnimation animation) {
        animations.add(animation);
    }
}
