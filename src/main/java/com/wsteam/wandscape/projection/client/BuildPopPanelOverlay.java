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
 * angle readout, six axis nudge buttons (X/Y/Z ±1, move the ghost by one block,
 * auto-locking so the nudge sticks), and a Submit button (always shown —
 * construction does not require pinning first). Rotation itself is done with
 * left-click on the ghost.
 */
public final class BuildPopPanelOverlay {

    public static final int PANEL_W = 164;
    public static final int PANEL_H = 132;
    public static final int PANEL_RIGHT_MARGIN = 8;
    public static final int PANEL_TOP_MARGIN = WandscapePanelOverlay.TOP_BAR_H + 8;

    // Layout Y offsets from panelY
    private static final int HEADER_Y = 6;
    private static final int POS_Y = HEADER_Y + 18;
    private static final int STATUS_Y = POS_Y + 16;
    private static final int ROT_Y = STATUS_Y + 20;
    private static final int NUDGE_Y = ROT_Y + 18;
    private static final int SUBMIT_Y = NUDGE_Y + 24;

    private static final int BTN_W = 58;
    private static final int BTN_H = 16;
    private static final int BTN_RIGHT_PAD = 8;

    // 六个轴微调按钮（X-/X+/Y-/Y+/Z-/Z+，每步一格）几何；NUDGE_LABELS 下标与 nudgeDelta() 一一对应
    private static final int NUDGE_BTN_W = 22;
    private static final int NUDGE_BTN_H = 14;
    private static final int NUDGE_GAP = 3;
    private static final String[] NUDGE_LABELS = {"X-1", "X+1", "Y-1", "Y+1", "Z-1", "Z+1"};

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
                        ? I18n.name("gui.wandscape.buildpop.unlock", "解锁").getString()
                        : I18n.name("gui.wandscape.buildpop.lock", "锁定").getString(),
                btnLockX + BTN_W / 2, btnLockY + 4, hoverLock ? 0xFFFFFFFF : 0xFFCCCCCC);

        // Rotation angle (read-only — rotate the ghost with left-click)
        y = panelY + ROT_Y;
        int rotDeg = ProjectionClientState.getRotationSteps() * 90;
        g.drawString(font, I18n.name("gui.wandscape.buildpop.rotation", "§7朝向: §e%d°", rotDeg).getString(), panelX + 8, y + 3, 0xFFFFFFFF, false);

        // Axis nudge buttons (X-1 X+1 Y-1 Y+1 Z-1 Z+1) — move the ghost by one block along the axis
        y = panelY + NUDGE_Y;
        int nudgeX = panelX + 8;
        for (int i = 0; i < NUDGE_LABELS.length; i++) {
            boolean hover = mouseX >= nudgeX && mouseX <= nudgeX + NUDGE_BTN_W
                    && mouseY >= y && mouseY <= y + NUDGE_BTN_H;
            int nudgeBg = hover ? 0xFF282C34 : 0xFF1C1F26;
            g.fill(RenderType.guiOverlay(), nudgeX, y, nudgeX + NUDGE_BTN_W, y + NUDGE_BTN_H, 0, nudgeBg);
            g.fill(RenderType.guiOverlay(), nudgeX, y + NUDGE_BTN_H - 1, nudgeX + NUDGE_BTN_W, y + NUDGE_BTN_H, 0, 0xFF3A3E47);
            g.drawCenteredString(font, NUDGE_LABELS[i], nudgeX + NUDGE_BTN_W / 2, y + 3, hover ? 0xFFFFFFFF : 0xFFCCCCCC);
            nudgeX += NUDGE_BTN_W + NUDGE_GAP;
        }

        // Submit button (always shown — construction does not require pinning first)
        y = panelY + SUBMIT_Y;
        int submitW = PANEL_W - 16;
        int submitX = panelX + 8;
        int submitY = y;

        boolean hoverSubmit = mouseX >= submitX && mouseX <= submitX + submitW && mouseY >= submitY && mouseY <= submitY + BTN_H;
        int submitBg = hoverSubmit ? 0xFF1A4D2E : 0xFF14381F;
        g.fill(RenderType.guiOverlay(), submitX, submitY, submitX + submitW, submitY + BTN_H, 0, submitBg);
        g.fill(RenderType.guiOverlay(), submitX, submitY + BTN_H - 1, submitX + submitW, submitY + BTN_H, 0, 0xFF28A745);
        g.drawCenteredString(font, I18n.name("gui.wandscape.buildpop.submit", "提交施工").getString(), submitX + submitW / 2, submitY + 4, hoverSubmit ? 0xFFFFFFFF : 0xFFAADDBB);
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

    /**
     * 命中的微调按钮下标（0=X-1, 1=X+1, 2=Y-1, 3=Y+1, 4=Z-1, 5=Z+1），未命中返回 -1。
     * 下标与 {@link #nudgeDelta(int)}、{@link #NUDGE_LABELS} 一一对应。
     */
    public static int hitTestNudge(double mouseX, double mouseY, int screenW) {
        if (!isActive()) return -1;
        int panelX = getPanelX(screenW);
        int startX = panelX + 8;
        int y = getPanelY() + NUDGE_Y;
        if (mouseY < y || mouseY > y + NUDGE_BTN_H) return -1;
        for (int i = 0; i < NUDGE_LABELS.length; i++) {
            int x = startX + i * (NUDGE_BTN_W + NUDGE_GAP);
            if (mouseX >= x && mouseX <= x + NUDGE_BTN_W) return i;
        }
        return -1;
    }

    /** 微调按钮的位移增量 [dx, dy, dz]，下标与 {@link #hitTestNudge} 一致。 */
    public static int[] nudgeDelta(int index) {
        return switch (index) {
            case 0 -> new int[]{-1, 0, 0};
            case 1 -> new int[]{ 1, 0, 0};
            case 2 -> new int[]{ 0, -1, 0};
            case 3 -> new int[]{ 0, 1, 0};
            case 4 -> new int[]{ 0, 0, -1};
            case 5 -> new int[]{ 0, 0, 1};
            default -> new int[]{0, 0, 0};
        };
    }
}
