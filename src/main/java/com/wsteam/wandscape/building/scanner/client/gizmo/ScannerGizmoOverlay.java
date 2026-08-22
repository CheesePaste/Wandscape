package com.wsteam.wandscape.building.scanner.client.gizmo;

import com.wsteam.wandscape.building.data.BlockOffset;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.ui.I18n;
import com.wsteam.wandscape.shared.ui.theme.MedievalColors;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/**
 * Native HUD Overlay Sidebar for the 3D Building Scanner Gizmo Visual Adjuster.
 * Renders on top of the world view without blocking standard mouse look.
 */
public final class ScannerGizmoOverlay {
    private static final String TAG = "ScannerGizmoOverlay";
    private static boolean registered = false;

    // Sidebar dimensions (GUI coordinates)
    private static final int PANEL_W = 200;
    private static final int PANEL_H = 224;
    private static final int PAD = 8;

    private ScannerGizmoOverlay() {}

    public static void register() {
        if (registered) return;
        registered = true;
        NeoForge.EVENT_BUS.addListener(RenderGuiEvent.Post.class, ScannerGizmoOverlay::onRenderGui);
        Log.info(TAG, "ScannerGizmoOverlay registered");
    }

    public static boolean isMouseOverPanel(double mx, double my) {
        if (!ScannerGizmoState.isActive()) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getWindow() == null) return false;
        int screenW = mc.getWindow().getGuiScaledWidth();
        int panelX = screenW - PANEL_W - 10;
        int panelY = 10;
        return mx >= panelX && mx <= panelX + PANEL_W && my >= panelY && my <= panelY + PANEL_H;
    }

    private static void onRenderGui(RenderGuiEvent.Post event) {
        if (!ScannerGizmoState.isActive()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.getWindow() == null) return;

        GuiGraphics gui = event.getGuiGraphics();
        Font font = mc.font;

        double[] mxArr = new double[1], myArr = new double[1];
        GLFW.glfwGetCursorPos(mc.getWindow().getWindow(), mxArr, myArr);
        double scale = mc.getWindow().getGuiScale();
        int mx = (int) (mxArr[0] / scale);
        int my = (int) (myArr[0] / scale);

        int screenW = mc.getWindow().getGuiScaledWidth();
        int panelX = screenW - PANEL_W - 10;
        int panelY = 10;

        // ── Main Sidebar Panel ──
        renderPanel(gui, font, panelX, panelY, PANEL_W, PANEL_H, mx, my);

        // ── Top Toast Message ──
        renderToast(gui, font, screenW);
    }

    private static void renderPanel(GuiGraphics gui, Font font, int x, int y, int w, int h, int mx, int my) {
        // Dark medieval background with gradient
        gui.fillGradient(x, y, x + w, y + h, 0xEE1E120A, 0xEE120804);

        // Golden glow border
        gui.fill(x, y, x + w, y + 1, 0xCCFFD700);
        gui.fill(x, y + h - 1, x + w, y + h, 0xCCFFD700);
        gui.fill(x, y, x + 1, y + h, 0xCCFFD700);
        gui.fill(x + w - 1, y, x + w, y + h, 0xCCFFD700);

        int curY = y + 6;

        // Header Title
        String title = I18n.string("gui.wandscape.gizmo.title", "📐 3D 可视化调整 (Gizmo)");
        gui.drawString(font, title, x + PAD, curY, MedievalColors.BORDER_GOLD);
        curY += 12;

        String subtitle = I18n.string("gui.wandscape.gizmo.subtitle", "建筑 3D 包围盒实时编辑");
        gui.drawString(font, subtitle, x + PAD, curY, MedievalColors.TEXT_MUTED);
        curY += 12;

        // Separator
        gui.fill(x + PAD, curY, x + w - PAD, curY + 1, 0x55806848);
        curY += 5;

        // ── Anchor Switcher (MIN vs MAX) ──
        String anchorLabel = I18n.string("gui.wandscape.gizmo.anchor_label", "编辑锚点 (Tab 切换):");
        gui.drawString(font, anchorLabel, x + PAD, curY, MedievalColors.TEXT_WARM_WHITE);
        curY += 11;

        int btnW = (w - PAD * 2 - 4) / 2;
        boolean minActive = ScannerGizmoState.getSelectedAnchor() == ScannerGizmoState.Anchor.MIN;
        boolean maxActive = ScannerGizmoState.getSelectedAnchor() == ScannerGizmoState.Anchor.MAX;

        String btnMin = I18n.string("gui.wandscape.gizmo.anchor_min", "🔵 Min 角点");
        String btnMax = I18n.string("gui.wandscape.gizmo.anchor_max", "🟡 Max 角点");

        drawButton(gui, font, x + PAD, curY, btnW, 16, btnMin, minActive, isInRect(mx, my, x + PAD, curY, btnW, 16), 0xFF00E5FF);
        drawButton(gui, font, x + PAD + btnW + 4, curY, btnW, 16, btnMax, maxActive, isInRect(mx, my, x + PAD + btnW + 4, curY, btnW, 16), 0xFFFFD700);
        curY += 20;

        // ── Boundary Size & Volume Badge ──
        int bw = ScannerGizmoState.getWidth();
        int bh = ScannerGizmoState.getHeight();
        int bd = ScannerGizmoState.getDepth();
        long bVol = ScannerGizmoState.getVolume();

        gui.fill(x + PAD, curY, x + w - PAD, curY + 22, 0x66000000);
        gui.drawString(font, I18n.string("gui.wandscape.scanner.size_format", "尺寸: %d × %d × %d", bw, bh, bd), x + PAD + 4, curY + 3, MedievalColors.BORDER_GOLD);
        gui.drawString(font, I18n.string("gui.wandscape.scanner.vol_format", "体积: %,d 方块格", bVol), x + PAD + 4, curY + 12, MedievalColors.TEXT_WARM_WHITE);
        curY += 26;

        // ── Stepper Adjusters for active anchor ──
        ScannerGizmoState.Anchor anchor = ScannerGizmoState.getSelectedAnchor();
        BlockOffset off = (anchor == ScannerGizmoState.Anchor.MIN) ? ScannerGizmoState.getCurrentMin() : ScannerGizmoState.getCurrentMax();

        String stepperTitle = I18n.string("gui.wandscape.gizmo.stepper_title", "坐标微调 (%s):", anchor == ScannerGizmoState.Anchor.MIN ? "Min" : "Max");
        gui.drawString(font, stepperTitle, x + PAD, curY, MedievalColors.TEXT_MUTED);
        curY += 11;

        drawStepperRow(gui, font, x + PAD, curY, w - PAD * 2, "X", off.x(), mx, my, 0xFFFF6060);
        curY += 16;
        drawStepperRow(gui, font, x + PAD, curY, w - PAD * 2, "Y", off.y(), mx, my, 0xFF60FF60);
        curY += 16;
        drawStepperRow(gui, font, x + PAD, curY, w - PAD * 2, "Z", off.z(), mx, my, 0xFF6080FF);
        curY += 18;

        // ── Help Text ──
        String helpMouse = I18n.string("gui.wandscape.gizmo.help_mouse", "🖱️ 右键拖动视角 | 左键拖轴");
        gui.drawString(font, helpMouse, x + PAD, curY, MedievalColors.TEXT_DIM);
        curY += 10;

        String helpKeys = I18n.string("gui.wandscape.gizmo.help_keys", "⌨️ Enter 保存 | Esc 还原");
        gui.drawString(font, helpKeys, x + PAD, curY, MedievalColors.TEXT_DIM);
        curY += 14;

        // ── Action Buttons (Confirm / Cancel) ──
        int actBtnW = (w - PAD * 2 - 4) / 2;
        boolean confHover = isInRect(mx, my, x + PAD, curY, actBtnW, 18);
        boolean cancHover = isInRect(mx, my, x + PAD + actBtnW + 4, curY, actBtnW, 18);

        String btnConfirm = I18n.string("gui.wandscape.gizmo.confirm", "✓ 确定 (Enter)");
        String btnCancel = I18n.string("gui.wandscape.gizmo.cancel", "✕ 还原 (Esc)");

        drawButton(gui, font, x + PAD, curY, actBtnW, 18, btnConfirm, true, confHover, MedievalColors.BORDER_GOLD);
        drawButton(gui, font, x + PAD + actBtnW + 4, curY, actBtnW, 18, btnCancel, false, cancHover, MedievalColors.TEXT_MUTED);
    }

    private static void drawStepperRow(GuiGraphics gui, Font font, int x, int y, int w, String axis, int val, int mx, int my, int axisColor) {
        gui.drawString(font, axis + ":", x, y + 2, axisColor);

        // Buttons: [-5] [-1]   [val]   [+1] [+5]
        int btnW = 20;
        int stepY = y;
        int bx1 = x + 16;
        int bx2 = x + 38;
        int bx3 = x + w - 42;
        int bx4 = x + w - 20;

        drawMiniBtn(gui, font, bx1, stepY, btnW, 13, "-5", isInRect(mx, my, bx1, stepY, btnW, 13));
        drawMiniBtn(gui, font, bx2, stepY, btnW, 13, "-1", isInRect(mx, my, bx2, stepY, btnW, 13));

        String valStr = String.valueOf(val);
        int valX = x + (w - font.width(valStr)) / 2;
        gui.drawString(font, valStr, valX, y + 3, MedievalColors.TEXT_WARM_WHITE);

        drawMiniBtn(gui, font, bx3, stepY, btnW, 13, "+1", isInRect(mx, my, bx3, stepY, btnW, 13));
        drawMiniBtn(gui, font, bx4, stepY, btnW, 13, "+5", isInRect(mx, my, bx4, stepY, btnW, 13));
    }

    private static void drawButton(GuiGraphics gui, Font font, int x, int y, int w, int h, String text, boolean active, boolean hover, int activeColor) {
        int bg = active ? 0xDD3A2818 : (hover ? 0xBB2A1A10 : 0x881A1008);
        gui.fill(x, y, x + w, y + h, bg);

        int border = active ? activeColor : (hover ? 0xAAFFD700 : 0x44806848);
        gui.fill(x, y, x + w, y + 1, border);
        gui.fill(x, y + h - 1, x + w, y + h, border);
        gui.fill(x, y, x + 1, y + h, border);
        gui.fill(x + w - 1, y, x + w, y + h, border);

        int textCol = active ? activeColor : (hover ? MedievalColors.TEXT_WARM_WHITE : MedievalColors.TEXT_MUTED);
        gui.drawString(font, text, x + (w - font.width(text)) / 2, y + (h - font.lineHeight) / 2, textCol);
    }

    private static void drawMiniBtn(GuiGraphics gui, Font font, int x, int y, int w, int h, String text, boolean hover) {
        gui.fill(x, y, x + w, y + h, hover ? 0xDD4A3520 : 0x882A1C10);
        int border = hover ? MedievalColors.BORDER_GOLD : 0x55605040;
        gui.fill(x, y, x + w, y + 1, border);
        gui.fill(x, y + h - 1, x + w, y + h, border);
        gui.fill(x, y, x + 1, y + h, border);
        gui.fill(x + w - 1, y, x + w, y + h, border);

        int textCol = hover ? MedievalColors.TEXT_WARM_WHITE : MedievalColors.TEXT_MUTED;
        gui.drawString(font, text, x + (w - font.width(text)) / 2, y + (h - font.lineHeight) / 2, textCol);
    }

    private static void renderToast(GuiGraphics gui, Font font, int screenW) {
        String msg = ScannerGizmoState.getToastMessage();
        if (msg == null) return;

        int textW = font.width(msg);
        int pad = 10;
        int w = textW + pad * 2;
        int h = font.lineHeight + 8;
        int x = (screenW - w) / 2;
        int y = 14;

        gui.fillGradient(x, y, x + w, y + h, 0xEE2A1C14, 0xEE120804);
        int color = ScannerGizmoState.getToastColor();
        gui.fill(x, y, x + w, y + 1, color);
        gui.fill(x, y + h - 1, x + w, y + h, color);
        gui.fill(x, y, x + 1, y + h, color);
        gui.fill(x + w - 1, y, x + w, y + h, color);

        gui.drawString(font, msg, x + pad, y + 4, color);
    }

    public static boolean handleMouseClick(double mx, double my, int button) {
        if (!ScannerGizmoState.isActive() || button != 0) return false;

        Minecraft mc = Minecraft.getInstance();
        if (mc.getWindow() == null) return false;
        int screenW = mc.getWindow().getGuiScaledWidth();
        int panelX = screenW - PANEL_W - 10;
        int panelY = 10;

        if (!isInRect((int) mx, (int) my, panelX, panelY, PANEL_W, PANEL_H)) {
            return false;
        }

        int x = panelX;
        int y = panelY;
        int w = PANEL_W;

        int curY = y + 6 + 12 + 12 + 5 + 11;
        int btnW = (w - PAD * 2 - 4) / 2;

        // 1. Min / Max anchor switch
        if (isInRect((int) mx, (int) my, x + PAD, curY, btnW, 16)) {
            ScannerGizmoState.setSelectedAnchor(ScannerGizmoState.Anchor.MIN);
            return true;
        }
        if (isInRect((int) mx, (int) my, x + PAD + btnW + 4, curY, btnW, 16)) {
            ScannerGizmoState.setSelectedAnchor(ScannerGizmoState.Anchor.MAX);
            return true;
        }
        curY += 20 + 26 + 11;

        // 2. Stepper Adjusters
        boolean isMin = ScannerGizmoState.getSelectedAnchor() == ScannerGizmoState.Anchor.MIN;

        // X Stepper
        if (checkStepperClick((int) mx, (int) my, x + PAD, curY, w - PAD * 2, delta -> {
            if (isMin) ScannerGizmoState.adjustMin(delta, 0, 0);
            else ScannerGizmoState.adjustMax(delta, 0, 0);
        })) return true;
        curY += 16;

        // Y Stepper
        if (checkStepperClick((int) mx, (int) my, x + PAD, curY, w - PAD * 2, delta -> {
            if (isMin) ScannerGizmoState.adjustMin(0, delta, 0);
            else ScannerGizmoState.adjustMax(0, delta, 0);
        })) return true;
        curY += 16;

        // Z Stepper
        if (checkStepperClick((int) mx, (int) my, x + PAD, curY, w - PAD * 2, delta -> {
            if (isMin) ScannerGizmoState.adjustMin(0, 0, delta);
            else ScannerGizmoState.adjustMax(0, 0, delta);
        })) return true;
        curY += 18 + 10 + 14;

        // 3. Confirm / Cancel buttons
        int actBtnW = (w - PAD * 2 - 4) / 2;
        if (isInRect((int) mx, (int) my, x + PAD, curY, actBtnW, 18)) {
            ScannerGizmoState.confirm();
            return true;
        }
        if (isInRect((int) mx, (int) my, x + PAD + actBtnW + 4, curY, actBtnW, 18)) {
            ScannerGizmoState.cancel();
            return true;
        }

        return true;
    }

    private static boolean checkStepperClick(int mx, int my, int x, int y, int w, java.util.function.IntConsumer onDelta) {
        int btnW = 20;
        int bx1 = x + 16;
        int bx2 = x + 38;
        int bx3 = x + w - 42;
        int bx4 = x + w - 20;

        if (isInRect(mx, my, bx1, y, btnW, 13)) { onDelta.accept(-5); return true; }
        if (isInRect(mx, my, bx2, y, btnW, 13)) { onDelta.accept(-1); return true; }
        if (isInRect(mx, my, bx3, y, btnW, 13)) { onDelta.accept(1); return true; }
        if (isInRect(mx, my, bx4, y, btnW, 13)) { onDelta.accept(5); return true; }
        return false;
    }

    private static boolean isInRect(int mx, int my, int rx, int ry, int rw, int rh) {
        return mx >= rx && mx <= rx + rw && my >= ry && my <= ry + rh;
    }
}