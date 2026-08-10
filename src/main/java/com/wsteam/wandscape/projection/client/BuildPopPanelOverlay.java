package com.wsteam.wandscape.projection.client;

import com.wsteam.wandscape.shared.ui.panel.WandscapePanelOverlay;
import com.wsteam.wandscape.shared.ui.panel.WandscapePanelState;
import com.wsteam.wandscape.shared.ui.theme.WandscapeTheme;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;

/**
 * Right-side pop panel for Build Projection mode.
 * Displays target building coordinates (X, Y, Z), Lock/Unlock button, Rotation button,
 * and a Submit button (visible only when pinned).
 */
public final class BuildPopPanelOverlay {

    public static final int PANEL_W = 164;
    public static final int PANEL_H_UNPINNED = 112;
    public static final int PANEL_H_PINNED = 140;
    public static final int PANEL_RIGHT_MARGIN = 8;
    public static final int PANEL_TOP_MARGIN = WandscapePanelOverlay.TOP_BAR_H + 8;

    // Layout Y offsets from panelY
    private static final int HEADER_Y = 6;
    private static final int POS_Y = HEADER_Y + 18;
    private static final int STATUS_Y = POS_Y + 16;
    private static final int ROT_Y = STATUS_Y + 20;
    private static final int SUBMIT_Y = ROT_Y + 22;

    private static final int BTN_W = 58;
    private static final int BTN_H = 16;
    private static final int BTN_RIGHT_PAD = 8;

    private BuildPopPanelOverlay() {}

    public static boolean isActive() {
        Minecraft mc = Minecraft.getInstance();
        boolean rightDown = false;
        if (mc != null && mc.getWindow() != null && mc.getWindow().getWindow() != 0L) {
            rightDown = org.lwjgl.glfw.GLFW.glfwGetMouseButton(
                    mc.getWindow().getWindow(),
                    org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        }
        return WandscapePanelState.isPanelOpen()
                && ProjectionClientState.isProjecting()
                && WandscapePanelState.getActiveSubMode() == WandscapePanelState.SubMode.BUILD_PROJECTION
                && !rightDown;
    }

    public static int getPanelX(int screenW) {
        return screenW - PANEL_W - PANEL_RIGHT_MARGIN;
    }

    public static int getPanelY() {
        return PANEL_TOP_MARGIN;
    }

    private static int getPanelH() {
        return ProjectionClientState.isPinned() ? PANEL_H_PINNED : PANEL_H_UNPINNED;
    }

    public static void render(GuiGraphics g, Font font, int screenW, int screenH, double mouseX, double mouseY) {
        if (!isActive()) return;

        boolean isPinned = ProjectionClientState.isPinned();
        int panelX = getPanelX(screenW);
        int panelY = getPanelY();
        int panelH = getPanelH();

        // Panel background
        WandscapeTheme.drawRtsBox(g, panelX, panelY, PANEL_W, panelH, false, false);

        // Header: 建筑参数
        int y = panelY + HEADER_Y;
        g.fill(RenderType.guiOverlay(), panelX + 6, y + 12, panelX + PANEL_W - 6, y + 13, 0xFF3A3E47);
        g.drawString(font, "§6§l建筑参数", panelX + 8, y, 0xFFFFFFFF, false);

        // Position coordinates
        y = panelY + POS_Y;
        BlockPos pos = ProjectionClientState.getGhostPos();
        String posStr;
        if (pos != null) {
            posStr = String.format("X:%d Y:%d Z:%d", pos.getX(), pos.getY(), pos.getZ());
        } else {
            posStr = "X:-- Y:-- Z:--";
        }
        g.drawString(font, "§7位置: §f" + posStr, panelX + 8, y, 0xFFFFFFFF, false);

        // Status & Lock/Unlock Button
        y = panelY + STATUS_Y;
        String status = isPinned ? "§a[已锁定]" : "§e[瞄准中]";
        g.drawString(font, "§7状态: " + status, panelX + 8, y + 2, 0xFFFFFFFF, false);

        int btnLockX = panelX + PANEL_W - BTN_W - BTN_RIGHT_PAD;
        int btnLockY = y;
        boolean hoverLock = mouseX >= btnLockX && mouseX <= btnLockX + BTN_W && mouseY >= btnLockY && mouseY <= btnLockY + BTN_H;
        int lockBg = hoverLock ? 0xFF282C34 : 0xFF1C1F26;
        int lockAccent = isPinned ? 0xFF28A745 : 0xFFC8A040;
        g.fill(RenderType.guiOverlay(), btnLockX, btnLockY, btnLockX + BTN_W, btnLockY + BTN_H, 0, lockBg);
        g.fill(RenderType.guiOverlay(), btnLockX, btnLockY + BTN_H - 1, btnLockX + BTN_W, btnLockY + BTN_H, 0, lockAccent);
        g.drawCenteredString(font, isPinned ? "🔓 解锁" : "📌 锁定", btnLockX + BTN_W / 2, btnLockY + 4, hoverLock ? 0xFFFFFFFF : 0xFFCCCCCC);

        // Rotation angle & button
        y = panelY + ROT_Y;
        int rotDeg = ProjectionClientState.getRotationSteps() * 90;
        String rotStr = "§7朝向: §e" + rotDeg + "°";
        g.drawString(font, rotStr, panelX + 8, y + 3, 0xFFFFFFFF, false);

        int btnRotX = panelX + PANEL_W - BTN_W - BTN_RIGHT_PAD;
        int btnRotY = y;
        boolean hoverRot = mouseX >= btnRotX && mouseX <= btnRotX + BTN_W && mouseY >= btnRotY && mouseY <= btnRotY + BTN_H;
        int rotBg = hoverRot ? 0xFF282C34 : 0xFF1C1F26;
        g.fill(RenderType.guiOverlay(), btnRotX, btnRotY, btnRotX + BTN_W, btnRotY + btnRotY + BTN_H - btnRotY, 0, rotBg);
        g.fill(RenderType.guiOverlay(), btnRotX, btnRotY + BTN_H - 1, btnRotX + BTN_W, btnRotY + BTN_H, 0, 0xFF2B62C8);
        g.drawCenteredString(font, "↺ 旋转", btnRotX + BTN_W / 2, btnRotY + 4, hoverRot ? 0xFFFFFFFF : 0xFFCCCCCC);

        // Submit button (only when pinned)
        if (isPinned) {
            y = panelY + SUBMIT_Y;
            int submitW = PANEL_W - 16;
            int submitX = panelX + 8;
            int submitY = y;

            boolean hoverSubmit = mouseX >= submitX && mouseX <= submitX + submitW && mouseY >= submitY && mouseY <= submitY + BTN_H;
            int submitBg = hoverSubmit ? 0xFF1A4D2E : 0xFF14381F;
            g.fill(RenderType.guiOverlay(), submitX, submitY, submitX + submitW, submitY + BTN_H, 0, submitBg);
            g.fill(RenderType.guiOverlay(), submitX, submitY + BTN_H - 1, submitX + submitW, submitY + BTN_H, 0, 0xFF28A745);
            g.drawCenteredString(font, "✓ 提交施工", submitX + submitW / 2, submitY + 4, hoverSubmit ? 0xFFFFFFFF : 0xFFAADDBB);
        }
    }

    public static boolean isOverPanel(double mouseX, double mouseY, int screenW) {
        if (!isActive()) return false;
        int panelX = getPanelX(screenW);
        int panelY = getPanelY();
        int panelH = getPanelH();
        return mouseX >= panelX && mouseX <= panelX + PANEL_W && mouseY >= panelY && mouseY <= panelY + panelH;
    }

    public static boolean isOverLockButton(double mouseX, double mouseY, int screenW) {
        if (!isActive()) return false;
        int panelX = getPanelX(screenW);
        int panelY = getPanelY();
        int btnX = panelX + PANEL_W - BTN_W - BTN_RIGHT_PAD;
        int btnY = panelY + STATUS_Y;
        return mouseX >= btnX && mouseX <= btnX + BTN_W && mouseY >= btnY && mouseY <= btnY + BTN_H;
    }

    public static boolean isOverRotateButton(double mouseX, double mouseY, int screenW) {
        if (!isActive()) return false;
        int panelX = getPanelX(screenW);
        int panelY = getPanelY();
        int btnX = panelX + PANEL_W - BTN_W - BTN_RIGHT_PAD;
        int btnY = panelY + ROT_Y;
        return mouseX >= btnX && mouseX <= btnX + BTN_W && mouseY >= btnY && mouseY <= btnY + BTN_H;
    }

    public static boolean isOverSubmitButton(double mouseX, double mouseY, int screenW) {
        if (!isActive()) return false;
        if (!ProjectionClientState.isPinned()) return false;
        int panelX = getPanelX(screenW);
        int panelY = getPanelY();
        int submitW = PANEL_W - 16;
        int submitX = panelX + 8;
        int submitY = panelY + SUBMIT_Y;
        return mouseX >= submitX && mouseX <= submitX + submitW && mouseY >= submitY && mouseY <= submitY + BTN_H;
    }
}
