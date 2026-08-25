package com.wsteam.wandscape.shared.ui.panel;

import java.util.List;

import com.wsteam.wandscape.overview.client.OverviewClientState;
import com.wsteam.wandscape.overview.client.OverviewFlightController;
import com.wsteam.wandscape.shared.network.tasks.MageModeActionPacket;
import com.wsteam.wandscape.shared.network.tasks.MageSummaryDto;
import com.wsteam.wandscape.shared.network.tasks.ResourceShortageDto;
import com.wsteam.wandscape.shared.network.tasks.TaskManagementActionPacket;
import com.wsteam.wandscape.shared.network.tasks.TaskSummaryDto;
import com.wsteam.wandscape.shared.ui.I18n;
import com.wsteam.wandscape.shared.ui.theme.WandscapeTheme;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Full-screen RTS Colony Management Hub for Global Tasks & Mages.
 * When active, generic HUD top bars and sidebars are hidden for a clean, spacious overview.
 */
public final class TaskManagementOverlay {

    private static final int HEADER_H = 34;
    private static final int TOOLBAR_H = 26;
    private static final int CARD_GAP = 8;
    private static final int TASK_CARD_H = 72;
    private static final int MAGE_CARD_H = 80;

    private static final int BG_BACKDROP = 0xAA080B10;
    private static final int HEADER_BG = 0xEE11151D;
    private static final int TOOLBAR_BG = 0xCC161B24;
    private static final int BORDER_GOLD = 0xFFC8A040;
    private static final int CARD_BG = 0xDD181D26;
    private static final int CARD_BG_HOVER = 0xF2222834;

    private TaskManagementOverlay() {}

    public static boolean isActive() {
        return WandscapePanelState.isPanelOpen()
                && !WandscapePanelState.isPanelHidden()
                && WandscapePanelState.getActiveSubMode() == WandscapePanelState.SubMode.TASKS;
    }

    public static void render(GuiGraphics g, Font font, int screenW, int screenH, double mx, double my) {
        if (!isActive()) return;

        // 1. Semi-transparent backdrop over 3D world
        g.fill(RenderType.guiOverlay(), 0, 0, screenW, screenH, 0, BG_BACKDROP);

        // 2. Top Header Bar (Navigation Tabs & Exit)
        renderHeader(g, font, screenW, mx, my);

        // 3. Sub-Header Toolbar (Filter Pills / Summary)
        renderToolbar(g, font, screenW, mx, my);

        // 4. Main Card Grid
        int listY = HEADER_H + TOOLBAR_H + 8;
        int listH = screenH - listY - 10;
        int padX = Math.max(16, (screenW - 1100) / 2 > 16 ? (screenW - 1100) / 2 : 20);
        int availW = screenW - padX * 2;

        TaskManagementClientState.SubTab tab = TaskManagementClientState.getActiveTab();
        if (tab == TaskManagementClientState.SubTab.TASKS) {
            renderTasksGrid(g, font, padX, listY, availW, listH, mx, my);
        } else {
            renderMagesGrid(g, font, padX, listY, availW, listH, mx, my);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // ── 1. Top Header ──
    // ═══════════════════════════════════════════════════════════════════

    private static void renderHeader(GuiGraphics g, Font font, int screenW, double mx, double my) {
        g.fill(RenderType.guiOverlay(), 0, 0, screenW, HEADER_H, 0, HEADER_BG);
        g.fill(RenderType.guiOverlay(), 0, HEADER_H - 1, screenW, HEADER_H, 0, BORDER_GOLD);

        int curX = 16;
        int btnY = 6;
        int btnH = 22;

        // Colony / Hub Title
        String colonyName = WandscapePanelState.getColonyName();
        String title = colonyName.isEmpty() ? "魔法小镇" : colonyName;
        g.drawString(font, title, curX, 12, WandscapeTheme.COLOR_TEXT_ACTIVE, false);
        curX += font.width(title) + 16;

        // Tab: Tasks
        TaskManagementClientState.SubTab tab = TaskManagementClientState.getActiveTab();
        boolean tasksActive = tab == TaskManagementClientState.SubTab.TASKS;
        int tabTaskW = 96;
        boolean taskHover = mx >= curX && mx <= curX + tabTaskW && my >= btnY && my <= btnY + btnH;
        int taskBg = tasksActive ? 0xFF2A3240 : (taskHover ? 0x883E4A5E : 0x441E242E);
        g.fill(RenderType.guiOverlay(), curX, btnY, curX + tabTaskW, btnY + btnH, 0, taskBg);
        if (tasksActive) {
            g.fill(RenderType.guiOverlay(), curX, btnY + btnH - 2, curX + tabTaskW, btnY + btnH, 0, BORDER_GOLD);
        }
        String taskLabel = "任务大厅 (" + TaskManagementClientState.getTotalActiveTasks() + ")";
        int taskColor = tasksActive ? WandscapeTheme.COLOR_TEXT_ACTIVE : WandscapeTheme.COLOR_TEXT_NORMAL;
        g.drawString(font, taskLabel, curX + (tabTaskW - font.width(taskLabel)) / 2, btnY + 7, taskColor, false);
        curX += tabTaskW + 8;

        // Tab: Mages
        boolean magesActive = tab == TaskManagementClientState.SubTab.MAGES;
        int tabMageW = 96;
        boolean mageHover = mx >= curX && mx <= curX + tabMageW && my >= btnY && my <= btnY + btnH;
        int mageBg = magesActive ? 0xFF2A3240 : (mageHover ? 0x883E4A5E : 0x441E242E);
        g.fill(RenderType.guiOverlay(), curX, btnY, curX + tabMageW, btnY + btnH, 0, mageBg);
        if (magesActive) {
            g.fill(RenderType.guiOverlay(), curX, btnY + btnH - 2, curX + tabMageW, btnY + btnH, 0, BORDER_GOLD);
        }
        String mageLabel = "法师名册 (" + TaskManagementClientState.getTotalMageCount() + ")";
        int mageColor = magesActive ? WandscapeTheme.COLOR_TEXT_ACTIVE : WandscapeTheme.COLOR_TEXT_NORMAL;
        g.drawString(font, mageLabel, curX + (tabMageW - font.width(mageLabel)) / 2, btnY + 7, mageColor, false);
        curX += tabMageW + 24;

        // Live Metric Badges (Center)
        if (curX < screenW - 320) {
            int inProgress = 0, awaiting = 0, pending = 0;
            for (TaskSummaryDto t : TaskManagementClientState.getAllTasks()) {
                if ("IN_PROGRESS".equalsIgnoreCase(t.state())) inProgress++;
                else if ("AWAITING_RESOURCES".equalsIgnoreCase(t.state())) awaiting++;
                else if ("PENDING_ASSIGN".equalsIgnoreCase(t.state())) pending++;
            }
            String metrics = String.format("运行中: %d  |  缺资源: %d  |  排队: %d  |  空闲法师: %d/%d",
                    inProgress, awaiting, pending,
                    TaskManagementClientState.getIdleMageCount(), TaskManagementClientState.getTotalMageCount());
            g.drawString(font, metrics, curX, 12, WandscapeTheme.COLOR_TEXT_DIM, false);
        }

        // Close / Exit Button [返回鸟瞰 (ESC)] (Right)
        int closeW = 110;
        int closeX = screenW - closeW - 16;
        boolean closeHover = mx >= closeX && mx <= closeX + closeW && my >= btnY && my <= btnY + btnH;
        int closeBg = closeHover ? 0xCCE53935 : 0x883A2020;
        g.fill(RenderType.guiOverlay(), closeX, btnY, closeX + closeW, btnY + btnH, 0, closeBg);
        String closeText = "返回鸟瞰 (ESC)";
        g.drawString(font, closeText, closeX + (closeW - font.width(closeText)) / 2, btnY + 7, 0xFFFFFFFF, false);
    }

    // ═══════════════════════════════════════════════════════════════════
    // ── 2. Toolbar & Filters ──
    // ═══════════════════════════════════════════════════════════════════

    private static void renderToolbar(GuiGraphics g, Font font, int screenW, double mx, double my) {
        int y = HEADER_H;
        g.fill(RenderType.guiOverlay(), 0, y, screenW, y + TOOLBAR_H, 0, TOOLBAR_BG);

        int curX = 20;
        int btnY = y + 4;
        int btnH = 18;

        TaskManagementClientState.SubTab tab = TaskManagementClientState.getActiveTab();
        if (tab == TaskManagementClientState.SubTab.TASKS) {
            TaskManagementClientState.TaskFilter currentFilter = TaskManagementClientState.getActiveFilter();
            TaskManagementClientState.TaskFilter[] filters = TaskManagementClientState.TaskFilter.values();

            for (TaskManagementClientState.TaskFilter f : filters) {
                String label = switch (f) {
                    case ALL -> "全部";
                    case IN_PROGRESS -> "进行中";
                    case AWAITING_RESOURCES -> "缺前置资源";
                    case PENDING -> "排队等待";
                    case QUEUED -> "建筑待办";
                };
                int btnW = font.width(label) + 14;
                boolean active = f == currentFilter;
                boolean hover = mx >= curX && mx <= curX + btnW && my >= btnY && my <= btnY + btnH;
                int bg = active ? 0xFFC8A040 : (hover ? 0x883E4A5E : 0x44262E3B);
                g.fill(RenderType.guiOverlay(), curX, btnY, curX + btnW, btnY + btnH, 0, bg);
                int txtColor = active ? 0xFF111214 : WandscapeTheme.COLOR_TEXT_NORMAL;
                g.drawString(font, label, curX + 7, btnY + 5, txtColor, false);
                curX += btnW + 6;
            }
        } else {
            String hint = "点击 [聚焦] 或 [跟踪] 可联动 3D 镜头观测法师工作，键盘 WASD 移动即刻脱离跟踪。";
            g.drawString(font, hint, curX, btnY + 5, 0xFF90CAF9, false);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // ── 3. Tasks Multi-Column Grid ──
    // ═══════════════════════════════════════════════════════════════════

    private static void renderTasksGrid(GuiGraphics g, Font font, int x, int y, int w, int h, double mx, double my) {
        List<TaskSummaryDto> tasks = TaskManagementClientState.getFilteredTasks();

        if (tasks.isEmpty()) {
            String empty = "当前筛选分类下暂无任务";
            g.drawString(font, empty, x + (w - font.width(empty)) / 2, y + 50, WandscapeTheme.COLOR_TEXT_DIM, false);
            return;
        }

        int cols = Math.max(1, Math.min(3, w / 350));
        int cardW = (w - (cols - 1) * CARD_GAP);
        cardW = cardW / cols;

        int totalRows = (tasks.size() + cols - 1) / cols;
        int totalHeight = totalRows * (TASK_CARD_H + CARD_GAP);
        int maxScroll = Math.max(0, totalHeight - h);
        int scroll = Math.min(TaskManagementClientState.getTaskScrollOffset(), maxScroll);

        g.enableScissor(x - 2, y, x + w + 2, y + h);

        for (int i = 0; i < tasks.size(); i++) {
            int col = i % cols;
            int row = i / cols;
            int cx = x + col * (cardW + CARD_GAP);
            int cy = y - scroll + row * (TASK_CARD_H + CARD_GAP);

            if (cy + TASK_CARD_H >= y && cy <= y + h) {
                renderTaskCard(g, font, cx, cy, cardW, tasks.get(i), mx, my);
            }
        }

        g.disableScissor();

        // Scrollbar
        if (maxScroll > 0) {
            int sbX = x + w - 4;
            int sbH = Math.max(16, (h * h) / totalHeight);
            int sbY = y + (int) ((float) scroll / maxScroll * (h - sbH));
            g.fill(RenderType.guiOverlay(), sbX, y, sbX + 3, y + h, 0, 0x33FFFFFF);
            g.fill(RenderType.guiOverlay(), sbX, sbY, sbX + 3, sbY + sbH, 0, 0xFFAAAAAA);
        }
    }

    private static void renderTaskCard(GuiGraphics g, Font font, int x, int y, int w, TaskSummaryDto task, double mx, double my) {
        boolean hover = mx >= x && mx <= x + w && my >= y && my <= y + TASK_CARD_H;
        g.fill(RenderType.guiOverlay(), x, y, x + w, y + TASK_CARD_H, 0, hover ? CARD_BG_HOVER : CARD_BG);
        g.fill(RenderType.guiOverlay(), x, y, x + 3, y + TASK_CARD_H, 0, getStateAccentColor(task.state()));

        // Line 1: Priority Badge + Title + Category Tag
        String pTag = "[P" + task.priority() + "] ";
        String title = pTag + task.title();
        if (font.width(title) > w - 80) {
            title = font.plainSubstrByWidth(title, w - 85) + "...";
        }
        g.drawString(font, title, x + 8, y + 6, WandscapeTheme.COLOR_TEXT_NORMAL, false);

        String catTag = "[" + formatCategory(task.category()) + "]";
        g.drawString(font, catTag, x + w - font.width(catTag) - 8, y + 6, WandscapeTheme.COLOR_TEXT_DIM, false);

        // Line 2: Status Details
        int statusY = y + 20;
        if ("IN_PROGRESS".equalsIgnoreCase(task.state())) {
            String mage = task.assignedNpcName().isEmpty() ? "法师" : task.assignedNpcName();
            String status = "正在执行: " + mage + " (步骤 " + (task.stepIndex() + 1) + "/" + task.totalSteps() + ")";
            g.drawString(font, status, x + 8, statusY, 0xFF81C784, false);

            // Progress Bar
            int barW = w - 170;
            int barY = y + 52;
            g.fill(RenderType.guiOverlay(), x + 8, barY, x + 8 + barW, barY + 4, 0, 0xFF2A2E38);
            int fillW = (int) (barW * task.getProgress());
            g.fill(RenderType.guiOverlay(), x + 8, barY, x + 8 + fillW, barY + 4, 0, 0xFFC8A040);
        } else if ("AWAITING_RESOURCES".equalsIgnoreCase(task.state())) {
            if (task.shortages() != null && !task.shortages().isEmpty()) {
                ResourceShortageDto s = task.shortages().getFirst();
                String shortStr = "缺少前置: " + s.displayName() + " x" + s.getMissingAmount() + " (库存: " + s.currentAmount() + " / 需: " + s.requiredAmount() + ")";
                g.drawString(font, shortStr, x + 8, statusY, 0xFFE57373, false);
            } else {
                g.drawString(font, "缺少前置资源", x + 8, statusY, 0xFFE57373, false);
            }
        } else if ("PENDING_ASSIGN".equalsIgnoreCase(task.state())) {
            String reason = "WAITING_NPC".equals(task.blockerReason()) ? "暂无空闲法师，等待调度认领" : "排队等待调度中";
            g.drawString(font, reason, x + 8, statusY, 0xFFFFD54F, false);
        } else if ("QUEUED".equalsIgnoreCase(task.state())) {
            g.drawString(font, "建筑队列排队中 (待办阶段)", x + 8, statusY, 0xFFB0BEC5, false);
        }

        // Line 3: Action Buttons (Right Aligned)
        renderTaskActionButtons(g, font, x + w - 150, y + 44, task, mx, my);
    }

    private static void renderTaskActionButtons(GuiGraphics g, Font font, int x, int y, TaskSummaryDto task, double mx, double my) {
        int btnH = 20;

        // [定位]
        if (task.hasTargetPos()) {
            int btnW = 44;
            boolean hover = mx >= x && mx <= x + btnW && my >= y && my <= y + btnH;
            g.fill(RenderType.guiOverlay(), x, y, x + btnW, y + btnH, 0, hover ? 0xEE3E4A5E : 0x882A313D);
            g.drawString(font, "定位", x + 6, y + 6, 0xFFFFFFFF, false);
        }

        // [加急]
        int rushX = x + 48;
        int rushW = 44;
        boolean rushHover = mx >= rushX && mx <= rushX + rushW && my >= y && my <= y + btnH;
        g.fill(RenderType.guiOverlay(), rushX, y, rushX + rushW, y + btnH, 0, rushHover ? 0xEEC8A040 : 0x885C4B20);
        g.drawString(font, "加急", rushX + 6, y + 6, 0xFFFFFFFF, false);

        // [取消]
        int cancelX = x + 96;
        int cancelW = 44;
        boolean cancelHover = mx >= cancelX && mx <= cancelX + cancelW && my >= y && my <= y + btnH;
        g.fill(RenderType.guiOverlay(), cancelX, y, cancelX + cancelW, y + btnH, 0, cancelHover ? 0xEEE53935 : 0x885C2020);
        g.drawString(font, "取消", cancelX + 6, y + 6, 0xFFFFFFFF, false);
    }

    // ═══════════════════════════════════════════════════════════════════
    // ── 4. Mages Multi-Column Grid ──
    // ═══════════════════════════════════════════════════════════════════

    private static void renderMagesGrid(GuiGraphics g, Font font, int x, int y, int w, int h, double mx, double my) {
        List<MageSummaryDto> mages = TaskManagementClientState.getFilteredMages();

        if (mages.isEmpty()) {
            String empty = "当前小镇暂无法师";
            g.drawString(font, empty, x + (w - font.width(empty)) / 2, y + 50, WandscapeTheme.COLOR_TEXT_DIM, false);
            return;
        }

        int cols = Math.max(1, Math.min(3, w / 350));
        int cardW = (w - (cols - 1) * CARD_GAP);
        cardW = cardW / cols;

        int totalRows = (mages.size() + cols - 1) / cols;
        int totalHeight = totalRows * (MAGE_CARD_H + CARD_GAP);
        int maxScroll = Math.max(0, totalHeight - h);
        int scroll = Math.min(TaskManagementClientState.getMageScrollOffset(), maxScroll);

        g.enableScissor(x - 2, y, x + w + 2, y + h);

        for (int i = 0; i < mages.size(); i++) {
            int col = i % cols;
            int row = i / cols;
            int cx = x + col * (cardW + CARD_GAP);
            int cy = y - scroll + row * (MAGE_CARD_H + CARD_GAP);

            if (cy + MAGE_CARD_H >= y && cy <= y + h) {
                renderMageCard(g, font, cx, cy, cardW, mages.get(i), mx, my);
            }
        }

        g.disableScissor();

        if (maxScroll > 0) {
            int sbX = x + w - 4;
            int sbH = Math.max(16, (h * h) / totalHeight);
            int sbY = y + (int) ((float) scroll / maxScroll * (h - sbH));
            g.fill(RenderType.guiOverlay(), sbX, y, sbX + 3, y + h, 0, 0x33FFFFFF);
            g.fill(RenderType.guiOverlay(), sbX, sbY, sbX + 3, sbY + sbH, 0, 0xFFAAAAAA);
        }
    }

    private static void renderMageCard(GuiGraphics g, Font font, int x, int y, int w, MageSummaryDto mage, double mx, double my) {
        boolean hover = mx >= x && mx <= x + w && my >= y && my <= y + MAGE_CARD_H;
        g.fill(RenderType.guiOverlay(), x, y, x + w, y + MAGE_CARD_H, 0, hover ? CARD_BG_HOVER : CARD_BG);
        g.fill(RenderType.guiOverlay(), x, y, x + 3, y + MAGE_CARD_H, 0, getMageStateAccentColor(mage.state()));

        // Line 1: Name + State Tag
        String name = mage.name();
        g.drawString(font, name, x + 8, y + 6, WandscapeTheme.COLOR_TEXT_ACTIVE, false);

        String stateTag = formatMageState(mage);
        g.drawString(font, stateTag, x + w - font.width(stateTag) - 8, y + 6, getMageStateTextColor(mage.state()), false);

        // Line 2: HP & MP Gauges
        int barW = 64;
        int barY = y + 22;
        // HP
        g.drawString(font, "HP", x + 8, barY, 0xFF81C784, false);
        g.fill(RenderType.guiOverlay(), x + 24, barY + 2, x + 24 + barW, barY + 6, 0, 0xFF3E2723);
        int hpFill = (int) (barW * mage.getHealthRatio());
        g.fill(RenderType.guiOverlay(), x + 24, barY + 2, x + 24 + hpFill, barY + 6, 0, 0xFF4CAF50);

        // MP
        int mpX = x + 96;
        g.drawString(font, "MP", mpX, barY, 0xFF42A5F5, false);
        g.fill(RenderType.guiOverlay(), mpX + 18, barY + 2, mpX + 18 + barW, barY + 6, 0, 0xFF0D47A1);
        int manaFill = (int) (barW * mage.getManaRatio());
        g.fill(RenderType.guiOverlay(), mpX + 18, barY + 2, mpX + 18 + manaFill, barY + 6, 0, 0xFF2196F3);

        // Line 3: Attributes + Wand
        String attrStr = String.format("法强:%.1f  工速:%.1f  护甲:%.0f", mage.spellPower(), mage.workSpeed(), mage.armorValue());
        if (!mage.equippedWand().isEmpty()) {
            attrStr += "  |  " + mage.equippedWand();
        }
        g.drawString(font, attrStr, x + 8, y + 36, WandscapeTheme.COLOR_TEXT_DIM, false);

        // Line 4: Action Buttons (Right Aligned)
        renderMageActionButtons(g, font, x + w - 210, y + 52, mage, mx, my);
    }

    private static void renderMageActionButtons(GuiGraphics g, Font font, int x, int y, MageSummaryDto mage, double mx, double my) {
        int btnH = 20;

        // [聚焦]
        int btnW = 44;
        boolean focusHover = mx >= x && mx <= x + btnW && my >= y && my <= y + btnH;
        g.fill(RenderType.guiOverlay(), x, y, x + btnW, y + btnH, 0, focusHover ? 0xEE3E4A5E : 0x882A313D);
        g.drawString(font, "聚焦", x + 5, y + 6, 0xFFFFFFFF, false);

        // [跟踪]
        int trackX = x + 48;
        int trackW = 44;
        boolean isTracking = TaskManagementClientState.getTrackingEntityId() == mage.entityId();
        boolean trackHover = mx >= trackX && mx <= trackX + trackW && my >= y && my <= y + btnH;
        int trackBg = isTracking ? 0xFFC8A040 : (trackHover ? 0xEE3E4A5E : 0x882A313D);
        g.fill(RenderType.guiOverlay(), trackX, y, trackX + trackW, y + btnH, 0, trackBg);
        g.drawString(font, "跟踪", trackX + 5, y + 6, isTracking ? 0xFF111214 : 0xFFFFFFFF, false);

        // [跟随]
        int followX = x + 96;
        int followW = 48;
        boolean followHover = mx >= followX && mx <= followX + followW && my >= y && my <= y + btnH;
        int followBg = mage.followMode() ? 0xFF4CAF50 : (followHover ? 0xEE3E4A5E : 0x882A313D);
        g.fill(RenderType.guiOverlay(), followX, y, followX + followW, y + btnH, 0, followBg);
        g.drawString(font, mage.followMode() ? "取消跟随" : "跟随", followX + 5, y + 6, 0xFFFFFFFF, false);

        // [和平]
        int peaceX = x + 148;
        int peaceW = 48;
        boolean peaceHover = mx >= peaceX && mx <= peaceX + peaceW && my >= y && my <= y + btnH;
        int peaceBg = mage.peaceMode() ? 0xFF42A5F5 : (peaceHover ? 0xEE3E4A5E : 0x882A313D);
        g.fill(RenderType.guiOverlay(), peaceX, y, peaceX + peaceW, y + btnH, 0, peaceBg);
        g.drawString(font, mage.peaceMode() ? "取消和平" : "和平", peaceX + 5, y + 6, 0xFFFFFFFF, false);
    }

    // ═══════════════════════════════════════════════════════════════════
    // ── Mouse Click & Scroll Handling ──
    // ═══════════════════════════════════════════════════════════════════

    public static boolean handleMouseClick(double mx, double my, int screenW, int screenH) {
        if (!isActive()) return false;

        // 1. Header Tab Switcher & Exit
        int curX = 16;
        String colonyName = WandscapePanelState.getColonyName();
        String title = colonyName.isEmpty() ? "魔法小镇" : colonyName;
        curX += Minecraft.getInstance().font.width(title) + 16;

        int btnY = 6;
        int btnH = 22;

        // Tab: Tasks
        int tabTaskW = 96;
        if (mx >= curX && mx <= curX + tabTaskW && my >= btnY && my <= btnY + btnH) {
            TaskManagementClientState.setActiveTab(TaskManagementClientState.SubTab.TASKS);
            playClickSound();
            return true;
        }
        curX += tabTaskW + 8;

        // Tab: Mages
        int tabMageW = 96;
        if (mx >= curX && mx <= curX + tabMageW && my >= btnY && my <= btnY + btnH) {
            TaskManagementClientState.setActiveTab(TaskManagementClientState.SubTab.MAGES);
            playClickSound();
            return true;
        }

        // Close Button
        int closeW = 110;
        int closeX = screenW - closeW - 16;
        if (mx >= closeX && mx <= closeX + closeW && my >= btnY && my <= btnY + btnH) {
            collapseDrawerToOverview();
            playClickSound();
            return true;
        }

        // 2. Toolbar Filters
        int toolbarY = HEADER_H;
        if (my >= toolbarY && my <= toolbarY + TOOLBAR_H) {
            TaskManagementClientState.SubTab tab = TaskManagementClientState.getActiveTab();
            if (tab == TaskManagementClientState.SubTab.TASKS) {
                int fx = 20;
                int fBtnY = toolbarY + 4;
                int fBtnH = 18;
                Font font = Minecraft.getInstance().font;
                for (TaskManagementClientState.TaskFilter f : TaskManagementClientState.TaskFilter.values()) {
                    String label = switch (f) {
                        case ALL -> "全部";
                        case IN_PROGRESS -> "进行中";
                        case AWAITING_RESOURCES -> "缺前置资源";
                        case PENDING -> "排队等待";
                        case QUEUED -> "建筑待办";
                    };
                    int btnW = font.width(label) + 14;
                    if (mx >= fx && mx <= fx + btnW && my >= fBtnY && my <= fBtnY + fBtnH) {
                        TaskManagementClientState.setActiveFilter(f);
                        playClickSound();
                        return true;
                    }
                    fx += btnW + 6;
                }
            }
            return true;
        }

        // 3. Grid Clicks
        int listY = HEADER_H + TOOLBAR_H + 8;
        int listH = screenH - listY - 10;
        int padX = Math.max(16, (screenW - 1100) / 2 > 16 ? (screenW - 1100) / 2 : 20);
        int availW = screenW - padX * 2;

        TaskManagementClientState.SubTab tab = TaskManagementClientState.getActiveTab();
        if (tab == TaskManagementClientState.SubTab.TASKS) {
            List<TaskSummaryDto> tasks = TaskManagementClientState.getFilteredTasks();
            int cols = Math.max(1, Math.min(3, availW / 350));
            int cardW = (availW - (cols - 1) * CARD_GAP) / cols;
            int scroll = TaskManagementClientState.getTaskScrollOffset();

            for (int i = 0; i < tasks.size(); i++) {
                int col = i % cols;
                int row = i / cols;
                int cx = padX + col * (cardW + CARD_GAP);
                int cy = listY - scroll + row * (TASK_CARD_H + CARD_GAP);

                if (my >= cy && my <= cy + TASK_CARD_H) {
                    TaskSummaryDto t = tasks.get(i);
                    int btnBaseX = cx + cardW - 150;
                    int btnBaseY = cy + 44;
                    int btnH2 = 20;

                    // [定位]
                    if (t.hasTargetPos() && mx >= btnBaseX && mx <= btnBaseX + 44 && my >= btnBaseY && my <= btnBaseY + btnH2) {
                        flyToTarget(t.targetX(), t.targetY(), t.targetZ());
                        collapseDrawerToOverview();
                        playClickSound();
                        return true;
                    }

                    // [加急]
                    int rushX = btnBaseX + 48;
                    if (mx >= rushX && mx <= rushX + 44 && my >= btnBaseY && my <= btnBaseY + btnH2) {
                        PacketDistributor.sendToServer(new TaskManagementActionPacket(
                                t.taskId(), TaskManagementActionPacket.ACTION_RUSH, 100));
                        playClickSound();
                        return true;
                    }

                    // [取消]
                    int cancelX = btnBaseX + 96;
                    if (mx >= cancelX && mx <= cancelX + 44 && my >= btnBaseY && my <= btnBaseY + btnH2) {
                        PacketDistributor.sendToServer(new TaskManagementActionPacket(
                                t.taskId(), TaskManagementActionPacket.ACTION_CANCEL, 0));
                        playClickSound();
                        return true;
                    }
                }
            }
        } else {
            List<MageSummaryDto> mages = TaskManagementClientState.getFilteredMages();
            int cols = Math.max(1, Math.min(3, availW / 350));
            int cardW = (availW - (cols - 1) * CARD_GAP) / cols;
            int scroll = TaskManagementClientState.getMageScrollOffset();

            for (int i = 0; i < mages.size(); i++) {
                int col = i % cols;
                int row = i / cols;
                int cx = padX + col * (cardW + CARD_GAP);
                int cy = listY - scroll + row * (MAGE_CARD_H + CARD_GAP);

                if (my >= cy && my <= cy + MAGE_CARD_H) {
                    MageSummaryDto m = mages.get(i);
                    int btnBaseX = cx + cardW - 210;
                    int btnBaseY = cy + 52;
                    int btnH2 = 20;

                    // [聚焦]
                    if (mx >= btnBaseX && mx <= btnBaseX + 44 && my >= btnBaseY && my <= btnBaseY + btnH2) {
                        flyToTarget(m.posX(), m.posY(), m.posZ());
                        collapseDrawerToOverview();
                        playClickSound();
                        return true;
                    }

                    // [跟踪]
                    int trackX = btnBaseX + 48;
                    if (mx >= trackX && mx <= trackX + 44 && my >= btnBaseY && my <= btnBaseY + btnH2) {
                        int current = TaskManagementClientState.getTrackingEntityId();
                        int next = current == m.entityId() ? -1 : m.entityId();
                        TaskManagementClientState.setTrackingEntityId(next);
                        if (next != -1) {
                            flyToTarget(m.posX(), m.posY(), m.posZ());
                            collapseDrawerToOverview();
                        }
                        playClickSound();
                        return true;
                    }

                    // [跟随]
                    int followX = btnBaseX + 96;
                    if (mx >= followX && mx <= followX + 48 && my >= btnBaseY && my <= btnBaseY + btnH2) {
                        PacketDistributor.sendToServer(new MageModeActionPacket(
                                m.entityId(), MageModeActionPacket.MODE_FOLLOW, !m.followMode()));
                        playClickSound();
                        return true;
                    }

                    // [和平]
                    int peaceX = btnBaseX + 148;
                    if (mx >= peaceX && mx <= peaceX + 48 && my >= btnBaseY && my <= btnBaseY + btnH2) {
                        PacketDistributor.sendToServer(new MageModeActionPacket(
                                m.entityId(), MageModeActionPacket.MODE_PEACE, !m.peaceMode()));
                        playClickSound();
                        return true;
                    }
                }
            }
        }

        return true;
    }

    public static boolean handleMouseScroll(double deltaY) {
        if (!isActive()) return false;
        TaskManagementClientState.SubTab tab = TaskManagementClientState.getActiveTab();
        int delta = deltaY > 0 ? -32 : 32;

        if (tab == TaskManagementClientState.SubTab.TASKS) {
            TaskManagementClientState.setTaskScrollOffset(
                    TaskManagementClientState.getTaskScrollOffset() + delta);
        } else {
            TaskManagementClientState.setMageScrollOffset(
                    TaskManagementClientState.getMageScrollOffset() + delta);
        }
        return true;
    }

    public static void collapseDrawerToOverview() {
        if (!OverviewClientState.isActive()) {
            OverviewFlightController.enter();
        }
        WandscapePanelState.setSubMode(WandscapePanelState.SubMode.OVERVIEW);
        WandscapePanelState.syncCursorToState();
    }

    private static void flyToTarget(double x, double y, double z) {
        if (!OverviewClientState.isActive()) {
            OverviewFlightController.enter();
        }
        OverviewClientState.setCamPosition(x, y + 14.0, z - 14.0);
    }

    private static void playClickSound() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSoundManager() != null) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
        }
    }

    private static String formatCategory(String category) {
        return switch (category.toLowerCase()) {
            case "build" -> "建造";
            case "gather" -> "采集";
            case "craft" -> "合成";
            case "decompose" -> "分解";
            case "guard" -> "守卫";
            case "altar" -> "祭坛";
            case "repair" -> "维修";
            case "queued" -> "待办";
            default -> "任务";
        };
    }

    private static int getStateAccentColor(String state) {
        return switch (state.toUpperCase()) {
            case "IN_PROGRESS" -> 0xFF81C784;
            case "AWAITING_RESOURCES" -> 0xFFE57373;
            case "PENDING_ASSIGN" -> 0xFFFFD54F;
            default -> 0xFF90A4AE;
        };
    }

    private static int getMageStateAccentColor(String state) {
        return switch (state.toUpperCase()) {
            case "CASTING", "MOVING" -> 0xFF81C784;
            case "FOLLOWING" -> 0xFF64B5F6;
            case "RESTING" -> 0xFFBA68C8;
            default -> 0xFF90A4AE;
        };
    }

    private static int getMageStateTextColor(String state) {
        return switch (state.toUpperCase()) {
            case "CASTING" -> 0xFF81C784;
            case "MOVING" -> 0xFF4FC3F7;
            case "FOLLOWING" -> 0xFF64B5F6;
            case "RESTING" -> 0xFFBA68C8;
            default -> 0xFFB0BEC5;
        };
    }

    private static String formatMageState(MageSummaryDto mage) {
        return switch (mage.state().toUpperCase()) {
            case "CASTING" -> "施法中" + (mage.currentTaskTitle().isEmpty() ? "" : ": " + mage.currentTaskTitle());
            case "MOVING" -> "前往工作中";
            case "FOLLOWING" -> "跟随中";
            case "RESTING" -> "回屋休息中";
            default -> "空闲待命中";
        };
    }
}
