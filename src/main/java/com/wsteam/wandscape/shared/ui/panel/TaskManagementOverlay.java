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
 * RTS-style Compact Drawer for Global Task & Mage Management.
 * Rendered when {@link WandscapePanelState#getActiveSubMode()} is {@code SubMode.TASKS}.
 */
public final class TaskManagementOverlay {

    public static final int DRAWER_W = 240;
    private static final int BG_COLOR = 0xF211141A;
    private static final int BORDER_COLOR = 0xFFC8A040;
    private static final int CARD_BG = 0xD81A1D24;
    private static final int CARD_BG_HOVER = 0xF0242933;

    private static final int TAB_BTN_H = 20;
    private static final int FILTER_BTN_H = 15;
    private static final int TASK_CARD_H = 58;
    private static final int MAGE_CARD_H = 62;
    private static final int CARD_GAP = 5;

    private TaskManagementOverlay() {}

    public static boolean isActive() {
        return WandscapePanelState.isPanelOpen()
                && !WandscapePanelState.isPanelHidden()
                && WandscapePanelState.getActiveSubMode() == WandscapePanelState.SubMode.TASKS;
    }

    public static boolean isOverDrawer(double mx, double my, int screenH) {
        if (!isActive()) return false;
        int x = WandscapePanelOverlay.SIDEBAR_W;
        int y = WandscapePanelOverlay.TOP_BAR_H;
        int h = screenH - y;
        return mx >= x && mx <= x + DRAWER_W && my >= y && my <= y + h;
    }

    public static void render(GuiGraphics g, Font font, int screenW, int screenH, double mx, double my) {
        if (!isActive()) return;

        int x = WandscapePanelOverlay.SIDEBAR_W;
        int y = WandscapePanelOverlay.TOP_BAR_H;
        int h = screenH - y;

        // 1. Drawer Background & Golden Right Border
        g.fill(RenderType.guiOverlay(), x, y, x + DRAWER_W, y + h, 0, BG_COLOR);
        g.fill(RenderType.guiOverlay(), x + DRAWER_W - 1, y, x + DRAWER_W, y + h, 0, BORDER_COLOR);

        int curY = y + 6;

        // 2. Header & Tab Switcher + Collapse Button
        renderHeader(g, font, x + 6, curY, DRAWER_W - 12, mx, my);
        curY += 24;

        TaskManagementClientState.SubTab tab = TaskManagementClientState.getActiveTab();
        if (tab == TaskManagementClientState.SubTab.TASKS) {
            renderTasksView(g, font, x + 6, curY, DRAWER_W - 12, h - (curY - y) - 6, mx, my);
        } else {
            renderMagesView(g, font, x + 6, curY, DRAWER_W - 12, h - (curY - y) - 6, mx, my);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // ── Header & SubTabs ──
    // ═══════════════════════════════════════════════════════════════════

    private static void renderHeader(GuiGraphics g, Font font, int x, int y, int w, double mx, double my) {
        int collapseW = 16;
        int tabTotalW = w - collapseW - 4;
        int halfW = (tabTotalW - 4) / 2;
        TaskManagementClientState.SubTab currentTab = TaskManagementClientState.getActiveTab();

        // Tasks Tab Button
        boolean tasksActive = currentTab == TaskManagementClientState.SubTab.TASKS;
        boolean tasksHover = mx >= x && mx <= x + halfW && my >= y && my <= y + TAB_BTN_H;
        int taskBg = tasksActive ? 0xEE2A313D : (tasksHover ? 0xCC20252E : 0xAA161920);
        g.fill(RenderType.guiOverlay(), x, y, x + halfW, y + TAB_BTN_H, 0, taskBg);
        if (tasksActive) {
            g.fill(RenderType.guiOverlay(), x, y + TAB_BTN_H - 2, x + halfW, y + TAB_BTN_H, 0, BORDER_COLOR);
        }
        String taskLabel = I18n.string("gui.wandscape.panel.tasks.tab_tasks", "📜 任务")
                + " " + TaskManagementClientState.getTotalActiveTasks();
        int taskTextColor = tasksActive ? WandscapeTheme.COLOR_TEXT_ACTIVE : WandscapeTheme.COLOR_TEXT_NORMAL;
        g.drawString(font, taskLabel, x + (halfW - font.width(taskLabel)) / 2, y + 5, taskTextColor, false);

        // Mages Tab Button
        int mageX = x + halfW + 4;
        boolean magesActive = currentTab == TaskManagementClientState.SubTab.MAGES;
        boolean magesHover = mx >= mageX && mx <= mageX + halfW && my >= y && my <= y + TAB_BTN_H;
        int mageBg = magesActive ? 0xEE2A313D : (magesHover ? 0xCC20252E : 0xAA161920);
        g.fill(RenderType.guiOverlay(), mageX, y, mageX + halfW, y + TAB_BTN_H, 0, mageBg);
        if (magesActive) {
            g.fill(RenderType.guiOverlay(), mageX, y + TAB_BTN_H - 2, mageX + halfW, y + TAB_BTN_H, 0, BORDER_COLOR);
        }
        String mageLabel = I18n.string("gui.wandscape.panel.tasks.tab_mages", "🧙 法师")
                + " " + TaskManagementClientState.getTotalMageCount();
        int mageTextColor = magesActive ? WandscapeTheme.COLOR_TEXT_ACTIVE : WandscapeTheme.COLOR_TEXT_NORMAL;
        g.drawString(font, mageLabel, mageX + (halfW - font.width(mageLabel)) / 2, y + 5, mageTextColor, false);

        // Collapse Button [◀]
        int colX = x + tabTotalW + 4;
        boolean colHover = mx >= colX && mx <= colX + collapseW && my >= y && my <= y + TAB_BTN_H;
        g.fill(RenderType.guiOverlay(), colX, y, colX + collapseW, y + TAB_BTN_H, 0, colHover ? 0xEE3E4A5E : 0x881E232B);
        g.drawString(font, "◀", colX + 4, y + 5, colHover ? WandscapeTheme.COLOR_TEXT_ACTIVE : WandscapeTheme.COLOR_TEXT_DIM, false);
    }

    // ═══════════════════════════════════════════════════════════════════
    // ── Tasks View ──
    // ═══════════════════════════════════════════════════════════════════

    private static void renderTasksView(GuiGraphics g, Font font, int x, int y, int w, int h, double mx, double my) {
        // Filter pills row
        int filterY = y;
        TaskManagementClientState.TaskFilter currentFilter = TaskManagementClientState.getActiveFilter();
        TaskManagementClientState.TaskFilter[] filters = TaskManagementClientState.TaskFilter.values();
        int filterW = (w - (filters.length - 1) * 2) / filters.length;

        for (int i = 0; i < filters.length; i++) {
            int fx = x + i * (filterW + 2);
            boolean active = filters[i] == currentFilter;
            boolean hover = mx >= fx && mx <= fx + filterW && my >= filterY && my <= filterY + FILTER_BTN_H;
            int bg = active ? 0xFFC8A040 : (hover ? 0x883A3E4A : 0x5520242C);
            g.fill(RenderType.guiOverlay(), fx, filterY, fx + filterW, filterY + FILTER_BTN_H, 0, bg);
            String name = I18n.string(filters[i].getTranslationKey(), filters[i].name());
            int txtColor = active ? 0xFF111214 : WandscapeTheme.COLOR_TEXT_NORMAL;
            g.drawString(font, name, fx + (filterW - font.width(name)) / 2, filterY + 3, txtColor, false);
        }

        // List Area with Scissors
        int listY = filterY + FILTER_BTN_H + 4;
        int listH = h - (FILTER_BTN_H + 4);
        List<TaskSummaryDto> tasks = TaskManagementClientState.getFilteredTasks();

        if (tasks.isEmpty()) {
            String empty = I18n.string("gui.wandscape.panel.tasks.empty", "暂无任务");
            g.drawString(font, empty, x + (w - font.width(empty)) / 2, listY + 30, WandscapeTheme.COLOR_TEXT_DIM, false);
            return;
        }

        int totalHeight = tasks.size() * (TASK_CARD_H + CARD_GAP);
        int maxScroll = Math.max(0, totalHeight - listH);
        int scroll = Math.min(TaskManagementClientState.getTaskScrollOffset(), maxScroll);

        g.enableScissor(x - 2, listY, x + w + 2, listY + listH);

        int renderY = listY - scroll;
        for (TaskSummaryDto task : tasks) {
            if (renderY + TASK_CARD_H >= listY && renderY <= listY + listH) {
                renderTaskCard(g, font, x, renderY, w, task, mx, my);
            }
            renderY += TASK_CARD_H + CARD_GAP;
        }

        g.disableScissor();

        // Scrollbar
        if (maxScroll > 0) {
            int sbX = x + w - 2;
            int sbH = Math.max(12, (listH * listH) / totalHeight);
            int sbY = listY + (int) ((float) scroll / maxScroll * (listH - sbH));
            g.fill(RenderType.guiOverlay(), sbX, listY, sbX + 2, listY + listH, 0, 0x33FFFFFF);
            g.fill(RenderType.guiOverlay(), sbX, sbY, sbX + 2, sbY + sbH, 0, 0xFFAAAAAA);
        }
    }

    private static void renderTaskCard(GuiGraphics g, Font font, int x, int y, int w, TaskSummaryDto task, double mx, double my) {
        boolean hover = mx >= x && mx <= x + w && my >= y && my <= y + TASK_CARD_H;
        int bg = hover ? CARD_BG_HOVER : CARD_BG;
        g.fill(RenderType.guiOverlay(), x, y, x + w, y + TASK_CARD_H, 0, bg);
        g.fill(RenderType.guiOverlay(), x, y, x + 2, y + TASK_CARD_H, 0, getStateAccentColor(task.state()));

        // Title + Priority
        String pTag = "[P" + task.priority() + "] ";
        String title = pTag + task.title();
        if (font.width(title) > w - 62) {
            title = font.plainSubstrByWidth(title, w - 68) + "...";
        }
        g.drawString(font, title, x + 5, y + 4, WandscapeTheme.COLOR_TEXT_NORMAL, false);

        // Status Line
        int statusY = y + 15;
        if ("IN_PROGRESS".equalsIgnoreCase(task.state())) {
            String mage = task.assignedNpcName().isEmpty() ? "法师" : task.assignedNpcName();
            String stepStr = " (" + (task.stepIndex() + 1) + "/" + task.totalSteps() + ")";
            String status = "🟢 " + mage + stepStr;
            g.drawString(font, status, x + 5, statusY, 0xFF81C784, false);

            // Progress Bar
            int barW = w - 68;
            g.fill(RenderType.guiOverlay(), x + 5, y + 43, x + 5 + barW, y + 46, 0, 0xFF2A2E38);
            int fillW = (int) (barW * task.getProgress());
            g.fill(RenderType.guiOverlay(), x + 5, y + 43, x + 5 + fillW, y + 46, 0, 0xFFC8A040);
        } else if ("AWAITING_RESOURCES".equalsIgnoreCase(task.state())) {
            if (task.shortages() != null && !task.shortages().isEmpty()) {
                ResourceShortageDto s = task.shortages().getFirst();
                String shortStr = "🔴 缺" + s.displayName() + " " + s.currentAmount() + "/" + s.requiredAmount();
                g.drawString(font, shortStr, x + 5, statusY, 0xFFE57373, false);
            } else {
                g.drawString(font, "🔴 缺少前置资源", x + 5, statusY, 0xFFE57373, false);
            }
        } else if ("PENDING_ASSIGN".equalsIgnoreCase(task.state())) {
            String reason = "WAITING_NPC".equals(task.blockerReason()) ? "⚠️ 等待空闲法师" : "🟡 排队等待中";
            g.drawString(font, reason, x + 5, statusY, 0xFFFFD54F, false);
        } else if ("QUEUED".equalsIgnoreCase(task.state())) {
            g.drawString(font, "⚪ 建筑队列排队中", x + 5, statusY, 0xFFB0BEC5, false);
        }

        // Action Buttons on Card (Right Side)
        renderTaskActionButtons(g, font, x + w - 58, y + 36, task, mx, my);
    }

    private static void renderTaskActionButtons(GuiGraphics g, Font font, int x, int y, TaskSummaryDto task, double mx, double my) {
        int btnW = 16;
        int btnH = 14;

        // Locate Button [🔍]
        if (task.hasTargetPos()) {
            boolean hover = mx >= x && mx <= x + btnW && my >= y && my <= y + btnH;
            g.fill(RenderType.guiOverlay(), x, y, x + btnW, y + btnH, 0, hover ? 0xCC3E4A5E : 0x882A313D);
            g.drawString(font, "🔍", x + 2, y + 3, 0xFFFFFFFF, false);
        }

        // Rush / Priority Button [⚡]
        int rushX = x + 19;
        boolean rushHover = mx >= rushX && mx <= rushX + btnW && my >= y && my <= y + btnH;
        g.fill(RenderType.guiOverlay(), rushX, y, rushX + btnW, y + btnH, 0, rushHover ? 0xCCC8A040 : 0x885C4B20);
        g.drawString(font, "⚡", rushX + 2, y + 3, 0xFFFFFFFF, false);

        // Cancel Button [✕]
        int cancelX = x + 38;
        boolean cancelHover = mx >= cancelX && mx <= cancelX + btnW && my >= y && my <= y + btnH;
        g.fill(RenderType.guiOverlay(), cancelX, y, cancelX + btnW, y + btnH, 0, cancelHover ? 0xCCE53935 : 0x885C2020);
        g.drawString(font, "✕", cancelX + 3, y + 3, 0xFFFFFFFF, false);
    }

    // ═══════════════════════════════════════════════════════════════════
    // ── Mages View ──
    // ═══════════════════════════════════════════════════════════════════

    private static void renderMagesView(GuiGraphics g, Font font, int x, int y, int w, int h, double mx, double my) {
        List<MageSummaryDto> mages = TaskManagementClientState.getFilteredMages();

        if (mages.isEmpty()) {
            String empty = I18n.string("gui.wandscape.panel.mages.empty", "暂无法师");
            g.drawString(font, empty, x + (w - font.width(empty)) / 2, y + 30, WandscapeTheme.COLOR_TEXT_DIM, false);
            return;
        }

        int totalHeight = mages.size() * (MAGE_CARD_H + CARD_GAP);
        int maxScroll = Math.max(0, totalHeight - h);
        int scroll = Math.min(TaskManagementClientState.getMageScrollOffset(), maxScroll);

        g.enableScissor(x - 2, y, x + w + 2, y + h);

        int renderY = y - scroll;
        for (MageSummaryDto mage : mages) {
            if (renderY + MAGE_CARD_H >= y && renderY <= y + h) {
                renderMageCard(g, font, x, renderY, w, mage, mx, my);
            }
            renderY += MAGE_CARD_H + CARD_GAP;
        }

        g.disableScissor();

        if (maxScroll > 0) {
            int sbX = x + w - 2;
            int sbH = Math.max(12, (h * h) / totalHeight);
            int sbY = y + (int) ((float) scroll / maxScroll * (h - sbH));
            g.fill(RenderType.guiOverlay(), sbX, y, sbX + 2, y + h, 0, 0x33FFFFFF);
            g.fill(RenderType.guiOverlay(), sbX, sbY, sbX + 2, sbY + sbH, 0, 0xFFAAAAAA);
        }
    }

    private static void renderMageCard(GuiGraphics g, Font font, int x, int y, int w, MageSummaryDto mage, double mx, double my) {
        boolean hover = mx >= x && mx <= x + w && my >= y && my <= y + MAGE_CARD_H;
        int bg = hover ? CARD_BG_HOVER : CARD_BG;
        g.fill(RenderType.guiOverlay(), x, y, x + w, y + MAGE_CARD_H, 0, bg);
        g.fill(RenderType.guiOverlay(), x, y, x + 2, y + MAGE_CARD_H, 0, getMageStateAccentColor(mage.state()));

        // Name + State Badge
        String name = "🧙 " + mage.name();
        g.drawString(font, name, x + 5, y + 4, WandscapeTheme.COLOR_TEXT_ACTIVE, false);

        String stateTag = formatMageState(mage);
        g.drawString(font, stateTag, x + 5, y + 15, getMageStateTextColor(mage.state()), false);

        // HP & Mana Bars
        int barW = 45;
        int barY = y + 27;
        // HP Bar
        g.fill(RenderType.guiOverlay(), x + 5, barY, x + 5 + barW, barY + 3, 0, 0xFF3E2723);
        int hpFill = (int) (barW * mage.getHealthRatio());
        g.fill(RenderType.guiOverlay(), x + 5, barY, x + 5 + hpFill, barY + 3, 0, 0xFF4CAF50);

        // Mana Bar
        g.fill(RenderType.guiOverlay(), x + 54, barY, x + 54 + barW, barY + 3, 0, 0xFF0D47A1);
        int manaFill = (int) (barW * mage.getManaRatio());
        g.fill(RenderType.guiOverlay(), x + 54, barY, x + 54 + manaFill, barY + 3, 0, 0xFF42A5F5);

        // Attributes line
        String attrStr = String.format("⚡%.1f 🔨%.1f 🛡%.0f", mage.spellPower(), mage.workSpeed(), mage.armorValue());
        g.drawString(font, attrStr, x + 5, y + 36, WandscapeTheme.COLOR_TEXT_DIM, false);

        // Action Buttons
        renderMageActionButtons(g, font, x + w - 76, y + 42, mage, mx, my);
    }

    private static void renderMageActionButtons(GuiGraphics g, Font font, int x, int y, MageSummaryDto mage, double mx, double my) {
        int btnW = 16;
        int btnH = 14;

        // Focus [🔍]
        boolean focusHover = mx >= x && mx <= x + btnW && my >= y && my <= y + btnH;
        g.fill(RenderType.guiOverlay(), x, y, x + btnW, y + btnH, 0, focusHover ? 0xCC3E4A5E : 0x882A313D);
        g.drawString(font, "🔍", x + 2, y + 3, 0xFFFFFFFF, false);

        // Track Camera [🎥]
        int trackX = x + 19;
        boolean isTracking = TaskManagementClientState.getTrackingEntityId() == mage.entityId();
        boolean trackHover = mx >= trackX && mx <= trackX + btnW && my >= y && my <= y + btnH;
        int trackBg = isTracking ? 0xFFC8A040 : (trackHover ? 0xCC3E4A5E : 0x882A313D);
        g.fill(RenderType.guiOverlay(), trackX, y, trackX + btnW, y + btnH, 0, trackBg);
        g.drawString(font, "🎥", trackX + 1, y + 3, isTracking ? 0xFF111214 : 0xFFFFFFFF, false);

        // Follow Toggle [🛡]
        int followX = x + 38;
        boolean followHover = mx >= followX && mx <= followX + btnW && my >= y && my <= y + btnH;
        int followBg = mage.followMode() ? 0xFF4CAF50 : (followHover ? 0xCC3E4A5E : 0x882A313D);
        g.fill(RenderType.guiOverlay(), followX, y, followX + btnW, y + btnH, 0, followBg);
        g.drawString(font, "🛡", followX + 3, y + 3, 0xFFFFFFFF, false);

        // Peace Toggle [🕊]
        int peaceX = x + 57;
        boolean peaceHover = mx >= peaceX && mx <= peaceX + btnW && my >= y && my <= y + btnH;
        int peaceBg = mage.peaceMode() ? 0xFF42A5F5 : (peaceHover ? 0xCC3E4A5E : 0x882A313D);
        g.fill(RenderType.guiOverlay(), peaceX, y, peaceX + btnW, y + btnH, 0, peaceBg);
        g.drawString(font, "🕊", peaceX + 2, y + 3, 0xFFFFFFFF, false);
    }

    // ═══════════════════════════════════════════════════════════════════
    // ── Mouse Click & Scroll Handling ──
    // ═══════════════════════════════════════════════════════════════════

    public static boolean handleMouseClick(double mx, double my, int screenH) {
        if (!isActive()) return false;
        int x = WandscapePanelOverlay.SIDEBAR_W + 6;
        int y = WandscapePanelOverlay.TOP_BAR_H + 6;
        int w = DRAWER_W - 12;
        int h = screenH - WandscapePanelOverlay.TOP_BAR_H - 12;

        int collapseW = 16;
        int tabTotalW = w - collapseW - 4;
        int halfW = (tabTotalW - 4) / 2;

        // 1. Header Tab Switcher & Collapse Button
        if (my >= y && my <= y + TAB_BTN_H) {
            if (mx >= x && mx <= x + halfW) {
                TaskManagementClientState.setActiveTab(TaskManagementClientState.SubTab.TASKS);
                playClickSound();
                return true;
            }
            int mageX = x + halfW + 4;
            if (mx >= mageX && mx <= mageX + halfW) {
                TaskManagementClientState.setActiveTab(TaskManagementClientState.SubTab.MAGES);
                playClickSound();
                return true;
            }
            int colX = x + tabTotalW + 4;
            if (mx >= colX && mx <= colX + collapseW) {
                collapseDrawerToOverview();
                playClickSound();
                return true;
            }
        }

        TaskManagementClientState.SubTab tab = TaskManagementClientState.getActiveTab();
        if (tab == TaskManagementClientState.SubTab.TASKS) {
            // Filter pills
            int filterY = y + 24;
            TaskManagementClientState.TaskFilter[] filters = TaskManagementClientState.TaskFilter.values();
            int filterW = (w - (filters.length - 1) * 2) / filters.length;
            if (my >= filterY && my <= filterY + FILTER_BTN_H) {
                for (int i = 0; i < filters.length; i++) {
                    int fx = x + i * (filterW + 2);
                    if (mx >= fx && mx <= fx + filterW) {
                        TaskManagementClientState.setActiveFilter(filters[i]);
                        playClickSound();
                        return true;
                    }
                }
            }

            // Task list clicks
            int listY = filterY + FILTER_BTN_H + 4;
            int listH = h - (listY - y);
            if (my >= listY && my <= listY + listH) {
                List<TaskSummaryDto> tasks = TaskManagementClientState.getFilteredTasks();
                int scroll = TaskManagementClientState.getTaskScrollOffset();
                int curY = listY - scroll;

                for (TaskSummaryDto t : tasks) {
                    if (my >= curY && my <= curY + TASK_CARD_H) {
                        // Action buttons
                        int btnX = x + w - 58;
                        int btnY = curY + 36;
                        if (my >= btnY && my <= btnY + 14) {
                            if (t.hasTargetPos() && mx >= btnX && mx <= btnX + 16) {
                                flyToTarget(t.targetX(), t.targetY(), t.targetZ());
                                collapseDrawerToOverview();
                                playClickSound();
                                return true;
                            }
                            int rushX = btnX + 19;
                            if (mx >= rushX && mx <= rushX + 16) {
                                PacketDistributor.sendToServer(new TaskManagementActionPacket(
                                        t.taskId(), TaskManagementActionPacket.ACTION_RUSH, 100));
                                playClickSound();
                                return true;
                            }
                            int cancelX = btnX + 38;
                            if (mx >= cancelX && mx <= cancelX + 16) {
                                PacketDistributor.sendToServer(new TaskManagementActionPacket(
                                        t.taskId(), TaskManagementActionPacket.ACTION_CANCEL, 0));
                                playClickSound();
                                return true;
                            }
                        }
                    }
                    curY += TASK_CARD_H + CARD_GAP;
                }
            }
        } else {
            // Mages list clicks
            int listY = y + 24;
            int listH = h - 24;
            if (my >= listY && my <= listY + listH) {
                List<MageSummaryDto> mages = TaskManagementClientState.getFilteredMages();
                int scroll = TaskManagementClientState.getMageScrollOffset();
                int curY = listY - scroll;

                for (MageSummaryDto m : mages) {
                    if (my >= curY && my <= curY + MAGE_CARD_H) {
                        int btnX = x + w - 76;
                        int btnY = curY + 42;
                        if (my >= btnY && my <= btnY + 14) {
                            if (mx >= btnX && mx <= btnX + 16) {
                                flyToTarget(m.posX(), m.posY(), m.posZ());
                                collapseDrawerToOverview();
                                playClickSound();
                                return true;
                            }
                            int trackX = btnX + 19;
                            if (mx >= trackX && mx <= trackX + 16) {
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
                            int followX = btnX + 38;
                            if (mx >= followX && mx <= followX + 16) {
                                PacketDistributor.sendToServer(new MageModeActionPacket(
                                        m.entityId(), MageModeActionPacket.MODE_FOLLOW, !m.followMode()));
                                playClickSound();
                                return true;
                            }
                            int peaceX = btnX + 57;
                            if (mx >= peaceX && mx <= peaceX + 16) {
                                PacketDistributor.sendToServer(new MageModeActionPacket(
                                        m.entityId(), MageModeActionPacket.MODE_PEACE, !m.peaceMode()));
                                playClickSound();
                                return true;
                            }
                        }
                    }
                    curY += MAGE_CARD_H + CARD_GAP;
                }
            }
        }
        return false;
    }

    public static boolean handleMouseScroll(double deltaY) {
        if (!isActive()) return false;
        TaskManagementClientState.SubTab tab = TaskManagementClientState.getActiveTab();
        int delta = deltaY > 0 ? -24 : 24;

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
        // SubMode is changed to OVERVIEW: closes drawer but keeps Overview flight active!
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
            case "CASTING" -> "🔨 正在施法";
            case "MOVING" -> "🚶 前往工作中";
            case "FOLLOWING" -> "🛡️ 跟随警戒中";
            case "RESTING" -> "💤 回屋休息中";
            default -> "🍵 空闲待命中";
        };
    }
}
