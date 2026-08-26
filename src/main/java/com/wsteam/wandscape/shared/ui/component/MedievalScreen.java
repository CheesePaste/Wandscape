package com.wsteam.wandscape.shared.ui.component;

import java.util.ArrayList;
import java.util.List;

import com.wsteam.wandscape.shared.ui.I18n;
import com.wsteam.wandscape.shared.ui.ReplayProtectedScreen;
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
public abstract class MedievalScreen extends Screen implements ReplayProtectedScreen {

    protected int leftPos, topPos;
    protected final int panelWidth;
    protected final int panelHeight;
    protected int headerHeight = 22;
    protected Component titleBarText;
    protected int titleXOffset = 10;
    protected final List<MedievalAnimation> animations = new ArrayList<>();

    // ── Reusable confirmation dialog (rendered above everything when open) ──
    protected final MedievalConfirmDialog confirmDialog = new MedievalConfirmDialog();

    /** Open the built-in confirm dialog; on confirm the given action runs. */
    protected void openConfirmDialog(Component message, Runnable onConfirm) {
        confirmDialog.open(message, onConfirm);
    }

    // ── Building creator footer ──
    /** Vertical space reserved at the bottom for the creator label (subclasses use it in layout math). */
    protected static final int CREATOR_FOOTER_H = 24;
    private String buildingCreator = "";

    /** Set the building designer's name to show at the bottom-left of the panel. */
    public void setCreator(String creator) {
        this.buildingCreator = creator != null ? creator : "";
    }

    /** Draw the creator label at the bottom-left at the default font size. */
    protected void renderCreatorFooter(GuiGraphics g) {
        if (buildingCreator.isBlank()) return;
        String text = I18n.name("gui.wandscape.common.creator_label", "Creator").getString()
                + ": " + buildingCreator;
        g.drawString(font, text, leftPos + 16, topPos + panelHeight - CREATOR_FOOTER_H,
                MedievalColors.TEXT_DIM);
    }

    // ── Built-in close button ──
    protected boolean showCloseButton;
    protected int closeBtnX, closeBtnY, closeBtnW = 18, closeBtnH = 14;
    protected int closeBtnState;

    // ── Built-in help button & document ──
    protected boolean showHelpButton;
    protected String helpDocumentPath;
    protected HelpButton helpButton;

    // ── Glass panel gradient (Dark opaque medieval theme) ──
    private static final int GLASS_TOP       = 0xF5261A10;
    private static final int GLASS_BOTTOM    = 0xF5120804;
    private static final int GLASS_BOX_TOP    = 0xDD3A2818;
    private static final int GLASS_BOX_BOTTOM = 0xDD1E100A;

    // ── Transient feedback toast (drawn over the screen, does not resize the panel) ──
    private static final long FEEDBACK_DURATION_MS = 3000L;
    private Component feedback;
    private int feedbackColor;
    private long feedbackExpireTick;

    /** Show a transient message at the top-center of the screen for ~3s. */
    public void showFeedback(Component message, int color) {
        this.feedback = message;
        this.feedbackColor = color;
        this.feedbackExpireTick = System.currentTimeMillis() + FEEDBACK_DURATION_MS;
    }

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
        // Confirm dialog open: swallow everything (Esc cancel / Enter confirm handled inside).
        if (confirmDialog.isOpen()) {
            return confirmDialog.keyPressed(keyCode, scanCode, modifiers);
        }
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

        renderContent(g, mouseX, mouseY, partialTick);

        for (Renderable renderable : this.renderables) {
            renderable.render(g, mouseX, mouseY, partialTick);
        }

        animations.removeIf(MedievalAnimation::isComplete);
        for (MedievalAnimation a : animations) {
            a.render(g, mouseX, mouseY, partialTick);
        }

        renderCreatorFooter(g);
        renderFeedback(g);

        renderForeground(g, mouseX, mouseY, partialTick);

        if (confirmDialog.isOpen()) {
            confirmDialog.render(g, width, height, mouseX, mouseY);
        }
    }

    /** Hook for drawing screen content (cards, background frames, labels) behind widgets. */
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY, float partialTick) {}

    /** Hook for drawing foreground elements (tooltips, overlays) in front of widgets. */
    protected void renderForeground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {}

    /** Draw the transient feedback toast, if any, at the top-center of the screen. */
    protected void renderFeedback(GuiGraphics g) {
        if (feedback == null) return;
        if (System.currentTimeMillis() > feedbackExpireTick) {
            feedback = null;
            return;
        }
        int textW = font.width(feedback);
        int pad = 8;
        int w = textW + pad * 2;
        int h = font.lineHeight + 6;
        int x = (this.width - w) / 2;
        int y = Math.max(6, topPos - h - 3);

        // Dark medieval box with colored glow border
        g.fillGradient(x, y, x + w, y + h, 0xEE2A1C14, 0xEE120804);
        int borderCol = (feedbackColor & 0x00FFFFFF) | 0xDD000000;
        g.fill(x, y, x + w, y + 1, borderCol);
        g.fill(x, y + h - 1, x + w, y + h, borderCol);
        g.fill(x, y, x + 1, y + h, borderCol);
        g.fill(x + w - 1, y, x + w, y + h, borderCol);

        g.drawString(font, feedback, x + pad, y + (h - font.lineHeight) / 2, feedbackColor);
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
        // Confirm dialog open: it consumes all clicks, blocking the screen behind.
        if (confirmDialog.isOpen()) {
            return confirmDialog.mouseClicked(mouseX, mouseY, button);
        }
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
