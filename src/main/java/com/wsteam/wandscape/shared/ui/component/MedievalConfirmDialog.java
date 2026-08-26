package com.wsteam.wandscape.shared.ui.component;

import java.util.List;

import com.wsteam.wandscape.shared.ui.I18n;
import com.wsteam.wandscape.shared.ui.theme.MedievalColors;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import org.lwjgl.glfw.GLFW;

/**
 * 可复用的中世纪确认框（模态）。自包含：不依赖 widget 生命周期，宿主 Screen
 * 在渲染末尾调用 {@link #render}、在 mouseClicked / keyPressed 开头调用对应方法。
 *
 * <p>打开时挡住下层一切交互：点击背景 / 任意非 Enter / Esc 键都被吞掉。
 * 确认框绘制复用同包 {@link MedievalScreen} 的 protected 静态绘图助手（金边/玻璃框）。
 *
 * <p>接入方式：
 * <ul>
 *   <li>{@link MedievalScreen} 基类已内置（openConfirmDialog）——所有中世纪屏直接用。</li>
 *   <li>AbstractContainerScreen 等非中世纪屏需持有一个实例并手动接入（见 NpcScreen）。</li>
 * </ul>
 */
public final class MedievalConfirmDialog {

    private static final int BOX_W = 280;
    private static final int BOX_H = 110;
    private static final int BTN_W = 100;
    private static final int BTN_H = 20;

    private Component message;
    private Runnable onConfirm;
    private boolean open;

    // 按钮矩形（render 时按屏幕居中计算，供 mouseClicked 命中检测）
    private int cancelX, cancelY, confirmX, confirmY;

    public void open(Component message, Runnable onConfirm) {
        this.message = message;
        this.onConfirm = onConfirm;
        this.open = true;
    }

    public void close() {
        this.open = false;
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

        // 全屏半透明遮罩，压暗背景
        g.fill(0, 0, screenW, screenH, 0x80000000);

        int bx = (screenW - BOX_W) / 2;
        int by = (screenH - BOX_H) / 2;

        // 玻璃渐变框 + 金边
        g.fillGradient(bx, by, bx + BOX_W, by + BOX_H, 0xF52A1C12, 0xF5100804);
        MedievalScreen.drawGlowBorder(g, bx, by, BOX_W, BOX_H, MedievalColors.BORDER_GOLD);

        // 消息自动换行，居中（FormattedCharSequence 直接绘制，避免类型转换歧义）
        List<net.minecraft.util.FormattedCharSequence> lines = font.split(message, BOX_W - 24);
        int lineY = by + 14;
        int maxLines = 3;
        for (int i = 0; i < lines.size() && i < maxLines; i++) {
            net.minecraft.util.FormattedCharSequence line = lines.get(i);
            g.drawString(font, line, bx + BOX_W / 2 - font.width(line) / 2,
                    lineY, MedievalColors.TEXT_WARM_WHITE);
            lineY += font.lineHeight + 2;
        }

        // 底部按钮：取消（左） / 确认（右）
        int btnY = by + BOX_H - BTN_H - 12;
        int totalW = BTN_W * 2 + 12;
        cancelX = bx + (BOX_W - totalW) / 2;
        confirmX = cancelX + BTN_W + 12;
        cancelY = confirmY = btnY;

        boolean cancHov = isInRect(mouseX, mouseY, cancelX, cancelY, BTN_W, BTN_H);
        boolean confHov = isInRect(mouseX, mouseY, confirmX, confirmY, BTN_W, BTN_H);

        drawButton(g, font, cancelX, btnY, I18n.name("gui.wandscape.confirm.cancel", "取消"), cancHov, false);
        drawButton(g, font, confirmX, btnY, I18n.name("gui.wandscape.confirm.ok", "确认"), confHov, true);
    }

    private static void drawButton(GuiGraphics g, Font font, int x, int y,
                                   Component label, boolean hovered, boolean confirm) {
        MedievalScreen.drawMinimalBox(g, x, y, BTN_W, BTN_H, confirm, hovered);
        int color = confirm
                ? (hovered ? MedievalColors.ACCENT_GOLD : MedievalColors.TEXT_WARM_WHITE)
                : (hovered ? MedievalColors.TEXT_WARM_WHITE : MedievalColors.TEXT_DIM);
        g.drawCenteredString(font, label, x + BTN_W / 2, y + (BTN_H - font.lineHeight) / 2, color);
    }

    private static boolean isInRect(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
