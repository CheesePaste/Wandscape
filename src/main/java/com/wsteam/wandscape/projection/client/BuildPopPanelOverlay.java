package com.wsteam.wandscape.projection.client;

import com.wsteam.wandscape.shared.ui.I18n;
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
 * Displays target building coordinates (X, Y, Z), Lock/Unlock button, rotation
 * angle readout, and a Submit button (always shown — construction does not
 * require pinning first). Rotation itself is done with left-click on the ghost.
 */
public final class BuildPopPanelOverlay {

    public static final int PANEL_W = 164;
    public static final int PANEL_H = 112;
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
        return PANEL_H;
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
        g.drawString(font, I18n.name("gui.wandscape.buildpop.title", "§6§l建筑参数").getString(), panelX + 8, y, 0xFFFFFFFF, false);

        // Position coordinates
        y = panelY + POS_Y;
        BlockPos pos = ProjectionClientState.getGhostPos();
        String posStr;
        if (pos != null) {
            posStr = String.format("X:%d Y:%d Z:%d", pos.getX(), pos.getY(), pos.getZ());
        } else {
            posStr = "X:-- Y:-- Z:--";
        }
        g.drawString(font, I18n.name("gui.wandscape.buildpop.position", "§7位置: §f%s", posStr).getString(), panelX + 8, y, 0xFFFFFFFF, false);

        // Status & Lock/Unlock Button
        y = panelY + STATUS_Y;
        String status = isPinned
                ? I18n.name("gui.wandscape.buildpop.locked", "§a[已锁定]").getString()
                : I18n.name("gui.wandscape.buildpop.aiming", "§e[瞄准中]").getString();
        g.drawString(font, I18n.name("gui.wandscape.buildpop.status", "§7状态: %s", status).getString(), panelX + 8, y + 2, 0xFFFFFFFF, false);

        int btnLockX = panelX + PANEL_W - BTN_W - BTN_RIGHT_PAD;
        int btnLockY = y;
        boolean hoverLock = mouseX >= btnLockX && mouseX <= btnLockX + BTN_W && mouseY >= btnLockY && mouseY <= btnLockY + BTN_H;
        int lockBg = hoverLock ? 0xFF282C34 : 0xFF1C1F26;
        int lockAccent = isPinned ? 0xFF28A745 : 0xFFC8A040;
        g.fill(RenderType.guiOverlay(), btnLockX, btnLockY, btnLockX + BTN_W, btnLockY + BTN_H, 0, lockBg);
        g.fill(RenderType.guiOverlay(), btnLockX, btnLockY + BTN_H - 1, btnLockX + BTN_W, btnLockY + BTN_H, 0, lockAccent);
        g.drawCenteredString(font, isPinned
                        ? I18n.name("gui.wandscape.buildpop.unlock", "🔓 解锁").getString()
                        : I18n.name("gui.wandscape.buildpop.lock", "📌 锁定").getString(),
                btnLockX + BTN_W / 2, btnLockY + 4, hoverLock ? 0xFFFFFFFF : 0xFFCCCCCC);

        // Rotation angle (read-only — rotate the ghost with left-click)
        y = panelY + ROT_Y;
        int rotDeg = ProjectionClientState.getRotationSteps() * 90;
        g.drawString(font, I18n.name("gui.wandscape.buildpop.rotation", "§7朝向: §e%d°", rotDeg).getString(), panelX + 8, y + 3, 0xFFFFFFFF, false);

        // Submit button (always shown — construction does not require pinning first)
        y = panelY + SUBMIT_Y;
        int submitW = PANEL_W - 16;
        int submitX = panelX + 8;
        int submitY = y;

        boolean hoverSubmit = mouseX >= submitX && mouseX <= submitX + submitW && mouseY >= submitY && mouseY <= submitY + BTN_H;
        int submitBg = hoverSubmit ? 0xFF1A4D2E : 0xFF14381F;
        g.fill(RenderType.guiOverlay(), submitX, submitY, submitX + submitW, submitY + BTN_H, 0, submitBg);
        g.fill(RenderType.guiOverlay(), submitX, submitY + BTN_H - 1, submitX + submitW, submitY + BTN_H, 0, 0xFF28A745);
        g.drawCenteredString(font, I18n.name("gui.wandscape.buildpop.submit", "✓ 提交施工").getString(), submitX + submitW / 2, submitY + 4, hoverSubmit ? 0xFFFFFFFF : 0xFFAADDBB);
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

    public static boolean isOverSubmitButton(double mouseX, double mouseY, int screenW) {
        if (!isActive()) return false;
        int panelX = getPanelX(screenW);
        int panelY = getPanelY();
        int submitW = PANEL_W - 16;
        int submitX = panelX + 8;
        int submitY = panelY + SUBMIT_Y;
        return mouseX >= submitX && mouseX <= submitX + submitW && mouseY >= submitY && mouseY <= submitY + BTN_H;
    }
}
