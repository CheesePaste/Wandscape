package com.wsteam.wandscape.shared.ui.component;

import java.util.ArrayList;
import java.util.List;

import com.wsteam.wandscape.shared.ui.animation.MedievalAnimation;
import com.wsteam.wandscape.shared.ui.skin.SkinRender;
import com.wsteam.wandscape.shared.ui.theme.MedievalColors;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Base screen for all Wandscape single-page UIs.
 * Provides gradient glass panel, glow border, purple header, and {@link MedievalColors} palette.
 */
public abstract class MedievalScreen extends Screen {

    protected int leftPos, topPos;
    protected final int panelWidth;
    protected final int panelHeight;
    protected int headerHeight = 22;
    protected Component titleBarText;
    protected int titleXOffset = 10;
    protected final List<MedievalAnimation> animations = new ArrayList<>();

    // ── Built-in close button ──
    protected boolean showCloseButton;
    protected int closeBtnX, closeBtnY, closeBtnW = 18, closeBtnH = 14;
    protected int closeBtnState;

    // ── Built-in help button & document ──
    protected boolean showHelpButton;
    protected String helpDocumentPath;
    protected HelpButton helpButton;

    // ── Glass panel gradient ──
    private static final int GLASS_TOP       = 0xBB483828;
    private static final int GLASS_BOTTOM    = 0xBB1E1410;
    private static final int GLASS_BOX_TOP    = 0xBB423020;
    private static final int GLASS_BOX_BOTTOM = 0xBB1C1008;

    protected MedievalScreen(Component title, int panelWidth, int panelHeight) {
        super(title);
        this.panelWidth = panelWidth;
        this.panelHeight = panelHeight;
    }

    protected void setTitleBar(Component title) {
        this.titleBarText = title;
    }

    @Override
    protected void init() {
        this.leftPos = (this.width - panelWidth) / 2;
        this.topPos = Math.max(2, (this.height - panelHeight) / 2);
        if (showCloseButton) {
            closeBtnX = leftPos + panelWidth - closeBtnW - 6;
            closeBtnY = topPos + (headerHeight - closeBtnH) / 2;
        }
        if (showHelpButton && helpDocumentPath != null) {
            int helpW = 14;
            int helpH = 14;
            int helpX = showCloseButton ? closeBtnX - helpW - 4 : leftPos + panelWidth - helpW - 6;
            int helpY = topPos + (headerHeight - helpH) / 2;
            helpButton = new HelpButton(helpX, helpY, helpW, helpH, this::openHelpDocument);
            addRenderableWidget(helpButton);
        }
    }

    public void openHelpDocument() {
        if (helpDocumentPath != null && minecraft != null) {
            String content = com.wsteam.wandscape.shared.ui.markdown.navigation.DocumentLoader.loadMarkdown(helpDocumentPath);
            var screen = new com.wsteam.wandscape.shared.ui.guide.GuideTestScreen(this, content, helpDocumentPath);
            minecraft.setScreen(screen);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Let a focused text box consume the key first (typing letters incl. H);
        // only open the help document when H is pressed outside any edit box.
        if (super.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        if (showHelpButton && helpDocumentPath != null
                && !(getFocused() instanceof EditBox box && box.canConsumeInput())
                && com.wsteam.wandscape.WandscapeClient.GUIDE_TOGGLE.matches(keyCode, scanCode)) {
            openHelpDocument();
            return true;
        }
        return false;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);

        if (titleBarText != null) {
            renderMinimalHeader(g);
        }

        if (showCloseButton) {
            renderCloseButton(g, mouseX, mouseY);
        }

        for (MedievalAnimation a : animations) a.tick();

        for (Renderable renderable : this.renderables) {
            renderable.render(g, mouseX, mouseY, partialTick);
        }

        animations.removeIf(MedievalAnimation::isComplete);
        for (MedievalAnimation a : animations) {
            a.render(g, mouseX, mouseY, partialTick);
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(g);

        // Gradient glass panel
        g.fillGradient(leftPos, topPos, leftPos + panelWidth, topPos + panelHeight,
                GLASS_TOP, GLASS_BOTTOM);
        // Glow border
        drawGlowBorder(g, leftPos, topPos, panelWidth, panelHeight,
                MedievalColors.BORDER_GOLD);
    }

    // ── MINIMAL header ──

    protected void renderMinimalHeader(GuiGraphics g) {
        int hx = leftPos + 1;
        int hy = topPos + 1;
        int hw = panelWidth - 2;

        g.fillGradient(hx, hy, hx + hw, hy + headerHeight,
                0xFF502870, 0xFF1A0830);

        // Gold bottom separator — 2-ring glow fade
        int sepY = hy + headerHeight;
        int sc = MedievalColors.BORDER_GOLD;
        g.fill(hx, sepY, hx + hw, sepY + 1, sc);
        g.fill(hx, sepY + 1, hx + hw, sepY + 2, (sc & 0x00FFFFFF) | 0x66000000);

        // Gold left accent — gradient
        g.fillGradient(hx, hy, hx + 3, hy + headerHeight,
                0xFFD4A840, 0xFF6A4020);

        if (titleBarText != null) {
            g.drawString(font, titleBarText, hx + titleXOffset,
                    hy + (headerHeight - font.lineHeight) / 2,
                    MedievalColors.TEXT_WARM_WHITE);
        }
    }

    // ── Close button ──

    protected void renderCloseButton(GuiGraphics g, int mouseX, int mouseY) {
        closeBtnState = isInRect(mouseX, mouseY, closeBtnX, closeBtnY, closeBtnW, closeBtnH) ? 1 : 0;
        SkinRender.drawCloseButton(g, closeBtnX, closeBtnY, closeBtnW, closeBtnH, closeBtnState);
    }

    protected boolean isCloseHit(double mouseX, double mouseY) {
        if (!showCloseButton) return false;
        return isInRect(mouseX, mouseY, closeBtnX, closeBtnY, closeBtnW, closeBtnH);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && isCloseHit(mouseX, mouseY)) {
            this.onClose();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    // ── Drawing helpers ──

    /**
     * Glow border — 2 rings fading from {@code color} at the edge
     * into transparency. Each ring uniform on all 4 sides — no corner seams.
     */
    protected static void drawGlowBorder(GuiGraphics g, int x, int y, int w, int h, int color) {
        int c0 = color;
        int c1 = (color & 0x00FFFFFF) | 0x66000000;

        // Ring 0 (outermost)
        g.fill(x, y, x + w, y + 1, c0);
        g.fill(x, y + h - 1, x + w, y + h, c0);
        g.fill(x, y, x + 1, y + h, c0);
        g.fill(x + w - 1, y, x + w, y + h, c0);

        // Ring 1 (fade)
        g.fill(x + 1, y + 1, x + w - 1, y + 2, c1);
        g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, c1);
        g.fill(x + 1, y + 1, x + 2, y + h - 1, c1);
        g.fill(x + w - 2, y + 1, x + w - 1, y + h - 1, c1);
    }

    /** Gradient box with glow border for tabs, cards, etc. */
    protected static void drawMinimalBox(GuiGraphics g, int x, int y, int w, int h,
                                         boolean active, boolean hovered) {
        if (active) {
            g.fillGradient(x, y, x + w, y + h, GLASS_BOX_TOP, GLASS_BOX_BOTTOM);
            drawGlowBorder(g, x, y, w, h, MedievalColors.BORDER_GOLD);
        } else if (hovered) {
            g.fillGradient(x, y, x + w, y + h,
                    MedievalColors.BUTTON_BG_HOVER, MedievalColors.PANEL_TITLE_BG);
            drawGlowBorder(g, x, y, w, h, MedievalColors.BORDER_GOLD_DARK);
        } else {
            g.fillGradient(x, y, x + w, y + h, 0x992A1E18, 0x991A0E08);
            g.fill(x, y, x + w, y + 1, MedievalColors.BORDER_GOLD_DARK);
            g.fill(x, y + h - 1, x + w, y + h, MedievalColors.BORDER_GOLD_DARK);
            g.fill(x, y, x + 1, y + h, MedievalColors.BORDER_GOLD_DARK);
            g.fill(x + w - 1, y, x + w, y + h, MedievalColors.BORDER_GOLD_DARK);
        }
    }

    /** Inset dark field with subtle inner shadow. */
    protected static void drawInsetField(GuiGraphics g, int x, int y, int w, int h) {
        g.fillGradient(x, y, x + w, y + h, 0x44000000, 0x33000000);
        g.fill(x, y, x + w, y + 1, 0x55000000);
        g.fill(x, y, x + 1, y + h, 0x55000000);
        g.fill(x, y + h - 1, x + w, y + h, 0x22FFFFFF);
        g.fill(x + w - 1, y, x + w, y + h, 0x22FFFFFF);
    }

    protected static boolean isInRect(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    public void addAnimation(MedievalAnimation animation) {
        animations.add(animation);
    }
}
