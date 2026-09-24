package com.wsteam.wandscape.content.task.ui;
import com.wsteam.wandscape.content.task.ecs.World;
import com.wsteam.wandscape.content.task.types.EntityId;
import com.wsteam.wandscape.foundation.networking.Net;
import com.wsteam.wandscape.foundation.ui.panel.WandscapePanelState;

import com.wsteam.wandscape.content.colony.overview.client.OverviewClientState;
import com.wsteam.wandscape.content.colony.overview.client.OverviewFlightController;
import com.wsteam.wandscape.content.task.network.*;
import com.wsteam.wandscape.foundation.ui.I18n;
import com.wsteam.wandscape.foundation.ui.theme.WandscapeTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.util.List;

/**
 * Full-screen RTS Colony Management Hub for Global Tasks & Mages.
 * When active, generic HUD top bars and sidebars are hidden for a clean, spacious overview.
 */
public final class TaskManagementOverlay {

    private static final int HEADER_H = 34;
    private static final int TOOLBAR_H = 26;
    private static final int CARD_GAP = 8;
    private static final int TASK_CARD_H = 72;
    private static final int PROD_CARD_H = 76;
    private static final int MAGE_CARD_H = 80;

    // Header layout (shared between render & mouse hit-test so nothing drifts).
    private static final int HEADER_CLOSE_W = 110;
    private static final int HEADER_CLOSE_GAP = 16;
    private static final int HEADER_TITLE_GAP = 16;

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
        } else if (tab == TaskManagementClientState.SubTab.PRODUCTION) {
            renderProductionGrid(g, font, padX, listY, availW, listH, mx, my);
        } else {
            renderMagesGrid(g, font, padX, listY, availW, listH, mx, my);
        }

        // 5. Dependency Modal (if a production item is selected)
        if (TaskManagementClientState.getSelectedProductionVirtualId() != -1) {
            renderDependencyModal(g, font, screenW, screenH, mx, my);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // ── 1. Top Header ──
    // ═══════════════════════════════════════════════════════════════════

    private static void renderHeader(GuiGraphics g, Font font, int screenW, double mx, double my) {
        g.fill(RenderType.guiOverlay(), 0, 0, screenW, HEADER_H, 0, HEADER_BG);
        g.fill(RenderType.guiOverlay(), 0, HEADER_H - 1, screenW, HEADER_H, 0, BORDER_GOLD);

        int btnY = 6;
        int btnH = 22;
        int closeW = HEADER_CLOSE_W;
        int closeX = screenW - closeW - HEADER_CLOSE_GAP;

        HeaderLayout lo = layoutHeader(font, screenW);

        // Colony / Hub Title (dropped on narrow screens so the tabs stay clear of the exit button)
        if (!lo.titleDraw().isEmpty()) {
            g.drawString(font, lo.titleDraw(), 16, 12, WandscapeTheme.COLOR_TEXT_ACTIVE, false);
        }

        TaskManagementClientState.SubTab tab = TaskManagementClientState.getActiveTab();
        int curX = lo.startX();

        // Tab 1: Tasks
        boolean tasksActive = tab == TaskManagementClientState.SubTab.TASKS;
        boolean taskHover = mx >= curX && mx <= curX + lo.taskW() && my >= btnY && my <= btnY + btnH;
        int taskBg = tasksActive ? 0xFF2A3240 : (taskHover ? 0x883E4A5E : 0x441E242E);
        g.fill(RenderType.guiOverlay(), curX, btnY, curX + lo.taskW(), btnY + btnH, 0, taskBg);
        if (tasksActive) {
            g.fill(RenderType.guiOverlay(), curX, btnY + btnH - 2, curX + lo.taskW(), btnY + btnH, 0, BORDER_GOLD);
        }
        int taskColor = tasksActive ? WandscapeTheme.COLOR_TEXT_ACTIVE : WandscapeTheme.COLOR_TEXT_NORMAL;
        g.drawString(font, lo.taskLabel(), curX + (lo.taskW() - font.width(lo.taskLabel())) / 2, btnY + 7, taskColor, false);
        curX += lo.taskW() + lo.gap();

        // Tab 2: Production
        boolean prodActive = tab == TaskManagementClientState.SubTab.PRODUCTION;
        boolean prodHover = mx >= curX && mx <= curX + lo.prodW() && my >= btnY && my <= btnY + btnH;
        int prodBg = prodActive ? 0xFF2A3240 : (prodHover ? 0x883E4A5E : 0x441E242E);
        g.fill(RenderType.guiOverlay(), curX, btnY, curX + lo.prodW(), btnY + btnH, 0, prodBg);
        if (prodActive) {
            g.fill(RenderType.guiOverlay(), curX, btnY + btnH - 2, curX + lo.prodW(), btnY + btnH, 0, BORDER_GOLD);
        }
        int prodColor = prodActive ? WandscapeTheme.COLOR_TEXT_ACTIVE : WandscapeTheme.COLOR_TEXT_NORMAL;
        g.drawString(font, lo.prodLabel(), curX + (lo.prodW() - font.width(lo.prodLabel())) / 2, btnY + 7, prodColor, false);
        curX += lo.prodW() + lo.gap();

        // Tab 3: Mages
        boolean magesActive = tab == TaskManagementClientState.SubTab.MAGES;
        boolean mageHover = mx >= curX && mx <= curX + lo.mageW() && my >= btnY && my <= btnY + btnH;
        int mageBg = magesActive ? 0xFF2A3240 : (mageHover ? 0x883E4A5E : 0x441E242E);
        g.fill(RenderType.guiOverlay(), curX, btnY, curX + lo.mageW(), btnY + btnH, 0, mageBg);
        if (magesActive) {
            g.fill(RenderType.guiOverlay(), curX, btnY + btnH - 2, curX + lo.mageW(), btnY + btnH, 0, BORDER_GOLD);
        }
        int mageColor = magesActive ? WandscapeTheme.COLOR_TEXT_ACTIVE : WandscapeTheme.COLOR_TEXT_NORMAL;
        g.drawString(font, lo.mageLabel(), curX + (lo.mageW() - font.width(lo.mageLabel())) / 2, btnY + 7, mageColor, false);
        curX += lo.mageW() + 24;

        // Live Metric Badges (Center): drawn in the gap between tabs and the exit button, elided
        // so it never runs underneath the exit button.
        int metricsAvail = closeX - 12 - curX;
        if (metricsAvail >= 90) {
            String metrics;
            if (tab == TaskManagementClientState.SubTab.PRODUCTION) {
                int running = 0, queued = 0, missing = 0;
                for (ProductionGroupDto gDto : TaskManagementClientState.getAllProductionGroups()) {
                    for (ProductionItemDto item : gDto.items()) {
                        if ("RUNNING".equalsIgnoreCase(item.status())) running++;
                        else if ("MISSING_ELEMENTS".equalsIgnoreCase(item.status())) missing++;
                        else queued++;
                    }
                }
                metrics = I18n.string("gui.wandscape.task.metrics.production",
                        "工坊建筑: %s  |  制作中: %s  |  排队: %s  |  缺元素: %s",
                        String.valueOf(TaskManagementClientState.getAllProductionGroups().size()),
                        String.valueOf(running), String.valueOf(queued), String.valueOf(missing));
            } else {
                int inProgress = 0, awaiting = 0, pending = 0;
                for (TaskSummaryDto t : TaskManagementClientState.getAllTasks()) {
                    if ("IN_PROGRESS".equalsIgnoreCase(t.state())) inProgress++;
                    else if ("AWAITING_RESOURCES".equalsIgnoreCase(t.state())) awaiting++;
                    else if ("PENDING_ASSIGN".equalsIgnoreCase(t.state())) pending++;
                }
                metrics = I18n.string("gui.wandscape.task.metrics.tasks",
                        "运行中: %s  |  缺资源: %s  |  排队: %s  |  空闲法师: %s/%s",
                        String.valueOf(inProgress), String.valueOf(awaiting), String.valueOf(pending),
                        String.valueOf(TaskManagementClientState.getIdleMageCount()),
                        String.valueOf(TaskManagementClientState.getTotalMageCount()));
            }
            if (font.width(metrics) > metricsAvail) {
                metrics = font.plainSubstrByWidth(metrics, metricsAvail);
            }
            g.drawString(font, metrics, curX, 12, WandscapeTheme.COLOR_TEXT_DIM, false);
        }

        // Close / Exit Button [返回鸟瞰 (ESC)] (Right)
        boolean closeHover = mx >= closeX && mx <= closeX + closeW && my >= btnY && my <= btnY + btnH;
        int closeBg = closeHover ? 0xCCE53935 : 0x883A2020;
        g.fill(RenderType.guiOverlay(), closeX, btnY, closeX + closeW, btnY + btnH, 0, closeBg);
        String closeText = I18n.string("gui.wandscape.task.close", "返回鸟瞰 (ESC)");
        g.drawString(font, closeText, closeX + (closeW - font.width(closeText)) / 2, btnY + 7, 0xFFFFFFFF, false);
    }

    /**
     * Measures the header's title/tab geometry once, shared by rendering & hit-testing, so the tab
     * row can never reach the right-pinned exit button. On narrow screens the colony title is
     * dropped first, then tab padding/gaps shrink.
     */
    private static HeaderLayout layoutHeader(Font font, int screenW) {
        int closeX = screenW - HEADER_CLOSE_W - HEADER_CLOSE_GAP;
        int contentRight = closeX - 12; // nothing left of the exit button may pass this

        String colony = WandscapePanelState.getColonyName();
        String title = colony.isEmpty()
                ? I18n.string("gui.wandscape.task.default_town_name", "魔法小镇")
                : colony;

        String taskLabel = I18n.string("gui.wandscape.task.tab.tasks", "任务大厅 (%s)",
                String.valueOf(TaskManagementClientState.getTotalActiveTasks()));
        String prodLabel = I18n.string("gui.wandscape.task.tab.production", "工坊流水线 (%s)",
                String.valueOf(TaskManagementClientState.getTotalProductionItemCount()));
        String mageLabel = I18n.string("gui.wandscape.task.tab.mages", "法师名册 (%s)",
                String.valueOf(TaskManagementClientState.getTotalMageCount()));

        int titleW = font.width(title);
        int pad = 24;
        int gap = 8;
        int taskW = font.width(taskLabel) + pad;
        int prodW = font.width(prodLabel) + pad;
        int mageW = font.width(mageLabel) + pad;

        // Room from the left margin x=16 to contentRight: title first, then the tabs.
        int avail = contentRight - 16;
        boolean dropTitle = false;
        for (int pass = 0; pass < 8; pass++) {
            int tabsTotal = taskW + gap + prodW + gap + mageW;
            int need = (dropTitle ? 0 : titleW + HEADER_TITLE_GAP) + tabsTotal;
            if (need <= avail) break;
            if (!dropTitle) {
                dropTitle = true;
            } else if (pad > 12) {
                pad -= 4;
                taskW = font.width(taskLabel) + pad;
                prodW = font.width(prodLabel) + pad;
                mageW = font.width(mageLabel) + pad;
            } else if (gap > 4) {
                gap -= 2;
            } else {
                break;
            }
        }

        int startX = dropTitle ? 16 : 16 + titleW + HEADER_TITLE_GAP;
        return new HeaderLayout(dropTitle ? "" : title, startX, taskW, prodW, mageW, gap,
                taskLabel, prodLabel, mageLabel);
    }

    /** Measured header geometry shared by rendering & hit-testing. */
    private record HeaderLayout(String titleDraw, int startX, int taskW, int prodW, int mageW,
                                int gap, String taskLabel, String prodLabel, String mageLabel) {}

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
                String label = taskFilterLabel(f);
                int btnW = font.width(label) + 14;
                boolean active = f == currentFilter;
                boolean hover = mx >= curX && mx <= curX + btnW && my >= btnY && my <= btnY + btnH;
                int bg = active ? 0xFFC8A040 : (hover ? 0x883E4A5E : 0x44262E3B);
                g.fill(RenderType.guiOverlay(), curX, btnY, curX + btnW, btnY + btnH, 0, bg);
                int txtColor = active ? 0xFF111214 : WandscapeTheme.COLOR_TEXT_NORMAL;
                g.drawString(font, label, curX + 7, btnY + 5, txtColor, false);
                curX += btnW + 6;
            }
        } else if (tab == TaskManagementClientState.SubTab.PRODUCTION) {
            TaskManagementClientState.ProductionFilter currentFilter = TaskManagementClientState.getActiveProductionFilter();
            TaskManagementClientState.ProductionFilter[] filters = TaskManagementClientState.ProductionFilter.values();

            for (TaskManagementClientState.ProductionFilter f : filters) {
                String label = productionFilterLabel(f);
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
            // Mages 页：无工具栏筛选/提示（[聚焦]/[跟踪] 入口已移除）。
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // ── 3. Tasks Multi-Column Grid ──
    // ═══════════════════════════════════════════════════════════════════

    private static void renderTasksGrid(GuiGraphics g, Font font, int x, int y, int w, int h, double mx, double my) {
        List<TaskSummaryDto> tasks = TaskManagementClientState.getFilteredTasks();

        if (tasks.isEmpty()) {
            String empty = I18n.string("gui.wandscape.task.empty.tasks", "当前筛选分类下暂无任务");
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
        String title = pTag + TaskText.taskTitle(task);
        if (font.width(title) > w - 80) {
            title = font.plainSubstrByWidth(title, w - 85) + "...";
        }
        g.drawString(font, title, x + 8, y + 6, WandscapeTheme.COLOR_TEXT_NORMAL, false);

        String catTag = "[" + formatCategory(task.category()) + "]";
        g.drawString(font, catTag, x + w - font.width(catTag) - 8, y + 6, WandscapeTheme.COLOR_TEXT_DIM, false);

        // Line 2: Status Details
        int statusY = y + 20;
        if ("IN_PROGRESS".equalsIgnoreCase(task.state())) {
            String mage = task.assignedNpcName().isEmpty()
                    ? I18n.string("gui.wandscape.task.default_mage", "法师")
                    : task.assignedNpcName();
            String status = I18n.string("gui.wandscape.task.status.executing", "正在执行: %s (步骤 %s/%s)",
                    mage, String.valueOf(task.stepIndex() + 1), String.valueOf(task.totalSteps()));
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
                String shortStr = I18n.string("gui.wandscape.task.status.missing_prereq",
                        "缺少前置: %s x%s (库存: %s / 需: %s)",
                        TaskText.shortageName(s), String.valueOf(s.getMissingAmount()),
                        String.valueOf(s.currentAmount()), String.valueOf(s.requiredAmount()));
                g.drawString(font, shortStr, x + 8, statusY, 0xFFE57373, false);
            } else {
                g.drawString(font, I18n.string("gui.wandscape.task.status.missing_prereq_short", "缺少前置资源"),
                        x + 8, statusY, 0xFFE57373, false);
            }
        } else if ("PENDING_ASSIGN".equalsIgnoreCase(task.state())) {
            String reason = "WAITING_NPC".equals(task.blockerReason())
                    ? I18n.string("gui.wandscape.task.status.no_idle_mage", "暂无空闲法师，等待调度认领")
                    : I18n.string("gui.wandscape.task.status.queued_dispatch", "排队等待调度中");
            g.drawString(font, reason, x + 8, statusY, 0xFFFFD54F, false);
        } else if ("QUEUED".equalsIgnoreCase(task.state())) {
            g.drawString(font, I18n.string("gui.wandscape.task.status.building_queued", "建筑队列排队中 (待办阶段)"),
                    x + 8, statusY, 0xFFB0BEC5, false);
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
            g.drawString(font, locateLabel(), x + 6, y + 6, 0xFFFFFFFF, false);
        }

        // [加急]
        int rushX = x + 48;
        int rushW = 44;
        boolean rushHover = mx >= rushX && mx <= rushX + rushW && my >= y && my <= y + btnH;
        g.fill(RenderType.guiOverlay(), rushX, y, rushX + rushW, y + btnH, 0, rushHover ? 0xEEC8A040 : 0x885C4B20);
        g.drawString(font, I18n.string("gui.wandscape.task.action.rush", "加急"), rushX + 6, y + 6, 0xFFFFFFFF, false);

        // [取消]
        int cancelX = x + 96;
        int cancelW = 44;
        boolean cancelHover = mx >= cancelX && mx <= cancelX + cancelW && my >= y && my <= btnH;
        g.fill(RenderType.guiOverlay(), cancelX, y, cancelX + cancelW, y + btnH, 0, cancelHover ? 0xEEE53935 : 0x885C2020);
        g.drawString(font, I18n.string("gui.wandscape.task.action.cancel", "取消"), cancelX + 6, y + 6, 0xFFFFFFFF, false);
    }

    // ═══════════════════════════════════════════════════════════════════
    // ── 3.5. Production Workshops & Supply Chain Grid ──
    // ═══════════════════════════════════════════════════════════════════

    private static void renderProductionGrid(GuiGraphics g, Font font, int x, int y, int w, int h, double mx, double my) {
        List<ProductionGroupDto> groups = TaskManagementClientState.getFilteredProductionGroups();

        if (groups.isEmpty()) {
            String empty = I18n.string("gui.wandscape.task.empty.production", "当前筛选分类下暂无工坊生产任务");
            g.drawString(font, empty, x + (w - font.width(empty)) / 2, y + 50, WandscapeTheme.COLOR_TEXT_DIM, false);
            return;
        }

        int totalHeight = 0;
        for (ProductionGroupDto grp : groups) {
            totalHeight += 28 + CARD_GAP; // group header
            totalHeight += grp.items().size() * (PROD_CARD_H + CARD_GAP);
            totalHeight += 10; // extra group spacing
        }

        int maxScroll = Math.max(0, totalHeight - h);
        int scroll = Math.min(TaskManagementClientState.getProductionScrollOffset(), maxScroll);

        g.enableScissor(x - 2, y, x + w + 2, y + h);

        int curY = y - scroll;
        for (ProductionGroupDto grp : groups) {
            // Group Header
            if (curY + 28 >= y && curY <= y + h) {
                renderProductionGroupHeader(g, font, x, curY, w, grp, mx, my);
            }
            curY += 28 + CARD_GAP;

            // Group Item Cards
            for (ProductionItemDto item : grp.items()) {
                if (curY + PROD_CARD_H >= y && curY <= y + h) {
                    renderProductionItemCard(g, font, x, curY, w, item, mx, my);
                }
                curY += PROD_CARD_H + CARD_GAP;
            }
            curY += 10;
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

    private static void renderProductionGroupHeader(GuiGraphics g, Font font, int x, int y, int w, ProductionGroupDto group, double mx, double my) {
        g.fill(RenderType.guiOverlay(), x, y, x + w, y + 26, 0, 0xEE1A202C);
        g.fill(RenderType.guiOverlay(), x, y, x + 3, y + 26, 0, BORDER_GOLD);

        String catName = switch (group.category().toLowerCase()) {
            case "workstation" -> I18n.string("gui.wandscape.task.group.workstation", "工作站");
            case "alchemy" -> I18n.string("gui.wandscape.task.group.alchemy", "炼药工坊");
            case "magic_workshop" -> I18n.string("gui.wandscape.task.group.magic_workshop", "魔法工坊");
            case "node" -> I18n.string("gui.wandscape.task.group.node", "元素节点");
            default -> I18n.string("gui.wandscape.task.group.default", "生产工坊");
        };

        int active = group.activeWorkers();
        int queued = Math.max(0, group.items().size() - active);
        String title = I18n.string("gui.wandscape.task.group.header", "[%s] %s  (制作中: %s | 排队: %s)",
                catName, I18n.buildingName(group.buildingTypeId(), group.buildingName()).getString(),
                String.valueOf(active), String.valueOf(queued));
        g.drawString(font, title, x + 8, y + 8, WandscapeTheme.COLOR_TEXT_ACTIVE, false);

        // [定位] Button
        int btnW = 44;
        int btnX = x + w - btnW - 8;
        int btnY = y + 4;
        boolean hover = mx >= btnX && mx <= btnX + btnW && my >= btnY && my <= btnY + 18;
        g.fill(RenderType.guiOverlay(), btnX, btnY, btnX + btnW, btnY + 18, 0, hover ? 0xEE3E4A5E : 0x882A313D);
        g.drawString(font, locateLabel(), btnX + 6, btnY + 5, 0xFFFFFFFF, false);
    }

    private static void renderProductionItemCard(GuiGraphics g, Font font, int x, int y, int w, ProductionItemDto item, double mx, double my) {
        boolean hover = mx >= x && mx <= x + w && my >= y && my <= y + PROD_CARD_H;
        g.fill(RenderType.guiOverlay(), x, y, x + w, y + PROD_CARD_H, 0, hover ? CARD_BG_HOVER : CARD_BG);

        int accentColor = switch (item.status().toUpperCase()) {
            case "RUNNING" -> 0xFF81C784; // Green
            case "MISSING_ELEMENTS" -> 0xFFE57373; // Red
            default -> 0xFFFFD54F; // Yellow / Queued
        };
        g.fill(RenderType.guiOverlay(), x, y, x + 3, y + PROD_CARD_H, 0, accentColor);

        // Line 1: Index + Display Name × Count + Category Tag
        String prefix = item.queueIndex() == 0
                ? I18n.string("gui.wandscape.task.prod.running_prefix", "[进行中] ")
                : I18n.string("gui.wandscape.task.prod.queued_prefix", "[#%s 排队] ",
                        String.valueOf(item.queueIndex()));
        String title = prefix + TaskText.productionItemName(item) + " × " + item.count();
        g.drawString(font, title, x + 8, y + 6, WandscapeTheme.COLOR_TEXT_NORMAL, false);

        String catTag = "[" + formatCategory(item.category()) + "]";
        g.drawString(font, catTag, x + w - font.width(catTag) - 75, y + 6, WandscapeTheme.COLOR_TEXT_DIM, false);

        // Line 2: Status Details & Elements
        int statusY = y + 22;
        if ("RUNNING".equalsIgnoreCase(item.status())) {
            String npcName = item.assignedNpcName().isEmpty()
                    ? I18n.string("gui.wandscape.task.default_workshop_mage", "工坊法师")
                    : item.assignedNpcName();
            g.drawString(font, I18n.string("gui.wandscape.task.prod.making", "正在制作: %s", npcName),
                    x + 8, statusY, 0xFF81C784, false);

            // Progress Bar
            int barW = w - 160;
            int barY = y + 54;
            g.fill(RenderType.guiOverlay(), x + 8, barY, x + 8 + barW, barY + 4, 0, 0xFF2A2E38);
            int fillW = (int) (barW * Math.clamp(item.progress(), 0f, 1f));
            g.fill(RenderType.guiOverlay(), x + 8, barY, x + 8 + fillW, barY + 4, 0, 0xFF81C784);
        } else if ("MISSING_ELEMENTS".equalsIgnoreCase(item.status())) {
            StringBuilder sb = new StringBuilder(
                    I18n.string("gui.wandscape.task.prod.missing_elements", "缺少元素: "));
            if (item.elementCosts() != null) {
                for (ResourceShortageDto s : item.elementCosts()) {
                    if (s.getMissingAmount() > 0) {
                        sb.append(I18n.string("gui.wandscape.task.prod.short_amount", "%s (缺 %s)  ",
                                TaskText.shortageName(s), String.valueOf(s.getMissingAmount())));
                    }
                }
            }
            g.drawString(font, sb.toString().trim(), x + 8, statusY, 0xFFE57373, false);
        } else {
            g.drawString(font, I18n.string("gui.wandscape.task.prod.elements_ready", "元素充足，等待工坊空闲开工"),
                    x + 8, statusY, 0xFFFFD54F, false);
        }

        // Line 3: Supply Chain / Auto-Gather status / Upstream Source
        int line3Y = y + 38;
        if (item.activeSupplyingGather()) {
            g.drawString(font, I18n.string("gui.wandscape.task.prod.gathering", "元素节点正在自动采集补齐中..."),
                    x + 8, line3Y, 0xFF80DEEA, false);
        } else if ("MISSING_ELEMENTS".equalsIgnoreCase(item.status())) {
            g.drawString(font, I18n.string("gui.wandscape.task.prod.not_gathering",
                            "暂无采集进行中 (需建造元素节点或等待空闲法师)"),
                    x + 8, line3Y, 0xFFFFB74D, false);
        } else {
            String src = item.dependencySource().isEmpty()
                    ? I18n.string("gui.wandscape.task.source.manual", "工坊手动排队")
                    : TaskText.resolve(item.dependencySource());
            g.drawString(font, I18n.string("gui.wandscape.task.prod.source", "来源: %s", src),
                    x + 8, line3Y, 0xFFB0BEC5, false);
        }

        // Action Button: [ 依赖链 ] (Right aligned)
        int btnW = 54;
        int btnX = x + w - btnW - 8;
        int btnY = y + 46;
        int btnH = 20;
        boolean btnHover = mx >= btnX && mx <= btnX + btnW && my >= btnY && my <= btnY + btnH;
        g.fill(RenderType.guiOverlay(), btnX, btnY, btnX + btnW, btnY + btnH, 0, btnHover ? 0xEEC8A040 : 0x885C4B20);
        g.drawString(font, I18n.string("gui.wandscape.task.action.deps", "依赖链"), btnX + 7, btnY + 6, 0xFFFFFFFF, false);
    }

    private static void renderDependencyModal(GuiGraphics g, Font font, int screenW, int screenH, double mx, double my) {
        long selectedId = TaskManagementClientState.getSelectedProductionVirtualId();
        ProductionItemDto targetItem = null;
        ProductionGroupDto targetGroup = null;

        for (ProductionGroupDto grp : TaskManagementClientState.getAllProductionGroups()) {
            for (ProductionItemDto itm : grp.items()) {
                if (itm.virtualOrGlobalId() == selectedId) {
                    targetItem = itm;
                    targetGroup = grp;
                    break;
                }
            }
            if (targetItem != null) break;
        }

        if (targetItem == null || targetGroup == null) {
            TaskManagementClientState.setSelectedProductionVirtualId(-1);
            return;
        }

        // Dim background
        g.fill(RenderType.guiOverlay(), 0, 0, screenW, screenH, 0, 0xCC080B10);

        int modalW = 540;
        int modalH = 310;
        int mx0 = (screenW - modalW) / 2;
        int my0 = (screenH - modalH) / 2;

        // Modal Window
        g.fill(RenderType.guiOverlay(), mx0, my0, mx0 + modalW, my0 + modalH, 0, 0xFA151A24);
        g.fill(RenderType.guiOverlay(), mx0, my0, mx0 + modalW, my0 + 2, 0, BORDER_GOLD);
        g.fill(RenderType.guiOverlay(), mx0, my0 + modalH - 2, mx0 + modalW, my0 + modalH, 0, BORDER_GOLD);
        g.fill(RenderType.guiOverlay(), mx0, my0, mx0 + 2, my0 + modalH, 0, BORDER_GOLD);
        g.fill(RenderType.guiOverlay(), mx0 + modalW - 2, my0, mx0 + modalW, my0 + modalH, 0, BORDER_GOLD);

        // Header Title
        g.drawString(font, I18n.string("gui.wandscape.task.deps.title", "【全链路供应链与依赖溯源】"),
                mx0 + 16, my0 + 14, WandscapeTheme.COLOR_TEXT_ACTIVE, false);

        // Close [X] top right
        int closeX = mx0 + modalW - 24;
        int closeY = my0 + 10;
        boolean closeHover = mx >= closeX && mx <= closeX + 16 && my >= closeY && my <= closeY + 16;
        g.drawString(font, "×", closeX, closeY, closeHover ? 0xFFE53935 : 0xFFB0BEC5, false);

        int contentY = my0 + 36;

        // Step 1: Upstream Trigger
        g.fill(RenderType.guiOverlay(), mx0 + 16, contentY, mx0 + modalW - 16, contentY + 44, 0, 0xEE1E2430);
        g.drawString(font, I18n.string("gui.wandscape.task.deps.step1", "1. 需求发起源头 (Upstream Trigger)"),
                mx0 + 24, contentY + 6, 0xFFFFD54F, false);
        String srcText = targetItem.dependencySource().isEmpty()
                ? I18n.string("gui.wandscape.task.source.manual_production", "工坊手动排队生产")
                : TaskText.resolve(targetItem.dependencySource());
        g.drawString(font, I18n.string("gui.wandscape.task.deps.source", "来源: %s", srcText),
                mx0 + 24, contentY + 22, 0xFFE0E0E0, false);
        contentY += 50;

        // Step 2: Workshop & Recipe
        g.fill(RenderType.guiOverlay(), mx0 + 16, contentY, mx0 + modalW - 16, contentY + 44, 0, 0xEE1E2430);
        g.drawString(font, I18n.string("gui.wandscape.task.deps.step2", "2. 当前生产工坊 (Workshop Node)"),
                mx0 + 24, contentY + 6, 0xFF81C784, false);
        String statusText = "RUNNING".equalsIgnoreCase(targetItem.status())
                ? I18n.string("gui.wandscape.task.deps.state.running", "正在制作中")
                : ("MISSING_ELEMENTS".equalsIgnoreCase(targetItem.status())
                        ? I18n.string("gui.wandscape.task.deps.state.missing", "缺少元素等待补齐")
                        : I18n.string("gui.wandscape.task.deps.state.queued", "队列排队就绪"));
        String wsText = I18n.string("gui.wandscape.task.deps.workshop", "工坊: %s | 产物: %s × %s | 状态: %s",
                I18n.buildingName(targetGroup.buildingTypeId(), targetGroup.buildingName()).getString(),
                TaskText.productionItemName(targetItem),
                String.valueOf(targetItem.count()), statusText);
        g.drawString(font, wsText, mx0 + 24, contentY + 22, 0xFFE0E0E0, false);
        contentY += 50;

        // Step 3: Elements Requirement Breakdown
        g.fill(RenderType.guiOverlay(), mx0 + 16, contentY, mx0 + modalW - 16, contentY + 64, 0, 0xEE1E2430);
        g.drawString(font, I18n.string("gui.wandscape.task.deps.step3", "3. 元素消耗与库存核算 (Element Cost & Stock)"),
                mx0 + 24, contentY + 6, 0xFF4FC3F7, false);
        if (targetItem.elementCosts() != null && !targetItem.elementCosts().isEmpty()) {
            int elemY = contentY + 22;
            for (ResourceShortageDto cost : targetItem.elementCosts()) {
                String elemStr = I18n.string("gui.wandscape.task.deps.element_row",
                        "• %s: 需求 %s | 仓库库存 %s | %s",
                        TaskText.shortageName(cost), String.valueOf(cost.requiredAmount()), String.valueOf(cost.currentAmount()),
                        cost.getMissingAmount() > 0
                                ? I18n.string("gui.wandscape.task.deps.short", "§c缺 %s", String.valueOf(cost.getMissingAmount()))
                                : I18n.string("gui.wandscape.task.deps.ok", "§a满足"));
                g.drawString(font, elemStr, mx0 + 24, elemY, 0xFFE0E0E0, false);
                elemY += 14;
            }
        } else {
            g.drawString(font, I18n.string("gui.wandscape.task.deps.no_elements", "• 本配方无元素消耗（如物品分解/初级转换）"),
                    mx0 + 24, contentY + 22, 0xFFB0BEC5, false);
        }
        contentY += 70;

        // Step 4: Downstream Auto-Supply
        g.fill(RenderType.guiOverlay(), mx0 + 16, contentY, mx0 + modalW - 16, contentY + 44, 0, 0xEE1E2430);
        g.drawString(font, I18n.string("gui.wandscape.task.deps.step4", "4. 自动化补料闭环 (Downstream Auto-Supply)"),
                mx0 + 24, contentY + 6, 0xFFCE93D8, false);
        if (targetItem.activeSupplyingGather()) {
            g.drawString(font, I18n.string("gui.wandscape.task.deps.loop_active",
                            "闭环运转中: 元素节点已自动发布 node:gather 采集任务，法师正在采集中！"),
                    mx0 + 24, contentY + 22, 0xFF80DEEA, false);
        } else if ("MISSING_ELEMENTS".equalsIgnoreCase(targetItem.status())) {
            g.drawString(font, I18n.string("gui.wandscape.task.deps.loop_blocked",
                            "采集阻塞: 当前殖民地缺少对应元素节点建筑，或所有法师均忙碌中。"),
                    mx0 + 24, contentY + 22, 0xFFFFB74D, false);
        } else {
            g.drawString(font, I18n.string("gui.wandscape.task.deps.loop_ready",
                            "物资已就绪: 所需元素已全部齐备，排队就绪即可自动开工。"),
                    mx0 + 24, contentY + 22, 0xFF81C784, false);
        }

        // Bottom [关闭] Button
        int okW = 90;
        int okH = 20;
        int okX = mx0 + (modalW - okW) / 2;
        int okY = my0 + modalH - 26;
        boolean okHover = mx >= okX && mx <= okX + okW && my >= okY && my <= okY + okH;
        g.fill(RenderType.guiOverlay(), okX, okY, okX + okW, okY + okH, 0, okHover ? 0xEE3E4A5E : 0x882A313D);
        g.drawString(font, I18n.string("gui.wandscape.task.action.close", "关闭 (ESC)"), okX + 16, okY + 6, 0xFFFFFFFF, false);
    }

    // ═══════════════════════════════════════════════════════════════════
    // ── 4. Mages Multi-Column Grid ──
    // ═══════════════════════════════════════════════════════════════════

    private static void renderMagesGrid(GuiGraphics g, Font font, int x, int y, int w, int h, double mx, double my) {
        List<MageSummaryDto> mages = TaskManagementClientState.getFilteredMages();

        if (mages.isEmpty()) {
            String empty = I18n.string("gui.wandscape.task.empty.mages", "当前小镇暂无法师");
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
        // 第三方模组登记的殖民地工作者标出来——它们与法师并排显示，
        // 但不吃法师那套属性/魔力，不标一下玩家会以为数据错了
        if (!"npc".equals(mage.kind())) {
            name = Component.translatable("gui.wandscape.task.worker_tag").getString() + " " + name;
        }
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
        String attrStr = I18n.string("gui.wandscape.task.mage.attrs", "法强:%s  工速:%s  护甲:%s",
                String.format("%.1f", mage.spellPower()),
                String.format("%.1f", mage.workSpeed()),
                String.format("%.0f", mage.armorValue()));
        if (!mage.equippedWand().isEmpty()) {
            attrStr += "  |  " + mage.equippedWand();
        }
        g.drawString(font, attrStr, x + 8, y + 36, WandscapeTheme.COLOR_TEXT_DIM, false);

        // Line 4: Action Buttons (Right Aligned) —— 仅法师。
        // 跟随/和平是 WandscapeNpc 的模式开关（服务端 MageModeActionPacket 对非法师直接 warn+return），
        // 面板里还并列着第三方工作者，给它们画这两个按钮只会是点不动的死按钮。
        if ("npc".equals(mage.kind())) {
            renderMageActionButtons(g, font, x + w - 108, y + 52, mage, mx, my);
        }
    }

    private static void renderMageActionButtons(GuiGraphics g, Font font, int x, int y, MageSummaryDto mage, double mx, double my) {
        int btnH = 20;

        // [跟随]
        int followW = 48;
        boolean followHover = mx >= x && mx <= x + followW && my >= y && my <= y + btnH;
        int followBg = mage.followMode() ? 0xFF4CAF50 : (followHover ? 0xEE3E4A5E : 0x882A313D);
        g.fill(RenderType.guiOverlay(), x, y, x + followW, y + btnH, 0, followBg);
        g.drawString(font, mage.followMode()
                        ? I18n.string("gui.wandscape.task.mage.unfollow", "取消跟随")
                        : I18n.string("gui.wandscape.task.mage.follow", "跟随"),
                x + 5, y + 6, 0xFFFFFFFF, false);

        // [和平]
        int peaceX = x + 52;
        int peaceW = 48;
        boolean peaceHover = mx >= peaceX && mx <= peaceX + peaceW && my >= y && my <= y + btnH;
        int peaceBg = mage.peaceMode() ? 0xFF42A5F5 : (peaceHover ? 0xEE3E4A5E : 0x882A313D);
        g.fill(RenderType.guiOverlay(), peaceX, y, peaceX + peaceW, y + btnH, 0, peaceBg);
        g.drawString(font, mage.peaceMode()
                        ? I18n.string("gui.wandscape.task.mage.unpeace", "取消和平")
                        : I18n.string("gui.wandscape.task.mage.peace", "和平"),
                peaceX + 5, y + 6, 0xFFFFFFFF, false);
    }

    // ═══════════════════════════════════════════════════════════════════
    // ── Mouse Click & Scroll Handling ──
    // ═══════════════════════════════════════════════════════════════════

    public static boolean handleMouseClick(double mx, double my, int screenW, int screenH) {
        if (!isActive()) return false;

        // 0. Dependency Modal Interception (if open)
        if (TaskManagementClientState.getSelectedProductionVirtualId() != -1) {
            int modalW = 540;
            int modalH = 310;
            int mx0 = (screenW - modalW) / 2;
            int my0 = (screenH - modalH) / 2;

            int closeX = mx0 + modalW - 24;
            int closeY = my0 + 10;
            int okW = 90;
            int okH = 20;
            int okX = mx0 + (modalW - okW) / 2;
            int okY = my0 + modalH - 26;

            boolean clickedClose = (mx >= closeX && mx <= closeX + 16 && my >= closeY && my <= closeY + 16)
                    || (mx >= okX && mx <= okX + okW && my >= okY && my <= okY + okH)
                    || (mx < mx0 || mx > mx0 + modalW || my < my0 || my > my0 + modalH);

            if (clickedClose) {
                TaskManagementClientState.setSelectedProductionVirtualId(-1);
                playClickSound();
            }
            return true;
        }

        // 1. Header Tab Switcher & Exit
        HeaderLayout lo = layoutHeader(Minecraft.getInstance().font, screenW);
        int curX = lo.startX();
        int btnY = 6;
        int btnH = 22;

        // Tab 1: Tasks
        if (mx >= curX && mx <= curX + lo.taskW() && my >= btnY && my <= btnY + btnH) {
            TaskManagementClientState.setActiveTab(TaskManagementClientState.SubTab.TASKS);
            playClickSound();
            return true;
        }
        curX += lo.taskW() + lo.gap();

        // Tab 2: Production
        if (mx >= curX && mx <= curX + lo.prodW() && my >= btnY && my <= btnY + btnH) {
            TaskManagementClientState.setActiveTab(TaskManagementClientState.SubTab.PRODUCTION);
            playClickSound();
            return true;
        }
        curX += lo.prodW() + lo.gap();

        // Tab 3: Mages
        if (mx >= curX && mx <= curX + lo.mageW() && my >= btnY && my <= btnY + btnH) {
            TaskManagementClientState.setActiveTab(TaskManagementClientState.SubTab.MAGES);
            playClickSound();
            return true;
        }

        // Close Button
        int closeW = HEADER_CLOSE_W;
        int closeX = screenW - closeW - HEADER_CLOSE_GAP;
        if (mx >= closeX && mx <= closeX + closeW && my >= btnY && my <= btnY + btnH) {
            collapseDrawerToOverview();
            playClickSound();
            return true;
        }

        // 2. Toolbar Filters
        int toolbarY = HEADER_H;
        if (my >= toolbarY && my <= toolbarY + TOOLBAR_H) {
            TaskManagementClientState.SubTab tab = TaskManagementClientState.getActiveTab();
            Font font = Minecraft.getInstance().font;
            int fx = 20;
            int fBtnY = toolbarY + 4;
            int fBtnH = 18;

            if (tab == TaskManagementClientState.SubTab.TASKS) {
                for (TaskManagementClientState.TaskFilter f : TaskManagementClientState.TaskFilter.values()) {
                    String label = taskFilterLabel(f);
                    int btnW = font.width(label) + 14;
                    if (mx >= fx && mx <= fx + btnW && my >= fBtnY && my <= fBtnY + fBtnH) {
                        TaskManagementClientState.setActiveFilter(f);
                        playClickSound();
                        return true;
                    }
                    fx += btnW + 6;
                }
            } else if (tab == TaskManagementClientState.SubTab.PRODUCTION) {
                for (TaskManagementClientState.ProductionFilter f : TaskManagementClientState.ProductionFilter.values()) {
                    String label = productionFilterLabel(f);
                    int btnW = font.width(label) + 14;
                    if (mx >= fx && mx <= fx + btnW && my >= fBtnY && my <= fBtnY + fBtnH) {
                        TaskManagementClientState.setActiveProductionFilter(f);
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
                        Net.toServer(new TaskManagementActionPacket(
                                t.taskId(), TaskManagementActionPacket.ACTION_RUSH, 100));
                        playClickSound();
                        return true;
                    }

                    // [取消]
                    int cancelX = btnBaseX + 96;
                    if (mx >= cancelX && mx <= cancelX + 44 && my >= btnBaseY && my <= btnBaseY + btnH2) {
                        Net.toServer(new TaskManagementActionPacket(
                                t.taskId(), TaskManagementActionPacket.ACTION_CANCEL, 0));
                        playClickSound();
                        return true;
                    }
                }
            }
        } else if (tab == TaskManagementClientState.SubTab.PRODUCTION) {
            List<ProductionGroupDto> groups = TaskManagementClientState.getFilteredProductionGroups();
            int scroll = TaskManagementClientState.getProductionScrollOffset();
            int curY = listY - scroll;

            for (ProductionGroupDto grp : groups) {
                // Check Header [定位]
                if (my >= curY && my <= curY + 28) {
                    int btnW = 44;
                    int btnX = padX + availW - btnW - 8;
                    int btnY2 = curY + 4;
                    if (mx >= btnX && mx <= btnX + btnW && my >= btnY2 && my <= btnY2 + 18) {
                        flyToTarget(grp.x(), grp.y(), grp.z());
                        collapseDrawerToOverview();
                        playClickSound();
                        return true;
                    }
                }
                curY += 28 + CARD_GAP;

                // Check Items [依赖链]
                for (ProductionItemDto item : grp.items()) {
                    if (my >= curY && my <= curY + PROD_CARD_H) {
                        int btnW = 54;
                        int btnX = padX + availW - btnW - 8;
                        int btnY2 = curY + 46;
                        int btnH2 = 20;
                        if (mx >= btnX && mx <= btnX + btnW && my >= btnY2 && my <= btnY2 + btnH2) {
                            TaskManagementClientState.setSelectedProductionVirtualId(item.virtualOrGlobalId());
                            playClickSound();
                            return true;
                        }
                    }
                    curY += PROD_CARD_H + CARD_GAP;
                }
                curY += 10;
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
                    // 跟随/和平按钮只画给法师（见 renderMageCard）；这里同步挡掉命中判定，
                    // 否则非法师卡片上会出现一块"看不见但能点、点了没反应"的死区。
                    if (!"npc".equals(m.kind())) continue;
                    int btnBaseX = cx + cardW - 108;
                    int btnBaseY = cy + 52;
                    int btnH2 = 20;

                    // [跟随]
                    if (mx >= btnBaseX && mx <= btnBaseX + 48 && my >= btnBaseY && my <= btnBaseY + btnH2) {
                        Net.toServer(new MageModeActionPacket(
                                m.entityId(), MageModeActionPacket.MODE_FOLLOW, !m.followMode()));
                        playClickSound();
                        return true;
                    }

                    // [和平]
                    int peaceX = btnBaseX + 52;
                    if (mx >= peaceX && mx <= peaceX + 48 && my >= btnBaseY && my <= btnBaseY + btnH2) {
                        Net.toServer(new MageModeActionPacket(
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
        } else if (tab == TaskManagementClientState.SubTab.PRODUCTION) {
            TaskManagementClientState.setProductionScrollOffset(
                    TaskManagementClientState.getProductionScrollOffset() + delta);
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

    /**
     * 筛选按钮文案。渲染与点击命中都调这里——两处各写一份 switch 时，改了文案却漏改命中判定，
     * 按钮的宽度和可点区域就会错位。
     */
    private static String taskFilterLabel(TaskManagementClientState.TaskFilter f) {
        return switch (f) {
            case ALL -> I18n.string("gui.wandscape.task.filter.all", "全部");
            case IN_PROGRESS -> I18n.string("gui.wandscape.task.filter.in_progress", "进行中");
            case AWAITING_RESOURCES -> I18n.string("gui.wandscape.task.filter.awaiting", "缺前置资源");
            case PENDING -> I18n.string("gui.wandscape.task.filter.queued", "排队等待");
            case QUEUED -> I18n.string("gui.wandscape.task.filter.building_queue", "建筑待办");
        };
    }

    /** 生产页筛选按钮文案，见 {@link #taskFilterLabel}。 */
    private static String productionFilterLabel(TaskManagementClientState.ProductionFilter f) {
        return switch (f) {
            case ALL -> I18n.string("gui.wandscape.task.filter.all", "全部");
            case RUNNING -> I18n.string("gui.wandscape.task.filter.running", "正在制作");
            case QUEUED -> I18n.string("gui.wandscape.task.filter.queued", "排队等待");
            case MISSING_ELEMENTS -> I18n.string("gui.wandscape.task.filter.missing", "缺元素/受阻");
        };
    }

    /**
     * 定位按钮文案。动作按钮的宽度是写死的（44/48/54 px），渲染与命中判定两处共用同一份常量，
     * 所以文案得短——换语言时别把标签写长，否则会溢出按钮。
     */
    private static String locateLabel() {
        return I18n.string("gui.wandscape.task.action.locate", "定位");
    }

    private static String formatCategory(String category) {
        return switch (category.toLowerCase()) {
            case "build" -> I18n.string("gui.wandscape.task.cat.build", "建造");
            case "gather" -> I18n.string("gui.wandscape.task.cat.gather", "采集");
            case "craft" -> I18n.string("gui.wandscape.task.cat.craft", "合成");
            case "decompose" -> I18n.string("gui.wandscape.task.cat.decompose", "分解");
            case "guard" -> I18n.string("gui.wandscape.task.cat.guard", "守卫");
            case "altar" -> I18n.string("gui.wandscape.task.cat.altar", "祭坛");
            case "repair" -> I18n.string("gui.wandscape.task.cat.repair", "维修");
            case "queued" -> I18n.string("gui.wandscape.task.cat.queued", "待办");
            default -> I18n.string("gui.wandscape.task.cat.default", "任务");
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
            case "CASTING" -> mage.currentTaskTitle().isEmpty()
                    ? I18n.string("gui.wandscape.task.mage_state.casting", "施法中")
                    : I18n.string("gui.wandscape.task.mage_state.casting_with_task", "施法中: %s",
                            TaskText.mageTaskTitle(mage));
            case "MOVING" -> I18n.string("gui.wandscape.task.mage_state.moving", "前往工作中");
            case "FOLLOWING" -> I18n.string("gui.wandscape.task.mage_state.following", "跟随中");
            case "RESTING" -> I18n.string("gui.wandscape.task.mage_state.resting", "回屋休息中");
            default -> I18n.string("gui.wandscape.task.mage_state.idle", "空闲待命中");
        };
    }
}
