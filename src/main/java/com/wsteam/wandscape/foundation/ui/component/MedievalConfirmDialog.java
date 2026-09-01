package com.wsteam.wandscape.foundation.ui.component;

import com.wsteam.wandscape.foundation.ui.I18n;
import com.wsteam.wandscape.foundation.ui.theme.MedievalColors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * 可复用的中世纪确认框（模态）。自包含：不依赖 widget 生命周期，宿主 Screen
 * 在渲染末尾调用 {@link #render}、在 mouseClicked / keyPressed 开头调用对应方法。
 *
 * <p>打开时挡住下层一切交互：点击背景 / 任意非 Enter / Esc 键都被吞掉。
 * 视觉：遮罩压暗背景 + <b>全不透明</b>深棕框体（金边 + 与面板同款紫色标题栏），
 * 高度随消息行数自适应、消息不截断。绘制复用同包 {@link MedievalScreen}
 * 的 protected 静态绘图助手（金边/玻璃框）。
 *
 * <p>接入方式：
 * <ul>
 *   <li>{@link MedievalScreen} 基类已内置（openConfirmDialog）——所有中世纪屏直接用。</li>
 *   <li>AbstractContainerScreen 等非中世纪屏需持有一个实例并手动接入（见 NpcScreen）。</li>
 * </ul>
 */
public final class MedievalConfirmDialog {

    private static final int BOX_W = 260;
    private static final int HEADER_H = 16;
    private static final int PAD = 16;
    private static final int BTN_W = 96;
    private static final int BTN_H = 18;
    private static final int BTN_GAP = 14;
    /** 遮罩 75% 黑：压暗背景保留环境感；框体不透明，不会透出下层文字。 */
    private static final int DIM_COLOR = 0xC0000000;
    // 标题栏：与各面板 header 同款紫色渐变
    private static final int HEADER_TOP = 0xFF502870;
    private static final int HEADER_BOTTOM = 0xFF1A0830;
    private static final int HEADER_ACCENT_TOP = 0xFFD4A840;
    private static final int HEADER_ACCENT_BOTTOM = 0xFF6A4020;
    // 框体：全不透明深棕渐变（alpha 必须为 FF，杜绝下层文字透出叠字）
    private static final int BOX_TOP = 0xFF2A1C12;
    private static final int BOX_BOTTOM = 0xFF140A06;

    private Component title;
    private Component message;
    private Runnable onConfirm;
    private boolean open;

    // 按钮矩形（render 时按屏幕居中计算，供 mouseClicked 命中检测）
    private int cancelX, cancelY, confirmX, confirmY;

    /** 以默认标题（「确认」）打开。 */
    public void open(Component message, Runnable onConfirm) {
        open(I18n.name("gui.wandscape.confirm.title", "确认"), message, onConfirm);
    }

    public void open(Component title, Component message, Runnable onConfirm) {
        this.title = title;
        this.message = message;
        this.onConfirm = onConfirm;
        this.open = true;
    }

    public void close() {
        this.open = false;
        this.title = null;
        this.message = null;
        this.onConfirm = null;
    }

    public boolean isOpen() {
        return open;
    }

    /** 用户确认：关闭并执行回调。返回是否消费（关闭前存在）。 */
    public boolean confirm() {
        if (!open) return false;
        Runnable action = onConfirm;
        close();
        if (action != null) action.run();
        return true;
    }

    /** 点击处理：返回 true 表示消费（确认框打开时所有点击都吞掉，阻止下层交互）。 */
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!open) return false;
        if (button == 0) {
            if (isInRect(mouseX, mouseY, confirmX, confirmY, BTN_W, BTN_H)) {
                return confirm();
            }
            if (isInRect(mouseX, mouseY, cancelX, cancelY, BTN_W, BTN_H)) {
                close();
                return true;
            }
        }
        return true; // 点背景同样吞掉
    }

    /** 键盘处理：Esc 取消、Enter 确认，其余按键吞掉（避免透传到下层输入框）。 */
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!open) return false;
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            confirm();
            return true;
        }
        return true;
    }

    /** 渲染确认框（最顶层）。{@code screenW/screenH} 为整个屏幕尺寸。 */
    public void render(GuiGraphics g, int screenW, int screenH, int mouseX, int mouseY) {
        if (!open) return;
        Font font = Minecraft.getInstance().font;

        // 置于最高渲染层（Z=500），压过 3D 实体（Z=50）、浮动物品（Z=232）及下层所有 UI 控件与文本
        g.pose().pushPose();
        g.pose().translate(0.0F, 0.0F, 500.0F);

        // 遮罩：走 fillGradient（与原版 renderTransparentBackground 同路径）全屏压暗
        g.fillGradient(0, 0, screenW, screenH, DIM_COLOR, DIM_COLOR);

        List<net.minecraft.util.FormattedCharSequence> lines = font.split(message, BOX_W - PAD * 2);
        int textH = lines.size() * (font.lineHeight + 2) - 2;
        int boxH = HEADER_H + 12 + textH + 12 + BTN_H + 12;

        int bx = (screenW - BOX_W) / 2;
        int by = (screenH - boxH) / 2;

        g.fillGradient(bx, by, bx + BOX_W, by + boxH, BOX_TOP, BOX_BOTTOM);
        MedievalScreen.drawGlowBorder(g, bx, by, BOX_W, boxH, MedievalColors.BORDER_GOLD);

        // 标题栏（与面板 header 同款：紫渐变 + 金分隔线 + 左侧金条）
        g.fillGradient(bx + 1, by + 1, bx + BOX_W - 1, by + HEADER_H, HEADER_TOP, HEADER_BOTTOM);
        g.fill(bx + 1, by + HEADER_H, bx + BOX_W - 1, by + HEADER_H + 1, MedievalColors.BORDER_GOLD);
        g.fillGradient(bx + 1, by + 1, bx + 4, by + HEADER_H, HEADER_ACCENT_TOP, HEADER_ACCENT_BOTTOM);
        g.drawString(font, title, bx + 8, by + (HEADER_H - font.lineHeight) / 2 + 1,
                MedievalColors.TEXT_WARM_WHITE);

        // 消息左对齐（多行更易读）
        int lineY = by + HEADER_H + 12;
        for (net.minecraft.util.FormattedCharSequence line : lines) {
            g.drawString(font, line, bx + PAD, lineY, MedievalColors.TEXT_WARM_WHITE);
            lineY += font.lineHeight + 2;
        }

        // 底部按钮：取消（左） / 确认（右）
        int btnY = by + boxH - BTN_H - 12;
        int totalW = BTN_W * 2 + BTN_GAP;
        cancelX = bx + (BOX_W - totalW) / 2;
        confirmX = cancelX + BTN_W + BTN_GAP;
        cancelY = confirmY = btnY;

        boolean cancHov = isInRect(mouseX, mouseY, cancelX, cancelY, BTN_W, BTN_H);
        boolean confHov = isInRect(mouseX, mouseY, confirmX, confirmY, BTN_W, BTN_H);

        drawButton(g, font, cancelX, btnY, I18n.name("gui.wandscape.confirm.cancel", "取消"), cancHov, false);
        drawButton(g, font, confirmX, btnY, I18n.name("gui.wandscape.confirm.ok", "确认"), confHov, true);

        // 立即收批并恢复 Pose 栈
        g.flush();
        g.pose().popPose();
    }

    private static void drawButton(GuiGraphics g, Font font, int x, int y,
                                   Component label, boolean hovered, boolean confirm) {
        int bgTop = confirm
                ? (hovered ? MedievalColors.BUTTON_BG_HOVER : 0xFF3A2818)
                : (hovered ? MedievalColors.BUTTON_BG_HOVER : 0xFF2A1E18);
        int bgBottom = confirm
                ? (hovered ? MedievalColors.PANEL_TITLE_BG : 0xFF1E100A)
                : (hovered ? MedievalColors.PANEL_TITLE_BG : 0xFF140C08);
        int borderColor = confirm
                ? (MedievalColors.BORDER_GOLD)
                : (hovered ? MedievalColors.BORDER_GOLD : MedievalColors.BORDER_GOLD_DARK);

        g.fillGradient(x, y, x + BTN_W, y + BTN_H, bgTop, bgBottom);
        MedievalScreen.drawGlowBorder(g, x, y, BTN_W, BTN_H, borderColor);

        int color = confirm
                ? (hovered ? MedievalColors.ACCENT_GOLD : MedievalColors.TEXT_WARM_WHITE)
                : (hovered ? MedievalColors.TEXT_WARM_WHITE : MedievalColors.TEXT_MUTED);
        g.drawCenteredString(font, label, x + BTN_W / 2, y + (BTN_H - font.lineHeight) / 2, color);
    }

    private static boolean isInRect(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
