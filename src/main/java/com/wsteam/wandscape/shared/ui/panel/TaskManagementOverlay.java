package com.wsteam.wandscape.shared.ui.panel;

import java.util.List;

import org.joml.Matrix4f;

import com.wsteam.wandscape.engine.sound.WandscapeSounds;
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
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * RTS-style Frosted Glass Drawer for Global Task & Mage Management.
 * Rendered when {@link WandscapePanelState#getActiveSubMode()} is {@code SubMode.TASKS}.
 */
public final class TaskManagementOverlay {

    public static final int DRAWER_W = 310;
    private static final int BG_COLOR = 0xF011141A;
    private static final int BORDER_COLOR = 0xFFC8A040;
    private static final int CARD_BG = 0xCC1A1D24;
    private static final int CARD_BG_HOVER = 0xEE242933;
    private static final int CARD_BORDER = 0xFF353B47;

    private static final int TAB_BTN_H = 22;
    private static final int FILTER_BTN_H = 16;
    private static final int TASK_CARD_H = 68;
    private static final int MAGE_CARD_H = 74;
    private static final int CARD_GAP = 6;

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

        int curY = y + 8;

        // 2. Header & Tab Switcher
        renderHeader(g, font, x + 8, curY, DRAWER_W - 16, mx, my);
        curY += 28;

        TaskManagementClientState.SubTab tab = TaskManagementClientState.getActiveTab();
        if (tab == TaskManagementClientState.SubTab.TASKS) {
            renderTasksView(g, font, x + 8, curY, DRAWER_W - 16, h - (curY - y) - 8, mx, my);
        } else {
            renderMagesView(g, font, x + 8, curY, DRAWER_W - 16, h - (curY - y) - 8, mx, my);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // ── Header & SubTabs ──
    // ═══════════════════════════════════════════════════════════════════

    private static void renderHeader(GuiGraphics g, Font font, int x, int y, int w, double mx, double my) {
        int halfW = (w - 6) / 2;
        TaskManagementClientState.SubTab currentTab = TaskManagementClientState.getActiveTab();

        // Tasks Tab Button
        boolean tasksActive = currentTab == TaskManagementClientState.SubTab.TASKS;
        boolean tasksHover = mx >= x && mx <= x + halfW && my >= y && my <= y + TAB_BTN_H;
        int taskBg = tasksActive ? 0xEE2A313D : (tasksHover ? 0xCC20252E : 0xAA161920);
        g.fill(RenderType.guiOverlay(), x, y, x + halfW, y + TAB_BTN_H, 0, taskBg);
        if (tasksActive) {
            g.fill(RenderType.guiOverlay(), x, y + TAB_BTN_H - 2, x + halfW, y + TAB_BTN_H, 0, BORDER_COLOR);
        }
        String taskLabel = I18n.string("gui.wandscape.panel.tasks.tab_tasks", "📜 任务大厅")
                + " (" + TaskManagementClientState.getTotalActiveTasks() + ")";
        int taskTextColor = tasksActive ? WandscapeTheme.COLOR_TEXT_ACTIVE : WandscapeTheme.COLOR_TEXT_NORMAL;
        g.drawString(font, taskLabel, x + (halfW - font.width(taskLabel)) / 2, y + 6, taskTextColor, false);

        // Mages Tab Button
        int mageX = x + halfW + 6;
        boolean magesActive = currentTab == TaskManagementClientState.SubTab.MAGES;
        boolean magesHover = mx >= mageX && mx <= mageX + halfW && my >= y && my <= y + TAB_BTN_H;
        int mageBg = magesActive ? 0xEE2A313D : (magesHover ? 0xCC20252E : 0xAA161920);
        g.fill(RenderType.guiOverlay(), mageX, y, mageX + halfW, y + TAB_BTN_H, 0, mageBg);
        if (magesActive) {
            g.fill(RenderType.guiOverlay(), mageX, y + TAB_BTN_H - 2, mageX + halfW, y + TAB_BTN_H, 0, BORDER_COLOR);
        }
        String mageLabel = I18n.string("gui.wandscape.panel.tasks.tab_mages", "🧙 法师名册")
                + " (" + TaskManagementClientState.getTotalMageCount() + ")";
        int mageTextColor = magesActive ? WandscapeTheme.COLOR_TEXT_ACTIVE : WandscapeTheme.COLOR_TEXT_NORMAL;
        g.drawString(font, mageLabel, mageX + (halfW - font.width(mageLabel)) / 2, y + 6, mageTextColor, false);
    }

    // ═══════════════════════════════════════════════════════════════════
    // ── Tasks View ──
    // ═══════════════════════════════════════════════════════════════════

    private static void renderTasksView(GuiGraphics g, Font font, int x, int y, int w, int h, double mx, double my) {
        // Filter pills row
        int filterY = y;
        TaskManagementClientState.TaskFilter currentFilter = TaskManagementClientState.getActiveFilter();
        TaskManagementClientState.TaskFilter[] filters = TaskManagementClientState.TaskFilter.values();
        int filterW = (w - (filters.length - 1) * 3) / filters.length;

        for (int i = 0; i < filters.length; i++) {
            int fx = x + i * (filterW + 3);
            boolean active = filters[i] == currentFilter;
            boolean hover = mx >= fx && mx <= fx + filterW && my >= filterY && my <= filterY + FILTER_BTN_H;
            int bg = active ? 0xFFC8A040 : (hover ? 0x883A3E4A : 0x5520242C);
            g.fill(RenderType.guiOverlay(), fx, filterY, fx + filterW, filterY + FILTER_BTN_H, 0, bg);
            String name = I18n.string(filters[i].getTranslationKey(), filters[i].name());
            int txtColor = active ? 0xFF111214 : WandscapeTheme.COLOR_TEXT_NORMAL;
            g.drawString(font, name, fx + (filterW - font.width(name)) / 2, filterY + 4, txtColor, false);
        }

        // List Area with Scissors
        int listY = filterY + FILTER_BTN_H + 6;
        int listH = h - (FILTER_BTN_H + 6);
        List<TaskSummaryDto> tasks = TaskManagementClientState.getFilteredTasks();

        if (tasks.isEmpty()) {
            String empty = I18n.string("gui.wandscape.panel.tasks.empty", "当前分类下暂无任务");
            g.drawString(font, empty, x + (w - font.width(empty)) / 2, listY + 30, WandscapeTheme.COLOR_TEXT_DIM, false);
            return;
        }

        int totalHeight = tasks.size() * (TASK_CARD_H + CARD_GAP);
        int maxScroll = Math.max(0, totalHeight - listH);
        int scroll = Math.min(TaskManagementClientState.getTaskScrollOffset(), maxScroll);

        g.enableScissor(x - 4, listY, x + w + 4, listY + listH);

        int renderY = listY - scroll;
        for (TaskSummaryDto task : tasks) {
            if (renderY + TASK_CARD_H >= listY && renderY <= listY + listH) {
                renderTaskCard(g, font, x, renderY, w, task, mx, my);
            }
            renderY += TASK_CARD_H + CARD_GAP;
        }

        g.disableScissor();

        // Scrollbar if needed
        if (maxScroll > 0) {
            int sbX = x + w - 3;
            int sbH = Math.max(16, (listH * listH) / totalHeight);
            int sbY = listY + (int) ((float) scroll / maxScroll * (listH - sbH));
            g.fill(RenderType.guiOverlay(), sbX, listY, sbX + 3, listY + listH, 0, 0x44FFFFFF);
            g.fill(RenderType.guiOverlay(), sbX, sbY, sbX + 3, sbY + sbH, 0, 0xFFAAAAAA);
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
        if (font.width(title) > w - 70) {
            title = font.plainSubstrByWidth(title, w - 76) + "...";
        }
        g.drawString(font, title, x + 6, y + 6, WandscapeTheme.COLOR_TEXT_NORMAL, false);

        // Status Line
        int statusY = y + 18;
        if ("IN_PROGRESS".equalsIgnoreCase(task.state())) {
            String mage = task.assignedNpcName().isEmpty() ? "法师" : task.assignedNpcName();
            String stepStr = " (" + (task.stepIndex() + 1) + "/" + task.totalSteps() + ")";
            String status = "🟢 正在执行: " + mage + stepStr;
            g.drawString(font, status, x + 6, statusY, 0xFF81C784, false);

            // Progress Bar
            int barW = w - 12;
            g.fill(RenderType.guiOverlay(), x + 6, y + 32, x + 6 + barW, y + 35, 0, 0xFF2A2E38);
            int fillW = (int) (barW * task.getProgress());
            g.fill(RenderType.guiOverlay(), x + 6, y + 32, x + 6 + fillW, y + 35, 0, 0xFFC8A040);
        } else if ("AWAITING_RESOURCES".equalsIgnoreCase(task.state())) {
            g.drawString(font, "🔴 缺少前置资源", x + 6, statusY, 0xFFE57373, false);
            // Shortages tags
            if (task.shortages() != null && !task.shortages().isEmpty()) {
                ResourceShortageDto s = task.shortages().getFirst();
                String shortStr = s.displayName() + " " + s.currentAmount() + "/" + s.requiredAmount() + " (缺" + s.getMissingAmount() + ")";
                g.drawString(font, shortStr, x + 6, y + 32, 0xFFFFB74D, false);
            }
        } else if ("PENDING_ASSIGN".equalsIgnoreCase(task.state())) {
            String reason = "WAITING_NPC".equals(task.blockerReason()) ? "⚠️ 等待空闲法师" : "🟡 排队等待中";
            g.drawString(font, reason, x + 6, statusY, 0xFFFFD54F, false);
        } else if ("QUEUED".equalsIgnoreCase(task.state())) {
            g.drawString(font, "⚪ 建筑队列排队中", x + 6, statusY, 0xFFB0BEC5, false);
        }

        // Action Buttons on Card (Right Side)
        renderTaskActionButtons(g, font, x + w - 68, y + 44, task, mx, my);
    }

    private static void renderTaskActionButtons(GuiGraphics g, Font font, int x, int y, TaskSummaryDto task, double mx, double my) {
        // Locate Button [🔍]
        if (task.hasTargetPos()) {
            boolean hover = mx >= x && mx <= x + 18 && my >= y && my <= y + 16;
            g.fill(RenderType.guiOverlay(), x, y, x + 18, y + 16, 0, hover ? 0xCC3E4A5E : 0x882A313D);
            g.drawString(font, "🔍", x + 3, y + 4, 0xFFFFFFFF, false);
        }

        // Rush / Priority Button [⚡]
        int rushX = x + 22;
        boolean rushHover = mx >= rushX && mx <= rushX + 18 && my >= y && my <= y + 16;
        g.fill(RenderType.guiOverlay(), rushX, y, rushX + 18, y + 16, 0, rushHover ? 0xCCC8A040 : 0x885C4B20);
        g.drawString(font, "⚡", rushX + 3, y + 4, 0xFFFFFFFF, false);

        // Cancel Button [❌]
        int cancelX = x + 44;
        boolean cancelHover = mx >= cancelX && mx <= cancelX + 18 && my >= y && my <= y + 16;
        g.fill(RenderType.guiOverlay(), cancelX, y, cancelX + 18, y + 16, 0, cancelHover ? 0xCCE53935 : 0x885C2020);
        g.drawString(font, "✕", cancelX + 4, y + 4, 0xFFFFFFFF, false);
    }

    // ═══════════════════════════════════════════════════════════════════
    // ── Mages View ──
    // ═══════════════════════════════════════════════════════════════════

    private static void renderMagesView(GuiGraphics g, Font font, int x, int y, int w, int h, double mx, double my) {
        List<MageSummaryDto> mages = TaskManagementClientState.getFilteredMages();

        if (mages.isEmpty()) {
            String empty = I18n.string("gui.wandscape.panel.mages.empty", "当前小镇暂无法师");
            g.drawString(font, empty, x + (w - font.width(empty)) / 2, y + 30, WandscapeTheme.COLOR_TEXT_DIM, false);
            return;
        }

        int totalHeight = mages.size() * (MAGE_CARD_H + CARD_GAP);
        int maxScroll = Math.max(0, totalHeight - h);
        int scroll = Math.min(TaskManagementClientState.getMageScrollOffset(), maxScroll);

        g.enableScissor(x - 4, y, x + w + 4, y + h);

        int renderY = y - scroll;
        for (MageSummaryDto mage : mages) {
            if (renderY + MAGE_CARD_H >= y && renderY <= y + h) {
                renderMageCard(g, font, x, renderY, w, mage, mx, my);
            }
            renderY += MAGE_CARD_H + CARD_GAP;
        }

        g.disableScissor();

        if (maxScroll > 0) {
            int sbX = x + w - 3;
            int sbH = Math.max(16, (h * h) / totalHeight);
            int sbY = y + (int) ((float) scroll / maxScroll * (h - sbH));
            g.fill(RenderType.guiOverlay(), sbX, y, sbX + 3, y + h, 0, 0x44FFFFFF);
            g.fill(RenderType.guiOverlay(), sbX, sbY, sbX + 3, sbY + sbH, 0, 0xFFAAAAAA);
        }
    }

    private static void renderMageCard(GuiGraphics g, Font font, int x, int y, int w, MageSummaryDto mage, double mx, double my) {
        boolean hover = mx >= x && mx <= x + w && my >= y && my <= y + MAGE_CARD_H;
        int bg = hover ? CARD_BG_HOVER : CARD_BG;
        g.fill(RenderType.guiOverlay(), x, y, x + w, y + MAGE_CARD_H, 0, bg);
        g.fill(RenderType.guiOverlay(), x, y, x + 2, y + MAGE_CARD_H, 0, getMageStateAccentColor(mage.state()));

        // Name + State Badge
        String name = "🧙 " + mage.name();
        g.drawString(font, name, x + 6, y + 6, WandscapeTheme.COLOR_TEXT_ACTIVE, false);

        String stateTag = formatMageState(mage);
        g.drawString(font, stateTag, x + 6, y + 18, getMageStateTextColor(mage.state()), false);

        // HP & Mana Bars
        int barW = 100;
        int barY = y + 30;
        // HP Bar
        g.fill(RenderType.guiOverlay(), x + 6, barY, x + 6 + barW, barY + 4, 0, 0xFF3E2723);
        int hpFill = (int) (barW * mage.getHealthRatio());
        g.fill(RenderType.guiOverlay(), x + 6, barY, x + 6 + hpFill, barY + 4, 0, 0xFF4CAF50);

        // Mana Bar
        g.fill(RenderType.guiOverlay(), x + 112, barY, x + 112 + barW, barY + 4, 0, 0xFF0D47A1);
        int manaFill = (int) (barW * mage.getManaRatio());
        g.fill(RenderType.guiOverlay(), x + 112, barY, x + 112 + manaFill, barY + 4, 0, 0xFF42A5F5);

        // Attributes line
        String attrStr = String.format("⚡法强: %.1fx  🔨工速: %.1fx", mage.spellPower(), mage.workSpeed());
        g.drawString(font, attrStr, x + 6, y + 42, WandscapeTheme.COLOR_TEXT_DIM, false);

        // Action Buttons
        renderMageActionButtons(g, font, x + w - 88, y + 52, mage, mx, my);
    }

    private static void renderMageActionButtons(GuiGraphics g, Font font, int x, int y, MageSummaryDto mage, double mx, double my) {
        // Focus [🔍]
        boolean focusHover = mx >= x && mx <= x + 18 && my >= y && my <= y + 16;
        g.fill(RenderType.guiOverlay(), x, y, x + 18, y + 16, 0, focusHover ? 0xCC3E4A5E : 0x882A313D);
        g.drawString(font, "🔍", x + 3, y + 4, 0xFFFFFFFF, false);

        // Track Camera [🎥]
        int trackX = x + 22;
        boolean isTracking = TaskManagementClientState.getTrackingEntityId() == mage.entityId();
        boolean trackHover = mx >= trackX && mx <= trackX + 18 && my >= y && my <= y + 16;
        int trackBg = isTracking ? 0xFFC8A040 : (trackHover ? 0xCC3E4A5E : 0x882A313D);
        g.fill(RenderType.guiOverlay(), trackX, y, trackX + 18, y + 16, 0, trackBg);
        g.drawString(font, "🎥", trackX + 2, y + 4, isTracking ? 0xFF111214 : 0xFFFFFFFF, false);

        // Follow Toggle [🛡️]
        int followX = x + 44;
        boolean followHover = mx >= followX && mx <= followX + 18 && my >= y && my <= y + 16;
        int followBg = mage.followMode() ? 0xFF4CAF50 : (followHover ? 0xCC3E4A5E : 0x882A313D);
        g.fill(RenderType.guiOverlay(), followX, y, followX + 18, y + 16, 0, followBg);
        g.drawString(font, "🛡", followX + 4, y + 4, 0xFFFFFFFF, false);

        // Peace Toggle [🕊]
        int peaceX = x + 66;
        boolean peaceHover = mx >= peaceX && mx <= peaceX + 18 && my >= y && my <= y + 16;
        int peaceBg = mage.peaceMode() ? 0xFF42A5F5 : (peaceHover ? 0xCC3E4A5E : 0x882A313D);
        g.fill(RenderType.guiOverlay(), peaceX, y, peaceX + 18, y + 16, 0, peaceBg);
        g.drawString(font, "🕊", peaceX + 3, y + 4, 0xFFFFFFFF, false);
    }

    // ═══════════════════════════════════════════════════════════════════
    // ── Mouse Click & Scroll Handling ──
    // ═══════════════════════════════════════════════════════════════════

    public static boolean handleMouseClick(double mx, double my, int screenH) {
        if (!isActive()) return false;
        int x = WandscapePanelOverlay.SIDEBAR_W + 8;
        int y = WandscapePanelOverlay.TOP_BAR_H + 8;
        int w = DRAWER_W - 16;
        int h = screenH - WandscapePanelOverlay.TOP_BAR_H - 16;

        // 1. Header Tab Switcher
        int halfW = (w - 6) / 2;
        if (my >= y && my <= y + TAB_BTN_H) {
            if (mx >= x && mx <= x + halfW) {
                TaskManagementClientState.setActiveTab(TaskManagementClientState.SubTab.TASKS);
                playClickSound();
                return true;
            }
            int mageX = x + halfW + 6;
            if (mx >= mageX && mx <= mageX + halfW) {
                TaskManagementClientState.setActiveTab(TaskManagementClientState.SubTab.MAGES);
                playClickSound();
                return true;
            }
        }

        TaskManagementClientState.SubTab tab = TaskManagementClientState.getActiveTab();
        if (tab == TaskManagementClientState.SubTab.TASKS) {
            // Filter pills
            int filterY = y + 28;
            TaskManagementClientState.TaskFilter[] filters = TaskManagementClientState.TaskFilter.values();
            int filterW = (w - (filters.length - 1) * 3) / filters.length;
            if (my >= filterY && my <= filterY + FILTER_BTN_H) {
                for (int i = 0; i < filters.length; i++) {
                    int fx = x + i * (filterW + 3);
                    if (mx >= fx && mx <= fx + filterW) {
                        TaskManagementClientState.setActiveFilter(filters[i]);
                        playClickSound();
                        return true;
                    }
                }
            }

            // Task list clicks
            int listY = filterY + FILTER_BTN_H + 6;
            int listH = h - (listY - y);
            if (my >= listY && my <= listY + listH) {
                List<TaskSummaryDto> tasks = TaskManagementClientState.getFilteredTasks();
                int scroll = TaskManagementClientState.getTaskScrollOffset();
                int curY = listY - scroll;

                for (TaskSummaryDto t : tasks) {
                    if (my >= curY && my <= curY + TASK_CARD_H) {
                        // Action buttons
                        int btnX = x + w - 68;
                        int btnY = curY + 44;
                        if (my >= btnY && my <= btnY + 16) {
                            if (t.hasTargetPos() && mx >= btnX && mx <= btnX + 18) {
                                flyToTarget(t.targetX(), t.targetY(), t.targetZ());
                                playClickSound();
                                return true;
                            }
                            int rushX = btnX + 22;
                            if (mx >= rushX && mx <= rushX + 18) {
                                PacketDistributor.sendToServer(new TaskManagementActionPacket(
                                        t.taskId(), TaskManagementActionPacket.ACTION_RUSH, 100));
                                playClickSound();
                                return true;
                            }
                            int cancelX = btnX + 44;
                            if (mx >= cancelX && mx <= cancelX + 18) {
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
            int listY = y + 28;
            int listH = h - 28;
            if (my >= listY && my <= listY + listH) {
                List<MageSummaryDto> mages = TaskManagementClientState.getFilteredMages();
                int scroll = TaskManagementClientState.getMageScrollOffset();
                int curY = listY - scroll;

                for (MageSummaryDto m : mages) {
                    if (my >= curY && my <= curY + MAGE_CARD_H) {
                        int btnX = x + w - 88;
                        int btnY = curY + 52;
                        if (my >= btnY && my <= btnY + 16) {
                            if (mx >= btnX && mx <= btnX + 18) {
                                flyToTarget(m.posX(), m.posY(), m.posZ());
                                playClickSound();
                                return true;
                            }
                            int trackX = btnX + 22;
                            if (mx >= trackX && mx <= trackX + 18) {
                                int current = TaskManagementClientState.getTrackingEntityId();
                                int next = current == m.entityId() ? -1 : m.entityId();
                                TaskManagementClientState.setTrackingEntityId(next);
                                playClickSound();
                                return true;
                            }
                            int followX = btnX + 44;
                            if (mx >= followX && mx <= followX + 18) {
                                PacketDistributor.sendToServer(new MageModeActionPacket(
                                        m.entityId(), MageModeActionPacket.MODE_FOLLOW, !m.followMode()));
                                playClickSound();
                                return true;
                            }
                            int peaceX = btnX + 66;
                            if (mx >= peaceX && mx <= peaceX + 18) {
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

    private static void flyToTarget(double x, double y, double z) {
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
            case "CASTING" -> "🔨 " + (mage.currentTaskTitle().isEmpty() ? "正在施法" : mage.currentTaskTitle());
            case "MOVING" -> "🚶 正在前往工作地点";
            case "FOLLOWING" -> "🛡️ 跟随玩家警戒中";
            case "RESTING" -> "💤 回屋冥想休息中";
            default -> "🍵 空闲待命中";
        };
    }
}
