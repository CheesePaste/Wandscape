package com.wsteam.wandscape.content.building.projection.client;
import com.wsteam.wandscape.content.task.component.Position;

import com.wsteam.wandscape.foundation.ui.I18n;
import com.wsteam.wandscape.foundation.ui.panel.WandscapePanelOverlay;
import com.wsteam.wandscape.foundation.ui.panel.WandscapePanelState;
import com.wsteam.wandscape.foundation.ui.theme.WandscapeTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;

/**
 * Right-side pop panel for Build Projection mode.
 * Displays target building coordinates (X, Y, Z), Lock/Unlock button, rotation
 * angle readout, six axis nudge buttons (X/Y/Z ±1, move the ghost by one block,
 * auto-locking so the nudge sticks), a「建造清盒」toggle (is=clear whole boundary
 * box like the pre-overlap build, default; off=pure placement allowing nesting),
 * and a Submit button (always shown — construction does not require pinning
 * first). Rotation itself is done with left-click on the ghost. Kept compact so
 * the panel bottom never reaches the bottom building-selection bar.
 */
public final class BuildPopPanelOverlay {

    public static final int PANEL_W = 164;
    /** 比旧版高一行：底部那行是「左键旋转 · Enter 确认 · ALT+滚轮微调」的键位提示。 */
    public static final int PANEL_H = 128;
    public static final int PANEL_RIGHT_MARGIN = 8;
    public static final int PANEL_TOP_MARGIN = WandscapePanelOverlay.TOP_BAR_H + 2;

    // Layout Y offsets from panelY (compact vertical rhythm so the panel bottom stays
    // above the bottom building-selection bar)
    private static final int HEADER_Y = 3;
    private static final int POS_Y = HEADER_Y + 16;
    private static final int STATUS_Y = POS_Y + 13;
    private static final int ROT_Y = STATUS_Y + 17;
    private static final int NUDGE_Y = ROT_Y + 13;
    private static final int CLEAR_Y = NUDGE_Y + 16;
    private static final int SUBMIT_Y = CLEAR_Y + 17;
    private static final int HINT_Y = SUBMIT_Y + 17;

    /** 「旋转」按钮：落在朝向那行右侧，与左键共用同一个动作（键盘侧走左键，不再占独立键位）。 */
    private static final int ROTATE_BTN_W = 44;
    private static final int ROTATE_BTN_H = 14;

    private static final int BTN_W = 58;
    private static final int BTN_H = 16;
    private static final int BTN_RIGHT_PAD = 8;

    // 建造清盒开关按钮背景（是=绿 / 否=金）
    private static final int CLEAR_BG_ON = 0xFF1A4D2E;
    private static final int CLEAR_BG_ON_HOVER = 0xFF2A6B3E;
    private static final int CLEAR_BG_OFF = 0xFF3A3423;
    private static final int CLEAR_BG_OFF_HOVER = 0xFF4A4230;
    private static final int CLEAR_ACCENT_ON = 0xFF28A745;
    private static final int CLEAR_ACCENT_OFF = 0xFFC8A040;

    // 六个轴微调按钮（X-/X+/Y-/Y+/Z-/Z+，每步一格）几何；NUDGE_LABELS 下标与 nudgeDelta() 一一对应
    private static final int NUDGE_BTN_W = 22;
    private static final int NUDGE_BTN_H = 14;
    private static final int NUDGE_GAP = 3;
    private static final String[] NUDGE_LABELS = {"X-1", "X+1", "Y-1", "Y+1", "Z-1", "Z+1"};

    private BuildPopPanelOverlay() {}

    public static boolean isActive() {
        // 面板常驻：以前按住右键会让整块面板消失（拖拽定位时看不到坐标/朝向/状态），
        // 现在瞄准阶段自动跟随准心、不再需要按住右键，面板也就没有隐藏的理由。
        return WandscapePanelState.isPanelOpen()
                && ProjectionClientState.isProjecting()
                && WandscapePanelState.getActiveSubMode() == WandscapePanelState.SubMode.BUILD_PROJECTION;
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

        // 阶段按钮：瞄准「确认位置」→ 调整「定稿」→ 定稿「重新瞄准」（与 Enter、左键前进同源）
        boolean isLocked = ProjectionClientState.isLocked();
        y = panelY + STATUS_Y;
        String statusKey;
        String statusFallback;
        if (isLocked) {
            statusKey = "gui.wandscape.buildpop.locked";
            statusFallback = "§a[已定稿]";
        } else if (isPinned) {
            statusKey = "gui.wandscape.buildpop.adjusting";
            statusFallback = "§b[调整中]";
        } else {
            statusKey = "gui.wandscape.buildpop.aiming";
            statusFallback = "§e[瞄准中]";
        }
        g.drawString(font, I18n.name("gui.wandscape.buildpop.status", "§7状态: %s",
                I18n.name(statusKey, statusFallback).getString()).getString(),
                panelX + 8, y + 2, 0xFFFFFFFF, false);

        int btnLockX = panelX + PANEL_W - BTN_W - BTN_RIGHT_PAD;
        int btnLockY = y;
        boolean hoverLock = mouseX >= btnLockX && mouseX <= btnLockX + BTN_W && mouseY >= btnLockY && mouseY <= btnLockY + BTN_H;
        int lockBg = hoverLock ? 0xFF282C34 : 0xFF1C1F26;
        int lockAccent = isLocked ? 0xFF28A745 : (isPinned ? 0xFF42A5F5 : 0xFFC8A040);
        g.fill(RenderType.guiOverlay(), btnLockX, btnLockY, btnLockX + BTN_W, btnLockY + BTN_H, 0, lockBg);
        g.fill(RenderType.guiOverlay(), btnLockX, btnLockY + BTN_H - 1, btnLockX + BTN_W, btnLockY + BTN_H, 0, lockAccent);
        String stageKey = isLocked ? "gui.wandscape.buildpop.unlock"
                : (isPinned ? "gui.wandscape.buildpop.finalize" : "gui.wandscape.buildpop.confirm");
        String stageFallback = isLocked ? "重新瞄准" : (isPinned ? "定稿" : "确认位置");
        g.drawCenteredString(font, I18n.name(stageKey, stageFallback).getString(),
                btnLockX + BTN_W / 2, btnLockY + 4, hoverLock ? 0xFFFFFFFF : 0xFFCCCCCC);

        // Rotation angle + 「旋转」按钮（与左键同一动作；定稿后置灰不可用）
        y = panelY + ROT_Y;
        int rotDeg = ProjectionClientState.getRotationSteps() * 90;
        g.drawString(font, I18n.name("gui.wandscape.buildpop.rotation", "§7朝向: §e%s°", rotDeg).getString(), panelX + 8, y + 3, 0xFFFFFFFF, false);

        int rotateX = panelX + PANEL_W - ROTATE_BTN_W - BTN_RIGHT_PAD;
        int rotateY = y;
        boolean hoverRotate = mouseX >= rotateX && mouseX <= rotateX + ROTATE_BTN_W && mouseY >= rotateY && mouseY <= rotateY + ROTATE_BTN_H;
        int rotateBg = (!isLocked && hoverRotate) ? 0xFF282C34 : 0xFF1C1F26;
        int rotateFg = isLocked ? 0xFF5A5F66 : (hoverRotate ? 0xFFFFFFFF : 0xFFCCCCCC);
        g.fill(RenderType.guiOverlay(), rotateX, rotateY, rotateX + ROTATE_BTN_W, rotateY + ROTATE_BTN_H, 0, rotateBg);
        g.fill(RenderType.guiOverlay(), rotateX, rotateY + ROTATE_BTN_H - 1, rotateX + ROTATE_BTN_W, rotateY + ROTATE_BTN_H, 0, 0xFF3A3E47);
        g.drawCenteredString(font, I18n.name("gui.wandscape.buildpop.rotate", "旋转").getString(),
                rotateX + ROTATE_BTN_W / 2, rotateY + 3, rotateFg);

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

        // 建造清盒开关（是=整盒清≈旧版，否=纯放可叠放）— 常驻于提交按钮上方
        y = panelY + CLEAR_Y;
        boolean clearBox = ProjectionClientState.isClearBoxBeforeBuild();
        int clearW = PANEL_W - 16;
        int clearX = panelX + 8;
        int clearY = y;

        boolean hoverClear = mouseX >= clearX && mouseX <= clearX + clearW
                && mouseY >= clearY && mouseY <= clearY + BTN_H;
        int clearBg = hoverClear
                ? (clearBox ? CLEAR_BG_ON_HOVER : CLEAR_BG_OFF_HOVER)
                : (clearBox ? CLEAR_BG_ON : CLEAR_BG_OFF);
        int clearAccent = clearBox ? CLEAR_ACCENT_ON : CLEAR_ACCENT_OFF;
        g.fill(RenderType.guiOverlay(), clearX, clearY, clearX + clearW, clearY + BTN_H, 0, clearBg);
        g.fill(RenderType.guiOverlay(), clearX, clearY + BTN_H - 1, clearX + clearW, clearY + BTN_H, 0, clearAccent);
        g.drawCenteredString(font, (clearBox
                        ? I18n.name("gui.wandscape.buildpop.clear_on", "清理盒内方块：是").getString()
                        : I18n.name("gui.wandscape.buildpop.clear_off", "清理盒内方块：否").getString()),
                clearX + clearW / 2, clearY + 4, hoverClear ? 0xFFFFFFFF : 0xFFDDDDDD);

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

        // 键位提示行：全仓只此一处解释「左键 / Enter / ALT+滚轮」，别再往别的行塞第二份
        g.drawCenteredString(font, I18n.name("gui.wandscape.buildpop.hint",
                        "§8左键旋转 · Enter 确认 · ALT+滚轮微调").getString(),
                panelX + PANEL_W / 2, panelY + HINT_Y + 2, 0xFFFFFFFF);
    }

    public static boolean isOverPanel(double mouseX, double mouseY, int screenW) {
        if (!isActive()) return false;
        int panelX = getPanelX(screenW);
        int panelY = getPanelY();
        int panelH = getPanelH();
        return mouseX >= panelX && mouseX <= panelX + PANEL_W && mouseY >= panelY && mouseY <= panelY + panelH;
    }

    /** 命中「阶段」按钮（瞄准→确认位置 / 调整中→定稿 / 已定稿→重新瞄准）。 */
    public static boolean isOverStageButton(double mouseX, double mouseY, int screenW) {
        if (!isActive()) return false;
        int panelX = getPanelX(screenW);
        int panelY = getPanelY();
        int btnX = panelX + PANEL_W - BTN_W - BTN_RIGHT_PAD;
        int btnY = panelY + STATUS_Y;
        return mouseX >= btnX && mouseX <= btnX + BTN_W && mouseY >= btnY && mouseY <= btnY + BTN_H;
    }

    /** 命中「旋转」按钮（朝向那行右侧，与左键同一动作）。 */
    public static boolean isOverRotateButton(double mouseX, double mouseY, int screenW) {
        if (!isActive()) return false;
        int panelX = getPanelX(screenW);
        int panelY = getPanelY();
        int btnX = panelX + PANEL_W - ROTATE_BTN_W - BTN_RIGHT_PAD;
        int btnY = panelY + ROT_Y;
        return mouseX >= btnX && mouseX <= btnX + ROTATE_BTN_W && mouseY >= btnY && mouseY <= btnY + ROTATE_BTN_H;
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

    /** 命中「建造清盒」开关按钮。 */
    public static boolean isOverClearToggleButton(double mouseX, double mouseY, int screenW) {
        if (!isActive()) return false;
        int panelX = getPanelX(screenW);
        int panelY = getPanelY();
        int clearW = PANEL_W - 16;
        int clearX = panelX + 8;
        int clearY = panelY + CLEAR_Y;
        return mouseX >= clearX && mouseX <= clearX + clearW && mouseY >= clearY && mouseY <= clearY + BTN_H;
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
