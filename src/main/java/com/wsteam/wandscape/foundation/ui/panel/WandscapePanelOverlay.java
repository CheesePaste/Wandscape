package com.wsteam.wandscape.foundation.ui.panel;
import com.wsteam.wandscape.content.building.ui.BuildingSelectionOverlay;
import com.wsteam.wandscape.content.colony.network.ColonyPanelClientState;
import com.wsteam.wandscape.content.colony.roster.ColonyRole;
import com.wsteam.wandscape.content.task.ui.TaskManagementOverlay;

import com.mojang.blaze3d.systems.RenderSystem;
import com.wsteam.wandscape.content.building.projection.client.BuildPopPanelOverlay;
import com.wsteam.wandscape.content.building.projection.client.BuildingDebugClientState;
import com.wsteam.wandscape.content.building.projection.client.ProjectionClientState;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.ui.I18n;
import com.wsteam.wandscape.foundation.ui.theme.WandscapeTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Renders the Wandscape panel overlay on top of the game HUD.
 * Layout: full-width top HUD bar + left sidebar with mode tabs.
 */
public final class WandscapePanelOverlay {

    private static final String TAG = "WandscapePanelOverlay";

    // ── Layout constants ──
    public static final int TOP_BAR_H = 28;
    public static final int SIDEBAR_W = 28;
    public static final int SIDEBAR_ICON_S = 24;
    public static final int SIDEBAR_GAP = 8;
    /**
     * Sidebar tab count — single source for rendering, hit-testing and the 1..N number keys.
     * Order: 0 建造 / 1 道路 / 2 小镇 / 3 任务 / 4 设置.
     */
    public static final int SIDEBAR_TAB_COUNT = 5;

    private static final int BAR_BG = 0xEE14161C;
    private static final int SIDEBAR_BG = 0xAA111214;
    private static final int OVERLAY_BG = 0xEE111214;

    private static boolean registered = false;

    private WandscapePanelOverlay() {}

    public static void register() {
        if (registered) return;
        registered = true;
        NeoForge.EVENT_BUS.register(WandscapePanelOverlay.class);
        Log.info(TAG, "[Panel] Overlay registered");
    }

    @SubscribeEvent
    public static void onRenderGuiPost(RenderGuiEvent.Post event) {
        if (!WandscapePanelState.isPanelOpen() || WandscapePanelState.isPanelHidden()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        if (mc.screen != null) return;

        GuiGraphics g = event.getGuiGraphics();
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();

        double guiScale = mc.getWindow().getGuiScale();
        double mx = mc.mouseHandler.xpos() / guiScale;
        double my = mc.mouseHandler.ypos() / guiScale;

        // Flush any pending HUD batches (chat text etc.) BEFORE rendering the panel, so the
        // panel content composites above them instead of sharing a sorted text buffer.
        g.flush();

        // Clear the depth buffer so depth-tested panel elements (sidebar icons, 3D previews)
        // are not culled by depth written by earlier HUD/chat batches.
        RenderSystem.clearDepth(1.0);
        RenderSystem.clear(256, Minecraft.ON_OSX);

        // Building selection bar
        BuildingSelectionOverlay.render(g, mc.font, screenW, screenH, mx, my);

        // Build mode right pop panel
        BuildPopPanelOverlay.render(g, mc.font, screenW, screenH, mx, my);

        // Task & Mage Management Hub (dedicated spacious overlay — hides top bar & sidebar)
        if (WandscapePanelState.getActiveSubMode() == WandscapePanelState.SubMode.TASKS) {
            TaskManagementOverlay.render(g, mc.font, screenW, screenH, mx, my);
            g.bufferSource().endBatch(RenderType.guiOverlay());
            g.flush();
            return;
        }

        // Settings Center Hub (dedicated spacious overlay — hides top bar & sidebar)
        if (WandscapePanelState.getActiveSubMode() == WandscapePanelState.SubMode.SETTINGS) {
            com.wsteam.wandscape.foundation.ui.settings.SettingsOverlay.render(g, mc.font, screenW, screenH, mx, my);
            g.bufferSource().endBatch(RenderType.guiOverlay());
            g.flush();
            return;
        }

        renderFills(g, mc.font, screenW, screenH, mx, my);
        g.bufferSource().endBatch(RenderType.guiOverlay());
        renderTexts(g, mc.font, screenW, screenH, mx, my);

        // Push the panel's remaining text/previews out in this pass so they stay above chat.
        g.flush();
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Fills ──
    // ═══════════════════════════════════════════════════════════════

    private static void renderFills(GuiGraphics g, Font font, int screenW, int screenH, double mx, double my) {
        // 1. Top bar background
        UUID cid = WandscapePanelState.getColonyId();
        if (cid != null) {
            g.fill(RenderType.guiOverlay(), 0, 0, screenW, TOP_BAR_H, 0, BAR_BG);
            g.fill(RenderType.guiOverlay(), 0, TOP_BAR_H - 1, screenW, TOP_BAR_H, 0, 0xFFC8A040);
        }

        // 2. Sidebar
        renderSidebar(g, screenW, screenH, mx, my);
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Sidebar ──
    // ═══════════════════════════════════════════════════════════════

    private static void renderSidebar(GuiGraphics g, int screenW, int screenH, double mx, double my) {
        int x = 0;
        int y = TOP_BAR_H;
        int h = screenH - TOP_BAR_H;

        g.fill(RenderType.guiOverlay(), x, y, x + SIDEBAR_W, y + h, 0, SIDEBAR_BG);

        int startY = y + 8;
        int totalIconH = SIDEBAR_ICON_S + SIDEBAR_GAP;

        net.minecraft.resources.ResourceLocation[] tabIcons = {
            WandscapeTheme.ICON_TAB_BUILD,
            WandscapeTheme.ICON_TAB_ROAD,
            WandscapeTheme.ICON_TAB_COLONY,
            WandscapeTheme.ICON_TAB_EDITOR,
            WandscapeTheme.ICON_TAB_SETTINGS
        };

        WandscapePanelState.SubMode activeMode = WandscapePanelState.getActiveSubMode();
        int hoveredIcon = getSidebarHoveredIcon(mx, my, screenH);

        // 建造 / 道路 / 小镇 / 任务 / 设置五个 tab（位置 0-4 对齐 1/2/3/4/5 数字键）
        for (int i = 0; i < tabIcons.length; i++) {
            int iy = startY + i * totalIconH;
            int ix = (SIDEBAR_W - SIDEBAR_ICON_S) / 2;
            int color = isTabActive(i, activeMode) ? WandscapeTheme.COLOR_TEXT_ACTIVE : WandscapeTheme.COLOR_TEXT_NORMAL;
            WandscapeTheme.drawIcon(g, tabIcons[i], ix, iy, SIDEBAR_ICON_S, SIDEBAR_ICON_S, color);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Texts ──
    // ═══════════════════════════════════════════════════════════════

    private static void renderTexts(GuiGraphics g, Font font, int screenW, int screenH, double mx, double my) {
        UUID cid = WandscapePanelState.getColonyId();
        if (cid != null) {
            renderTopBar(g, font, screenW, mx, my);
        }

        // First-time guidance
        if (com.wsteam.wandscape.foundation.ui.tutorial.TutorialSession.shouldShow()) {
            boolean buildMode = WandscapePanelState.getActiveSubMode() == WandscapePanelState.SubMode.BUILD_PROJECTION;
            boolean isPlacing = WandscapePanelState.getBuildPhase() == WandscapePanelState.BuildPhase.PLACING;
            boolean isBar = WandscapePanelState.getBuildPhase() == WandscapePanelState.BuildPhase.BAR;
            boolean isPinned = ProjectionClientState.isPinned();
            com.wsteam.wandscape.foundation.ui.tutorial.TutorialRenderer.render(g, font, screenW, screenH, mx, my,
                    com.wsteam.wandscape.foundation.ui.tutorial.TutorialRegistry.step(
                            com.wsteam.wandscape.foundation.ui.tutorial.TutorialSession.currentStep()),
                    buildMode, isPlacing, isBar, isPinned);
        }

        // Stats content (shifted right of sidebar)
        if (WandscapePanelState.getActiveSubMode() == WandscapePanelState.SubMode.STATS) {
            renderStatsContent(g, font, screenW, screenH);
        }

        // Colony panel (shifted right of sidebar — the sidebar stays visible here,
        // unlike the TASKS/SETTINGS hubs, so the other tabs remain clickable)
        if (WandscapePanelState.getActiveSubMode() == WandscapePanelState.SubMode.COLONY) {
            renderColonyPanel(g, font, screenW, screenH, mx, my);
        }

        // Sidebar tab tooltips
        int hoveredIcon = getSidebarHoveredIcon(mx, my, screenH);
        if (hoveredIcon >= 0 && WandscapePanelState.isCursorLifted()) {
            net.minecraft.network.chat.Component tip = switch (hoveredIcon) {
                case 0 -> I18n.name("gui.wandscape.panel.tab.build", "建造 (1)");
                case 1 -> I18n.name("gui.wandscape.panel.tab.road", "道路 (2)");
                case 2 -> I18n.name("gui.wandscape.panel.tab.colony", "小镇 (3)");
                case 3 -> I18n.name("gui.wandscape.panel.tab.tasks", "任务 (4)");
                case 4 -> I18n.name("gui.wandscape.panel.tab.settings", "设置中心 (5)");
                default -> null;
            };
            if (tip != null) {
                g.renderTooltip(font, tip, (int) mx, (int) my);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Colony panel：我的小镇 / 待处理邀请 / 成员 / 可邀请玩家 ──
    //
    // 「当前镇」只有一份真源：WandscapePanelState.getColonyId()（服务端 ColonyStatsSyncPacket
    // 推来的当前镇，顶栏/边界/设置/本面板共用）。本面板**不维护自己的选中项**——高亮、成员表、
    // 邀请入口的档位全部按那个 id 到 ColonyPanelClientState 的花名册缓存里取，所以
    // 「面板切了但顶栏不变」不可能发生（没有第二份状态可漂移）。
    // 点击一行 = 发 SELECT 意图；服务端 setActive 后统一推送，高亮随之移动（服务端权威）。
    // 渲染与命中检测共用同一份几何与同一份数据视图（computeGeo + view + *Rect + colonyLines），
    // 避免两处各算一遍漂移。
    // ═══════════════════════════════════════════════════════════════

    // ── 几何常量：面板内所有坐标都由这些常量算出，别在方法里撒魔法数 ──
    /** 面板距侧边栏/顶栏/屏幕边的间距。 */
    private static final int CP_PAD = 8;
    /** 面板内边距（标题与两列内容距面板边框）。 */
    private static final int CP_INNER_PAD = 6;
    /** 左右两列之间的间距。 */
    private static final int CP_COL_GAP = 8;
    /** 面板标题行高。 */
    private static final int CP_TITLE_H = 14;
    /** 分区标题行高。 */
    private static final int CP_SECTION_H = 11;
    /** 列表单行高（行背景占 CP_ROW_H-1，余 1px 作行距）。 */
    private static final int CP_ROW_H = 13;
    /** 两段之间的竖向间隔。 */
    private static final int CP_SEC_GAP = 4;
    /** 按钮文字左右内边距。 */
    private static final int CP_BTN_PAD = 6;
    /** 面板最小宽/最大宽/最小高、列最小宽——装不下就整块不画（早退，不画残）。 */
    private static final int CP_MIN_W = 240;
    private static final int CP_MAX_W = 420;
    private static final int CP_MIN_H = 96;
    private static final int CP_MIN_COL_W = 92;
    /** 首段目标行数：小镇列表 4 行、成员列表 6 行，剩余高度给各自的第二段。 */
    private static final int CP_TARGET_COLONY_ROWS = 4;
    private static final int CP_TARGET_MEMBER_ROWS = 6;

    private static final int COLOR_ROW_HOVER = 0x33FFFFFF;
    private static final int COLOR_ROW_SELECTED = 0x33C8A040;
    private static final int COLOR_DISABLED_BORDER = 0xFF2E323C;
    private static final int COLOR_DISABLED_TEXT = 0xFF5C6068;

    /** 面板四个分区；顺序即渲染顺序，也是滚动偏移数组的下标。 */
    public enum ColonySection { COLONIES, INVITES, MEMBERS, ONLINE }

    /** 命中类型；index 是列表里的**绝对**行下标（不是可见行内的偏移），未命中为 -1。 */
    public enum ColonyHit { MISS, COLONY_ROW, INVITE_ACCEPT, INVITE_DECLINE, MEMBER_ROLE, MEMBER_REMOVE,
                            TRANSFER_TOGGLE, MEMBER_TRANSFER, ONLINE_INVITE, INVITE_ROLE }

    public record ColonyHitResult(ColonyHit hit, int index) {}

    private static final ColonyHitResult NO_HIT = new ColonyHitResult(ColonyHit.MISS, -1);

    /** 渲染/动作共用的行视图（T2 数据 → 本面板要的最小字段）。 */
    public record ColonyRow(UUID id, String name, int level, ColonyRole role) {}
    public record InviteRow(UUID colonyId, String colonyName, String inviterName, ColonyRole role) {}
    public record MemberRow(UUID id, String name, ColonyRole role) {}
    public record OnlineRow(UUID id, String name) {}

    /**
     * 小镇列表的一「行」：分组标题 / 引导提示 / 一座镇。
     *
     * <p>三者共用 {@link #CP_ROW_H} 行高——命中检测只按行高算下标，行高不一就得再养一套几何。
     * {@code rowIndex} 是这一行在 {@link #colonyRows()} 里的下标（标题/提示为 -1），
     * 命中结果只吐 rowIndex，所以 controller 依旧按 colonyRows() 取行。
     */
    private record ColonyLine(@Nullable ColonyRow row, int rowIndex, @Nullable String label, int labelColor) {
        static ColonyLine of(ColonyRow row, int rowIndex) {
            return new ColonyLine(row, rowIndex, null, 0);
        }

        static ColonyLine label(String text, int color) {
            return new ColonyLine(null, -1, text, color);
        }
    }

    /** 各分区滚动偏移（单位：行）。渲染、命中、滚轮三处共用，切走再切回保留浏览位置。 */
    private static final int[] COLONY_SCROLL = new int[ColonySection.values().length];

    /**
     * 邀请档位的可选顺序——第一个是默认档位（契约要求默认 MEMBER）。
     * 实际候选再按「低于自己档位」过滤（见 {@link #inviteRoleOptions()}）：MANAGER 只能邀 MEMBER/ALLY。
     */
    private static final ColonyRole[] INVITE_ROLE_CYCLE = { ColonyRole.MEMBER, ColonyRole.ALLY, ColonyRole.MANAGER };
    private static int inviteRoleIndex = 0;

    /**
     * 「转让镇长」模式：开启后成员行右侧的「调档位 / 移除」换成单个【转让】，点谁就把镇长让给谁。
     *
     * <p>为什么要一个模式、而不是给每行再加一个转让按钮：转让是**唯一**会把镇长交出去的操作，
     * 误点代价最大，而一行塞三个按钮既挤又正好和【移除】挨着。换成模式后，同一时刻一行只有一个
     * 动作可点——想调档位/移除就先退出转让模式，点错也点不到别的。
     *
     * <p>纯客户端 UI 状态（服务端照旧权威重判），与 {@link #COLONY_SCROLL} 同族；随「离开小镇页」
     * （{@code WandscapePanelState.exitCurrentSubMode}）与「当前镇/我的档位变了」一起复位。
     */
    private static boolean transferMode = false;
    /** 进入转让模式时的小镇 id；当前镇换了就自动作废（见 {@link #syncTransferMode}）。 */
    @Nullable
    private static UUID transferModeColony = null;

    private record ColonyGeo(int x, int y, int w, int h,
                             int leftX, int rightX, int colW,
                             int coloniesBodyY, int coloniesRows,
                             int invitesBodyY, int invitesRows,
                             int membersBodyY, int membersRows,
                             int onlineBodyY, int onlineRows) {}

    /** 一个分区的可见窗口：bodyY 起、可见行 [from,to)，行高 CP_ROW_H。 */
    private record SectionView(int bodyY, int rows, int from, int to) {
        int rowY(int index) {
            return bodyY + (index - from) * CP_ROW_H;
        }

        /** 命中这一分区的哪一行（绝对下标）；不在可见行内返回 -1。 */
        int indexAt(double my) {
            if (rows <= 0 || my < bodyY) return -1;
            int rel = (int) ((my - bodyY) / CP_ROW_H);
            if (rel >= rows) return -1;
            int index = from + rel;
            return index < to ? index : -1;
        }
    }

    /** 纯代码按钮矩形；渲染与命中检测共用同一个，避免两处各算一遍漂移。 */
    private record ButtonRect(int x, int y, int w, int h) {
        boolean contains(double mx, double my) {
            return mx >= x && mx <= x + w && my >= y && my <= y + h;
        }
    }

    /** 面板几何：渲染与命中检测的唯一来源，改布局只改这里。装不下返回 null。 */
    private static ColonyGeo computeGeo(int screenW, int screenH) {
        int x = SIDEBAR_W + CP_PAD;
        int y = TOP_BAR_H + CP_PAD;
        int w = Math.min(CP_MAX_W, screenW - x - CP_PAD);
        int h = screenH - y - CP_PAD;
        if (w < CP_MIN_W || h < CP_MIN_H) return null;

        int colW = (w - CP_INNER_PAD * 2 - CP_COL_GAP) / 2;
        if (colW < CP_MIN_COL_W) return null;
        int leftX = x + CP_INNER_PAD;
        int rightX = leftX + colW + CP_COL_GAP;

        int bodyTop = y + CP_TITLE_H;
        int avail = h - CP_TITLE_H - CP_INNER_PAD;
        if (avail < CP_SECTION_H + CP_ROW_H) return null;
        int per = avail - CP_SEC_GAP;

        // 左列：小镇列表 / 待处理邀请；右列：成员 / 可邀请玩家
        int coloniesSec = firstSectionH(CP_SECTION_H + CP_TARGET_COLONY_ROWS * CP_ROW_H, per);
        int membersSec = firstSectionH(CP_SECTION_H + CP_TARGET_MEMBER_ROWS * CP_ROW_H, per);
        int invitesSec = per - coloniesSec;
        int onlineSec = per - membersSec;

        return new ColonyGeo(x, y, w, h, leftX, rightX, colW,
                bodyTop + CP_SECTION_H, sectionRows(coloniesSec),
                bodyTop + coloniesSec + CP_SEC_GAP + CP_SECTION_H, sectionRows(invitesSec),
                bodyTop + CP_SECTION_H, sectionRows(membersSec),
                bodyTop + membersSec + CP_SEC_GAP + CP_SECTION_H, sectionRows(onlineSec));
    }

    /** 首段高度：目标值夹在「至少标题+一行」与「给第二段留标题+一行」之间。 */
    private static int firstSectionH(int target, int avail) {
        int minH = CP_SECTION_H + CP_ROW_H;
        int maxH = Math.max(minH, avail - minH);
        return Math.max(minH, Math.min(target, maxH));
    }

    private static int sectionRows(int sectionH) {
        return Math.max(0, (sectionH - CP_SECTION_H) / CP_ROW_H);
    }

    private static boolean contains(ColonyGeo geo, double mx, double my) {
        return geo != null && mx >= geo.x() && mx <= geo.x() + geo.w()
                && my >= geo.y() && my <= geo.y() + geo.h();
    }

    /** 把某分区的滚动偏移夹到 [0, 溢出量]，返回夹取后的起始行。 */
    private static int clampedScroll(ColonySection section, int rows, int count) {
        int max = Math.max(0, count - rows);
        int cur = Math.min(Math.max(0, COLONY_SCROLL[section.ordinal()]), max);
        COLONY_SCROLL[section.ordinal()] = cur;
        return cur;
    }

    private static SectionView view(ColonyGeo geo, ColonySection section, int count) {
        int rows = switch (section) {
            case COLONIES -> geo.coloniesRows();
            case INVITES -> geo.invitesRows();
            case MEMBERS -> geo.membersRows();
            case ONLINE -> geo.onlineRows();
        };
        int bodyY = switch (section) {
            case COLONIES -> geo.coloniesBodyY();
            case INVITES -> geo.invitesBodyY();
            case MEMBERS -> geo.membersBodyY();
            case ONLINE -> geo.onlineBodyY();
        };
        int from = clampedScroll(section, rows, count);
        return new SectionView(bodyY, rows, from, Math.min(count, from + rows));
    }

    // ── Rendering ──

    /**
     * 小镇面板：左列「小镇列表 / 待处理邀请」，右列「成员 / 可邀请玩家」。
     *
     * <p>面板只占侧边栏右侧的区域，侧边栏保持可见、其它 tab 仍可点（与 TASKS/SETTINGS 那种
     * 整屏 hub 不同）。数据全走 ColonyPanelClientState 的按 colonyId 缓存，当前镇取
     * {@link #currentColonyId()}（服务端推送的唯一真源）；未收到同步时各分区显示空态、按钮置灰。
     */
    private static void renderColonyPanel(GuiGraphics g, Font font, int screenW, int screenH,
                                          double mx, double my) {
        try {
            ColonyGeo geo = computeGeo(screenW, screenH);
            if (geo == null) return;

            UUID current = currentColonyId();
            // 转让模式只在「当前镇 + 我是该镇镇长」下成立：切换了镇、或被降级/转让出去，下一帧就复位
            syncTransferMode(current);

            boolean hover = WandscapePanelState.isCursorLifted();

            WandscapeTheme.drawRtsBox(g, geo.x(), geo.y(), geo.w(), geo.h(), true, false);
            drawText(g, font, I18n.name("gui.wandscape.colony_switch.title", "我的小镇").getString(),
                    geo.x() + CP_INNER_PAD, geo.y() + 3, WandscapeTheme.COLOR_TEXT_ACTIVE);

            ColonyRole myRole = currentRole();
            boolean inviteAllowed = canInvite();

            renderColonySection(g, font, geo, mx, my, hover);
            renderInviteSection(g, font, geo, inviteRows(), mx, my, hover);
            renderMemberSection(g, font, geo, memberRows(), current != null, mx, my, hover);
            renderOnlineSection(g, font, geo, inviteAllowed ? onlineRows() : List.of(),
                    current != null, myRole, mx, my, hover);
        } catch (Throwable t) {
            // 每帧都跑的 HUD 路径：数据来自网络镜像 + 在线玩家表，出任何异常都不许把 HUD 打崩。
            Log.warn(TAG, "[Colony] 面板渲染失败: {}", t.toString());
        }
    }

    private static void renderColonySection(GuiGraphics g, Font font, ColonyGeo geo,
                                            double mx, double my, boolean hover) {
        List<ColonyLine> lines = colonyLines();
        SectionView v = view(geo, ColonySection.COLONIES, lines.size());
        UUID current = currentColonyId();
        drawSectionTitle(g, font, geo.leftX(), geo.coloniesBodyY() - CP_SECTION_H, geo.colW(),
                I18n.name("gui.wandscape.colony_switch.section.mine", "小镇列表").getString(), v, lines.size());

        for (int i = v.from(); i < v.to(); i++) {
            ColonyLine line = lines.get(i);
            int rowY = v.rowY(i);

            // 分组标题 / 建镇引导：不是可点的镇行，恒不点亮 hover、恒不产生命中。
            if (line.row() == null) {
                drawText(g, font, truncate(font, line.label(), geo.colW() - 6), geo.leftX() + 3, rowY + 2,
                        line.labelColor());
                continue;
            }

            ColonyRow row = line.row();
            boolean switchable = canSwitch(row.role());
            boolean isCurrent = current != null && current.equals(row.id());
            boolean rowHover = switchable && rowHovered(v, i, hover, mx, my, geo.leftX(), geo.colW());

            if (isCurrent) {
                g.fill(RenderType.guiOverlay(), geo.leftX(), rowY, geo.leftX() + geo.colW(),
                        rowY + CP_ROW_H - 1, COLOR_ROW_SELECTED);
                drawBorder(g, geo.leftX(), rowY, geo.colW(), CP_ROW_H - 1, WandscapeTheme.COLOR_BORDER_ACTIVE);
            } else if (rowHover) {
                g.fill(RenderType.guiOverlay(), geo.leftX(), rowY, geo.leftX() + geo.colW(),
                        rowY + CP_ROW_H - 1, COLOR_ROW_HOVER);
            }

            // ALLY 行（不可切换）整行置灰、且不点亮 hover：点不动的事视觉上就得先说清楚。
            int textColor = !switchable ? COLOR_DISABLED_TEXT
                    : (isCurrent ? WandscapeTheme.COLOR_TEXT_ACTIVE
                    : (rowHover ? WandscapeTheme.COLOR_TEXT_NORMAL : WandscapeTheme.COLOR_TEXT_DIM));
            WandscapeTheme.drawIcon(g, WandscapeTheme.ICON_TAB_COLONY, geo.leftX() + 2, rowY + 1, 10, 10, textColor);

            String right = isCurrent
                    ? I18n.name("gui.wandscape.colony_switch.current", "当前").getString()
                    : roleName(row.role());
            int rightW = font.width(right);
            int rightColor = !switchable ? COLOR_DISABLED_TEXT
                    : (isCurrent ? WandscapeTheme.COLOR_TEXT_ACTIVE : WandscapeTheme.COLOR_TEXT_DIM);
            String name = (row.name() == null || row.name().isEmpty()) ? shortId(row.id()) : row.name();
            String label = I18n.name("gui.wandscape.colony_switch.entry", "%s  Lv.%s", name, row.level()).getString();
            drawText(g, font, truncate(font, label, geo.colW() - 16 - rightW), geo.leftX() + 14, rowY + 2, textColor);
            drawText(g, font, right, geo.leftX() + geo.colW() - 2 - rightW, rowY + 2, rightColor);
        }
    }

    /**
     * 小镇列表的数据视图：按「我拥有的（OWNER）/ 我参与的」分组，并在没有自己的镇时补一条建镇引导。
     *
     * <p>渲染、命中、滚动三处共用它。注意与 {@link #colonyRows()} 的分工：后者是纯镇行的**动作视图**，
     * controller 按它取行；命中结果里的 index 也一律是 colonyRows() 的下标（不是本列表的行下标）。
     */
    private static List<ColonyLine> colonyLines() {
        List<ColonyRow> rows = colonyRows();
        List<ColonyLine> lines = new ArrayList<>(rows.size() + 3);
        boolean ownedHeader = false;
        boolean joinedHeader = false;
        boolean ownsAny = false;

        if (rows.isEmpty()) {
            lines.add(ColonyLine.label(I18n.name("gui.wandscape.colony_switch.empty",
                    "尚未加入任何小镇").getString(), WandscapeTheme.COLOR_TEXT_DIM));
        }
        for (int i = 0; i < rows.size(); i++) {
            ColonyRow row = rows.get(i);
            if (row.role() == ColonyRole.OWNER) {
                ownsAny = true;
                if (!ownedHeader) {
                    lines.add(ColonyLine.label(I18n.name("gui.wandscape.colony_switch.section.owned",
                            "我拥有的").getString(), WandscapeTheme.COLOR_TEXT_ACTIVE));
                    ownedHeader = true;
                }
            } else if (!joinedHeader) {
                lines.add(ColonyLine.label(I18n.name("gui.wandscape.colony_switch.section.joined",
                        "我参与的").getString(), WandscapeTheme.COLOR_TEXT_ACTIVE));
                joinedHeader = true;
            }
            lines.add(ColonyLine.of(row, i));
        }
        // 一座自己的镇都没有 → 给出唯一可见的建镇入口提示（世界动作：建造市政厅 → 右键命名）。
        if (!ownsAny) {
            lines.add(ColonyLine.label(I18n.name("gui.wandscape.colony_switch.guide",
                    "新建自己的小镇：建造市政厅后右键命名").getString(), WandscapeTheme.COLOR_TEXT_NORMAL));
        }
        return lines;
    }

    private static void renderInviteSection(GuiGraphics g, Font font, ColonyGeo geo, List<InviteRow> rows,
                                            double mx, double my, boolean hover) {
        SectionView v = view(geo, ColonySection.INVITES, rows.size());
        drawSectionTitle(g, font, geo.leftX(), geo.invitesBodyY() - CP_SECTION_H, geo.colW(),
                I18n.name("gui.wandscape.colony_members.section.invites", "待处理邀请").getString(), v, rows.size());

        if (rows.isEmpty()) {
            drawText(g, font, I18n.name("gui.wandscape.colony_members.empty.invites", "暂无邀请").getString(),
                    geo.leftX() + 3, geo.invitesBodyY() + 2, WandscapeTheme.COLOR_TEXT_DIM);
            return;
        }

        String acceptLabel = I18n.name("gui.wandscape.colony_members.accept", "接受").getString();
        String declineLabel = I18n.name("gui.wandscape.colony_members.decline", "拒绝").getString();
        for (int i = v.from(); i < v.to(); i++) {
            InviteRow row = rows.get(i);
            int rowY = v.rowY(i);
            boolean rowHover = rowHovered(v, i, hover, mx, my, geo.leftX(), geo.colW());
            if (rowHover) {
                g.fill(RenderType.guiOverlay(), geo.leftX(), rowY, geo.leftX() + geo.colW(),
                        rowY + CP_ROW_H - 1, COLOR_ROW_HOVER);
            }

            // 接受/拒绝是邀请方给的，任何档位都能处理自己的邀请 → 恒可用。
            ButtonRect accept = inviteAcceptRect(geo, font, rowY);
            ButtonRect decline = inviteDeclineRect(geo, font, rowY);
            drawFlatButton(g, font, accept, acceptLabel, true, rowHover && accept.contains(mx, my));
            drawFlatButton(g, font, decline, declineLabel, true, rowHover && decline.contains(mx, my));

            String text = I18n.name("gui.wandscape.colony_members.invite_row", "%s 由 %s 邀请（%s）",
                    row.colonyName(), row.inviterName(), roleName(row.role())).getString();
            drawText(g, font, truncate(font, text, accept.x() - geo.leftX() - 5), geo.leftX() + 3, rowY + 2,
                    WandscapeTheme.COLOR_TEXT_NORMAL);
        }
    }

    private static void renderMemberSection(GuiGraphics g, Font font, ColonyGeo geo, List<MemberRow> rows,
                                            boolean hasSelection, double mx, double my, boolean hover) {
        SectionView v = view(geo, ColonySection.MEMBERS, rows.size());
        int titleY = geo.membersBodyY() - CP_SECTION_H;
        boolean govern = canGovern();

        // 标题行右侧是「转让镇长」开关：只有镇长看得见（看不到就不画，与在线玩家区的档位按钮同款）。
        ButtonRect transferToggle = memberTransferToggleRect(geo, font);
        String title = I18n.name("gui.wandscape.colony_members.section.members", "成员").getString();
        drawSectionTitle(g, font, geo.rightX(), titleY,
                govern ? transferToggle.x() - geo.rightX() - 3 : geo.colW(),
                govern ? truncate(font, title, transferToggle.x() - geo.rightX() - 4) : title,
                v, rows.size());
        if (govern) {
            String label = transferMode
                    ? I18n.name("gui.wandscape.colony_members.transfer.cancel", "取消转让").getString()
                    : I18n.name("gui.wandscape.colony_members.transfer.start", "转让镇长").getString();
            drawFlatButton(g, font, transferToggle, label, true, hover && transferToggle.contains(mx, my));
        }

        if (rows.isEmpty()) {
            String hint = hasSelection
                    ? I18n.name("gui.wandscape.colony_members.empty.members", "暂无成员").getString()
                    : I18n.name("gui.wandscape.colony_members.hint.select", "点击上方小镇列表选择要管理的小镇").getString();
            drawText(g, font, hint, geo.rightX() + 3, geo.membersBodyY() + 2, WandscapeTheme.COLOR_TEXT_DIM);
            return;
        }

        boolean transferring = govern && transferMode;
        String transferLabel = I18n.name("gui.wandscape.colony_members.transfer", "转让").getString();
        for (int i = v.from(); i < v.to(); i++) {
            MemberRow row = rows.get(i);
            int rowY = v.rowY(i);
            boolean rowHover = rowHovered(v, i, hover, mx, my, geo.rightX(), geo.colW());
            if (rowHover) {
                g.fill(RenderType.guiOverlay(), geo.rightX(), rowY, geo.rightX() + geo.colW(),
                        rowY + CP_ROW_H - 1, COLOR_ROW_HOVER);
            }

            // 只有 OWNER 能调档位/移除/转让；镇长那一行谁都不许动（与服务端「防自锁」一致）。
            boolean manageable = govern && row.role() != ColonyRole.OWNER;
            if (transferring && manageable) {
                // 转让模式下这一行只剩【转让】：把档位与移除换掉，避免紧挨着点错
                ButtonRect transferBtn = memberTransferRect(geo, font, rowY);
                drawFlatButton(g, font, transferBtn, transferLabel, true, rowHover && transferBtn.contains(mx, my));
                drawText(g, font, truncate(font, row.name(), transferBtn.x() - geo.rightX() - 5),
                        geo.rightX() + 3, rowY + 2, WandscapeTheme.COLOR_TEXT_NORMAL);
                continue;
            }

            String roleLabel = roleName(row.role());
            ButtonRect roleBtn = memberRoleRect(geo, font, rowY, roleLabel);
            ButtonRect removeBtn = memberRemoveRect(geo, rowY);
            drawFlatButton(g, font, roleBtn, roleLabel, manageable, rowHover && roleBtn.contains(mx, my));
            drawFlatButton(g, font, removeBtn, "×", manageable, rowHover && removeBtn.contains(mx, my));

            drawText(g, font, truncate(font, row.name(), roleBtn.x() - geo.rightX() - 5),
                    geo.rightX() + 3, rowY + 2,
                    manageable ? WandscapeTheme.COLOR_TEXT_NORMAL : WandscapeTheme.COLOR_TEXT_DIM);
        }
    }

    private static void renderOnlineSection(GuiGraphics g, Font font, ColonyGeo geo, List<OnlineRow> rows,
                                            boolean hasSelection, ColonyRole myRole,
                                            double mx, double my, boolean hover) {
        SectionView v = view(geo, ColonySection.ONLINE, rows.size());
        int titleY = geo.onlineBodyY() - CP_SECTION_H;

        // 标题行右侧是「邀请档位」循环按钮：徽标即当前将授予的档位（默认 MEMBER）。
        boolean inviteAllowed = canInvite();
        ButtonRect roleBtn = inviteRoleCycleRect(geo, font);
        String roleLabel = I18n.name("gui.wandscape.colony_members.invite_role", "邀请档位: %s",
                roleName(inviteRole())).getString();
        drawSectionTitle(g, font, geo.leftX(), titleY, roleBtn.x() - geo.leftX() - 3,
                truncate(font, I18n.name("gui.wandscape.colony_members.section.online", "可邀请的在线玩家").getString(),
                        roleBtn.x() - geo.leftX() - 4), v, rows.size());
        if (myRole != null) {
            drawFlatButton(g, font, roleBtn, roleLabel, inviteAllowed, hover && inviteAllowed && roleBtn.contains(mx, my));
        }

        if (!hasSelection) {
            drawText(g, font, I18n.name("gui.wandscape.colony_members.hint.select", "点击上方小镇列表选择要管理的小镇").getString(),
                    geo.leftX() + 3, geo.onlineBodyY() + 2, WandscapeTheme.COLOR_TEXT_DIM);
            return;
        }
        if (myRole == null) {
            drawText(g, font, I18n.name("gui.wandscape.colony_members.hint.not_member", "你不是该镇成员").getString(),
                    geo.leftX() + 3, geo.onlineBodyY() + 2, COLOR_DISABLED_TEXT);
            return;
        }
        if (!inviteAllowed) {
            drawText(g, font, I18n.name("gui.wandscape.colony_members.hint.no_permission", "需管理员及以上档位").getString(),
                    geo.leftX() + 3, geo.onlineBodyY() + 2, COLOR_DISABLED_TEXT);
            return;
        }
        if (rows.isEmpty()) {
            drawText(g, font, I18n.name("gui.wandscape.colony_members.empty.online", "没有可邀请的在线玩家").getString(),
                    geo.leftX() + 3, geo.onlineBodyY() + 2, WandscapeTheme.COLOR_TEXT_DIM);
            return;
        }

        String inviteLabel = I18n.name("gui.wandscape.colony_members.invite", "邀请").getString();
        for (int i = v.from(); i < v.to(); i++) {
            OnlineRow row = rows.get(i);
            int rowY = v.rowY(i);
            boolean rowHover = rowHovered(v, i, hover, mx, my, geo.leftX(), geo.colW());
            if (rowHover) {
                g.fill(RenderType.guiOverlay(), geo.leftX(), rowY, geo.leftX() + geo.colW(),
                        rowY + CP_ROW_H - 1, COLOR_ROW_HOVER);
            }
            // 整行可点即邀请（契约「点击某人即邀请」），右侧按钮只是这个动作的可视抓手。
            ButtonRect btn = onlineInviteRect(geo, font, rowY);
            drawFlatButton(g, font, btn, inviteLabel, true, rowHover && btn.contains(mx, my));
            drawText(g, font, truncate(font, row.name(), btn.x() - geo.leftX() - 5),
                    geo.leftX() + 3, rowY + 2, WandscapeTheme.COLOR_TEXT_NORMAL);
        }
    }

    /** 分区标题 + 上下溢出箭头 + 1px 分隔线（dividerW<=0 不画分隔线）。 */
    private static void drawSectionTitle(GuiGraphics g, Font font, int x, int y, int dividerW,
                                         String title, SectionView v, int count) {
        String arrows = (v.from() > 0 ? " ↑" : "") + (v.to() < count ? " ↓" : "");
        drawText(g, font, title + arrows, x, y, WandscapeTheme.COLOR_TEXT_ACTIVE);
        if (dividerW > 0) {
            g.fill(RenderType.guiOverlay(), x, y + CP_SECTION_H - 2, x + dividerW, y + CP_SECTION_H - 1,
                    WandscapeTheme.COLOR_BORDER_NORMAL);
        }
    }

    /** 纯代码小按钮：置灰时边框与文字一起变暗，且不画 hover 高亮（无权限的按钮点不动）。 */
    private static void drawFlatButton(GuiGraphics g, Font font, ButtonRect r, String label,
                                       boolean enabled, boolean hovered) {
        int border = !enabled ? COLOR_DISABLED_BORDER
                : (hovered ? WandscapeTheme.COLOR_BORDER_ACTIVE : WandscapeTheme.COLOR_BORDER_NORMAL);
        int textColor = !enabled ? COLOR_DISABLED_TEXT
                : (hovered ? WandscapeTheme.COLOR_TEXT_ACTIVE : WandscapeTheme.COLOR_TEXT_NORMAL);
        if (enabled && hovered) {
            g.fill(RenderType.guiOverlay(), r.x(), r.y(), r.x() + r.w(), r.y() + r.h(), WandscapeTheme.COLOR_BG_HOVER);
        }
        drawBorder(g, r.x(), r.y(), r.w(), r.h(), border);
        drawText(g, font, label, r.x() + (r.w() - font.width(label)) / 2f,
                r.y() + (r.h() - font.lineHeight) / 2f, textColor);
    }

    // ── Button rects（渲染与命中检测共用，改一处两边同时变） ──

    private static ButtonRect inviteDeclineRect(ColonyGeo geo, Font font, int rowY) {
        String label = I18n.name("gui.wandscape.colony_members.decline", "拒绝").getString();
        int w = font.width(label) + CP_BTN_PAD;
        return new ButtonRect(geo.leftX() + geo.colW() - w - 1, rowY + 1, w, CP_ROW_H - 3);
    }

    private static ButtonRect inviteAcceptRect(ColonyGeo geo, Font font, int rowY) {
        String label = I18n.name("gui.wandscape.colony_members.accept", "接受").getString();
        int w = font.width(label) + CP_BTN_PAD;
        ButtonRect decline = inviteDeclineRect(geo, font, rowY);
        return new ButtonRect(decline.x() - w - 2, rowY + 1, w, CP_ROW_H - 3);
    }

    private static ButtonRect memberRemoveRect(ColonyGeo geo, int rowY) {
        int size = CP_ROW_H - 3;
        return new ButtonRect(geo.rightX() + geo.colW() - size - 1, rowY + 1, size, size);
    }

    private static ButtonRect memberRoleRect(ColonyGeo geo, Font font, int rowY, String roleLabel) {
        int w = font.width(roleLabel) + CP_BTN_PAD;
        ButtonRect remove = memberRemoveRect(geo, rowY);
        return new ButtonRect(remove.x() - w - 3, rowY + 1, w, CP_ROW_H - 3);
    }

    /** 成员区标题行右侧的「转让镇长」开关（渲染与命中共用）。 */
    private static ButtonRect memberTransferToggleRect(ColonyGeo geo, Font font) {
        String label = transferMode
                ? I18n.name("gui.wandscape.colony_members.transfer.cancel", "取消转让").getString()
                : I18n.name("gui.wandscape.colony_members.transfer.start", "转让镇长").getString();
        int w = font.width(label) + CP_BTN_PAD;
        return new ButtonRect(geo.rightX() + geo.colW() - w - 1, geo.membersBodyY() - CP_SECTION_H, w, CP_SECTION_H);
    }

    /** 转让模式下成员行右侧的单个【转让】按钮（取代平时的档位 + 移除两键）。 */
    private static ButtonRect memberTransferRect(ColonyGeo geo, Font font, int rowY) {
        String label = I18n.name("gui.wandscape.colony_members.transfer", "转让").getString();
        int w = font.width(label) + CP_BTN_PAD;
        return new ButtonRect(geo.rightX() + geo.colW() - w - 1, rowY + 1, w, CP_ROW_H - 3);
    }

    private static ButtonRect onlineInviteRect(ColonyGeo geo, Font font, int rowY) {
        String label = I18n.name("gui.wandscape.colony_members.invite", "邀请").getString();
        int w = font.width(label) + CP_BTN_PAD;
        return new ButtonRect(geo.leftX() + geo.colW() - w - 1, rowY + 1, w, CP_ROW_H - 3);
    }

    private static ButtonRect inviteRoleCycleRect(ColonyGeo geo, Font font) {
        String label = I18n.name("gui.wandscape.colony_members.invite_role", "邀请档位: %s",
                roleName(inviteRole())).getString();
        int w = font.width(label) + CP_BTN_PAD;
        return new ButtonRect(geo.leftX() + geo.colW() - w - 1, geo.onlineBodyY() - CP_SECTION_H, w, CP_SECTION_H);
    }

    // ── 权限判定（客户端 UX；服务端仍会重判） ──

    /**
     * 当前镇（服务端推送的唯一真源）：顶栏 / 边界 / 设置中心 / 本面板共用它，面板不再自持选中项。
     * 无当前镇（未加入任何镇 / 尚未收到同步）返回 null。
     */
    @Nullable
    public static UUID currentColonyId() {
        return WandscapePanelState.getColonyId();
    }

    /** 我在当前镇的档位；无当前镇或非成员返回 null。 */
    @Nullable
    public static ColonyRole currentRole() {
        return ColonyPanelClientState.roleOf(currentColonyId());
    }

    /** 切换档位的下限：>= MEMBER 才可切换。ALLY 只有白名单权限，切过去也什么都做不了。 */
    public static boolean canSwitch(@Nullable ColonyRole role) {
        return role != null && role.atLeast(ColonyRole.MEMBER);
    }

    /** 该行是否可切换（>= MEMBER）；ALLY 行置灰且不产生命中。 */
    public static boolean canSwitch(@Nullable ColonyRow row) {
        return row != null && canSwitch(row.role());
    }

    /** 邀请入口：当前镇档位 >= MANAGER。 */
    public static boolean canInvite() {
        ColonyRole role = currentRole();
        return role != null && role.atLeast(ColonyRole.MANAGER);
    }

    /** 调档位 / 移除成员 / 转让镇长：当前镇档位 == OWNER（== {@link ColonyRole#canGovern()}）。 */
    public static boolean canGovern() {
        ColonyRole role = currentRole();
        return role != null && role.canGovern();
    }

    /** 是否处于「转让镇长」模式（纯客户端 UI 状态，仅 OWNER 下成立）。 */
    public static boolean isTransferMode() {
        return transferMode;
    }

    /** 进入/退出「转让镇长」模式；命中成员区标题行右侧的开关按钮时调用。 */
    public static void toggleTransferMode() {
        if (transferMode) {
            exitTransferMode();
            return;
        }
        if (!canGovern()) return;
        transferMode = true;
        transferModeColony = currentColonyId();
    }

    /** 退出「转让镇长」模式：发出转让、离开小镇页、当前镇或我的档位变了，都走这里。 */
    public static void exitTransferMode() {
        transferMode = false;
        transferModeColony = null;
    }

    /**
     * 自愈：转让模式只在「当前镇 + 我是该镇 OWNER」下成立。切换了镇、或被降级 / 把镇长让出去之后，
     * 渲染时就把它复位——面板上不会留下一份「看着能转让、点下去必失败」的旧界面。
     *
     * <p>离开小镇页的那条路径不走这里（那时本方法根本不会被调用），由
     * {@code WandscapePanelState.exitCurrentSubMode} 显式复位。
     */
    private static void syncTransferMode(@Nullable UUID current) {
        if (transferMode && (!canGovern() || !Objects.equals(transferModeColony, current))) {
            exitTransferMode();
        }
    }

    /** 当前选择的邀请档位（默认 MEMBER）。候选只含低于自己档位的，见 {@link #inviteRoleOptions()}。 */
    public static ColonyRole inviteRole() {
        List<ColonyRole> options = inviteRoleOptions();
        if (options.isEmpty()) return ColonyRole.MEMBER;
        if (inviteRoleIndex < 0 || inviteRoleIndex >= options.size()) inviteRoleIndex = 0;
        return options.get(inviteRoleIndex);
    }

    /** 切到下一个可选邀请档位（命中「邀请档位」按钮时调用）。 */
    public static void cycleInviteRole() {
        List<ColonyRole> options = inviteRoleOptions();
        if (options.size() <= 1) return;
        inviteRoleIndex = (inviteRoleIndex + 1) % options.size();
    }

    private static List<ColonyRole> inviteRoleOptions() {
        ColonyRole mine = currentRole();
        if (mine == null) return List.of();
        List<ColonyRole> out = new ArrayList<>(INVITE_ROLE_CYCLE.length);
        for (ColonyRole role : INVITE_ROLE_CYCLE) {
            if (role.rank() < mine.rank()) out.add(role);
        }
        return out;
    }

    // ── 数据视图（渲染与 controller 动作共用；T2 契约保证访问器非 null，这里再兜一层） ──

    public static List<ColonyRow> colonyRows() {
        var raw = ColonyPanelClientState.getColonies();
        if (raw == null || raw.isEmpty()) return List.of();
        List<ColonyRow> out = new ArrayList<>(raw.size());
        for (var entry : raw) {
            out.add(new ColonyRow(entry.colonyId(), entry.name(), entry.level(), entry.myRole()));
        }
        return out;
    }

    public static List<InviteRow> inviteRows() {
        var raw = ColonyPanelClientState.getPendingInvites();
        if (raw == null || raw.isEmpty()) return List.of();
        List<InviteRow> out = new ArrayList<>(raw.size());
        for (var invite : raw) {
            out.add(new InviteRow(invite.colonyId(), invite.colonyName(), invite.inviterName(), invite.role()));
        }
        return out;
    }

    /** 当前镇的成员表；无当前镇 / 无快照返回空表。 */
    public static List<MemberRow> memberRows() {
        var raw = ColonyPanelClientState.membersOf(currentColonyId());
        if (raw == null || raw.isEmpty()) return List.of();
        List<MemberRow> out = new ArrayList<>(raw.size());
        for (var member : raw) {
            out.add(new MemberRow(member.id(), member.name(), member.role()));
        }
        return out;
    }

    /** 可邀请的在线玩家：排除当前镇花名册上的人与自己，按名字排序。 */
    public static List<OnlineRow> onlineRows() {
        Minecraft mc = Minecraft.getInstance();
        var connection = mc.getConnection();
        if (connection == null || mc.player == null) return List.of();

        Set<UUID> known = new HashSet<>();
        for (MemberRow member : memberRows()) {
            if (member.id() != null) known.add(member.id());
        }
        known.add(mc.player.getUUID());

        List<OnlineRow> out = new ArrayList<>();
        for (var info : connection.getOnlinePlayers()) {
            var profile = info.getProfile();
            if (profile == null || profile.getId() == null) continue;
            if (known.contains(profile.getId())) continue;
            String name = profile.getName();
            out.add(new OnlineRow(profile.getId(), (name == null || name.isEmpty()) ? "?" : name));
        }
        out.sort(Comparator.comparing(OnlineRow::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    // ── 命中检测 / 滚动（controller 调用；几何与渲染同源） ──

    /** 鼠标是否落在小镇面板上（面板画不出来或当前不是 COLONY 页时为 false）。 */
    public static boolean isOverColonyPanel(double mx, double my, int screenW, int screenH) {
        if (WandscapePanelState.getActiveSubMode() != WandscapePanelState.SubMode.COLONY) return false;
        return contains(computeGeo(screenW, screenH), mx, my);
    }

    /**
     * 面板点击命中检测。置灰（无权限）的按钮**不返回命中**，所以点了自然没效果；
     * MENU 层只负责把 hit 翻成发包动作。
     */
    public static ColonyHitResult colonyHitTest(double mx, double my, int screenW, int screenH) {
        if (WandscapePanelState.getActiveSubMode() != WandscapePanelState.SubMode.COLONY) return NO_HIT;
        ColonyGeo geo = computeGeo(screenW, screenH);
        if (!contains(geo, mx, my)) return NO_HIT;
        Font font = Minecraft.getInstance().font;
        if (font == null) return NO_HIT;

        // 1. 小镇列表：只有可切换的行（>= MEMBER）产生命中 → 切当前镇。
        //    分组标题/建镇引导行、以及 ALLY 行（置灰不可切换）都不给命中，点了自然没效果。
        List<ColonyLine> lines = colonyLines();
        SectionView coloniesView = view(geo, ColonySection.COLONIES, lines.size());
        int lineIndex = rowIndexAt(coloniesView, mx, my, geo.leftX(), geo.colW());
        if (lineIndex >= 0) {
            ColonyLine line = lines.get(lineIndex);
            if (line.row() != null && line.rowIndex() >= 0 && canSwitch(line.row())) {
                return new ColonyHitResult(ColonyHit.COLONY_ROW, line.rowIndex());
            }
            return NO_HIT;
        }

        // 2. 待处理邀请：行内右侧 [接受][拒绝]
        List<InviteRow> invites = inviteRows();
        SectionView invitesView = view(geo, ColonySection.INVITES, invites.size());
        int inviteIndex = rowIndexAt(invitesView, mx, my, geo.leftX(), geo.colW());
        if (inviteIndex >= 0) {
            int rowY = invitesView.rowY(inviteIndex);
            if (inviteAcceptRect(geo, font, rowY).contains(mx, my)) {
                return new ColonyHitResult(ColonyHit.INVITE_ACCEPT, inviteIndex);
            }
            if (inviteDeclineRect(geo, font, rowY).contains(mx, my)) {
                return new ColonyHitResult(ColonyHit.INVITE_DECLINE, inviteIndex);
            }
        }

        // 3. 成员：仅 OWNER 可调档位 / 移除 / 转让，且镇长那行谁都不给动（置灰 → 不返回命中）
        if (canGovern()) {
            // 3a. 标题行右侧的「转让镇长」开关：只切进/出转让模式，本身不是花名册操作
            if (memberTransferToggleRect(geo, font).contains(mx, my)) {
                return new ColonyHitResult(ColonyHit.TRANSFER_TOGGLE, -1);
            }
            List<MemberRow> members = memberRows();
            SectionView membersView = view(geo, ColonySection.MEMBERS, members.size());
            int memberIndex = rowIndexAt(membersView, mx, my, geo.rightX(), geo.colW());
            if (memberIndex >= 0) {
                MemberRow row = members.get(memberIndex);
                if (row.role() != ColonyRole.OWNER) {
                    int rowY = membersView.rowY(memberIndex);
                    // 转让模式下这一行只有一个【转让】按钮；平时才是档位 + 移除（两者不同时存在，
                    // 所以「点了转让却改了档位」在几何上就不可能发生）。
                    if (transferMode) {
                        if (memberTransferRect(geo, font, rowY).contains(mx, my)) {
                            return new ColonyHitResult(ColonyHit.MEMBER_TRANSFER, memberIndex);
                        }
                    } else {
                        if (memberRoleRect(geo, font, rowY, roleName(row.role())).contains(mx, my)) {
                            return new ColonyHitResult(ColonyHit.MEMBER_ROLE, memberIndex);
                        }
                        if (memberRemoveRect(geo, rowY).contains(mx, my)) {
                            return new ColonyHitResult(ColonyHit.MEMBER_REMOVE, memberIndex);
                        }
                    }
                }
            }
        }

        // 4. 可邀请玩家：仅 MANAGER+ 才有入口（标题行的档位循环 + 整行点击邀请）
        if (canInvite()) {
            if (inviteRoleCycleRect(geo, font).contains(mx, my)) {
                return new ColonyHitResult(ColonyHit.INVITE_ROLE, -1);
            }
            List<OnlineRow> online = onlineRows();
            SectionView onlineView = view(geo, ColonySection.ONLINE, online.size());
            int onlineIndex = rowIndexAt(onlineView, mx, my, geo.leftX(), geo.colW());
            if (onlineIndex >= 0) return new ColonyHitResult(ColonyHit.ONLINE_INVITE, onlineIndex);
        }

        return NO_HIT;
    }

    private static int rowIndexAt(SectionView v, double mx, double my, int colX, int colW) {
        if (mx < colX || mx > colX + colW) return -1;
        return v.indexAt(my);
    }

    /**
     * 鼠标滚轮：滚动光标所在分区，只在真的能滚（有溢出）时消费事件。
     *
     * @return true 表示事件已被面板消费
     */
    public static boolean scrollColonyPanel(double mx, double my, int screenW, int screenH, double deltaY) {
        try {
            if (WandscapePanelState.getActiveSubMode() != WandscapePanelState.SubMode.COLONY) return false;
            ColonyGeo geo = computeGeo(screenW, screenH);
            if (!contains(geo, mx, my)) return false;

            ColonySection section = sectionAt(geo, mx, my);
            if (section == null) return false;

            int count = switch (section) {
                case COLONIES -> colonyLines().size();
                case INVITES -> inviteRows().size();
                case MEMBERS -> memberRows().size();
                case ONLINE -> canInvite() ? onlineRows().size() : 0;
            };
            int rows = view(geo, section, count).rows();
            if (count <= rows) return false;   // 没溢出 → 不消费，交回其它处理器

            int current = COLONY_SCROLL[section.ordinal()];
            int next = Math.max(0, Math.min(count - rows, current + (deltaY > 0 ? -1 : 1)));
            COLONY_SCROLL[section.ordinal()] = next;
            // 到这个分区的顶/底也照样消费：否则滚轮会穿到面板下面的其它处理器上跳变。
            return true;
        } catch (Throwable t) {
            Log.warn(TAG, "[Colony] 面板滚动失败: {}", t.toString());
            return false;
        }
    }

    /** 光标在哪个分区（列 → 上下两段）；不在任何分区返回 null。 */
    private static ColonySection sectionAt(ColonyGeo geo, double mx, double my) {
        boolean left = mx >= geo.leftX() && mx <= geo.leftX() + geo.colW();
        boolean right = mx >= geo.rightX() && mx <= geo.rightX() + geo.colW();
        if (!left && !right) return null;

        if (left) {
            int invitesTop = geo.invitesBodyY() - CP_SECTION_H - CP_SEC_GAP;
            return my < invitesTop ? ColonySection.COLONIES : ColonySection.INVITES;
        }
        int onlineTop = geo.onlineBodyY() - CP_SECTION_H - CP_SEC_GAP;
        return my < onlineTop ? ColonySection.MEMBERS : ColonySection.ONLINE;
    }

    // ── 文本/名称小工具 ──

    private static String roleName(ColonyRole role) {
        // 档位显示名只留一份真源：与聊天/Toast 提示共用 ColonyRosterSyncService.roleLabel，
        // 避免 UI 与消息各写一套（此前两套键同档异名：panel 作「管理员」、消息作「管事」）。
        return com.wsteam.wandscape.content.colony.network.ColonyRosterSyncService
                .roleLabel(role).getString();
    }

    private static String shortId(UUID id) {
        return id == null ? "?" : id.toString().substring(0, 8);
    }

    /** 行高亮：鼠标在该列范围内、且落在这一行——两列同高时不互相点亮。 */
    private static boolean rowHovered(SectionView v, int index, boolean hover, double mx, double my,
                                      int colX, int colW) {
        return hover && mx >= colX && mx <= colX + colW && v.indexAt(my) == index;
    }

    /** 按像素宽度截断（尾部补 ".."），避免文字压到右侧按钮上。 */
    private static String truncate(Font font, String text, int maxW) {
        if (text == null || text.isEmpty()) return "";
        if (maxW <= 0) return "";
        if (font.width(text) <= maxW) return text;
        String ellipsis = "..";
        int budget = maxW - font.width(ellipsis);
        int end = text.length();
        while (end > 0 && font.width(text.substring(0, end)) > budget) end--;
        return end <= 0 ? ellipsis : text.substring(0, end) + ellipsis;
    }

    /** 1px 主题边框（纯代码绘制，不用纹理）。 */
    private static void drawBorder(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(RenderType.guiOverlay(), x, y, x + w, y + 1, color);
        g.fill(RenderType.guiOverlay(), x, y + h - 1, x + w, y + h, color);
        g.fill(RenderType.guiOverlay(), x, y, x + 1, y + h, color);
        g.fill(RenderType.guiOverlay(), x + w - 1, y, x + w, y + h, color);
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Top bar HUD ──
    // ═══════════════════════════════════════════════════════════════

    private static void renderTopBar(GuiGraphics g, Font font, int screenW, double mx, double my) {
        int lvl = WandscapePanelState.getColonyLevel();
        String name = WandscapePanelState.getColonyName();
        UUID cid = WandscapePanelState.getColonyId();
        if (name == null || name.isEmpty()) name = cid != null ? cid.toString().substring(0, 8) : "?";

        Minecraft mc = Minecraft.getInstance();

        // ── Row 1: colony info + stats + day + tourists + NPC ──
        int y1 = 3;
        int iconS1 = 12;
        int textY1 = 5;
        int x = 4;
        int rightMargin = 8;

        // 1. Colony icon + name + level
        WandscapeTheme.drawIcon(g, WandscapeTheme.ICON_COLONY, x, y1, iconS1, iconS1, WandscapeTheme.COLOR_TEXT_NORMAL);
        x += iconS1 + 3;
        String colonyText = name + " Lv." + lvl;
        drawText(g, font, colonyText, x, textY1, WandscapeTheme.COLOR_TEXT_NORMAL);
        x += font.width(colonyText) + 6;

        // 2. Comfort
        WandscapeTheme.drawIcon(g, WandscapeTheme.ICON_COMFORT, x, y1, iconS1, iconS1, WandscapeTheme.COLOR_COMFORT);
        x += iconS1 + 2;
        String comfortStr = String.valueOf(WandscapePanelState.getComfort());
        drawText(g, font, comfortStr, x, textY1, WandscapeTheme.COLOR_TEXT_NORMAL);
        x += font.width(comfortStr) + 6;

        // 3. Magic
        WandscapeTheme.drawIcon(g, WandscapeTheme.ICON_MAGIC, x, y1, iconS1, iconS1, WandscapeTheme.COLOR_MAGIC);
        x += iconS1 + 2;
        String magicStr = String.valueOf(WandscapePanelState.getMagic());
        drawText(g, font, magicStr, x, textY1, WandscapeTheme.COLOR_TEXT_NORMAL);
        x += font.width(magicStr) + 6;

        // 4. Wonder
        WandscapeTheme.drawIcon(g, WandscapeTheme.ICON_WONDER, x, y1, iconS1, iconS1, WandscapeTheme.COLOR_WONDER);
        x += iconS1 + 2;
        String wonderStr = String.valueOf(WandscapePanelState.getWonder());
        drawText(g, font, wonderStr, x, textY1, WandscapeTheme.COLOR_TEXT_NORMAL);
        x += font.width(wonderStr) + 10;

        // 5. Day
        long day = mc.level != null ? mc.level.getDayTime() / 24000 + 1 : 1;
        String dayText = I18n.name("gui.wandscape.panel.day", "Day %s", day).getString();
        drawText(g, font, dayText, x, textY1, WandscapeTheme.COLOR_TEXT_DIM);
        x += font.width(dayText) + 10;

        // 6. Tourist count (overnight stayers / total)
        WandscapeTheme.drawIcon(g, WandscapeTheme.ICON_TOURIST, x, y1, iconS1, iconS1, WandscapeTheme.COLOR_TEXT_NORMAL);
        x += iconS1 + 2;
        int totalT = WandscapePanelState.getTouristCount();
        int overnightT = WandscapePanelState.getOvernightStayerCount();
        String touristText = overnightT + "/" + totalT;
        drawText(g, font, touristText, x, textY1, WandscapeTheme.COLOR_TEXT_NORMAL);
        x += font.width(touristText) + 10;

        // 7. NPC idle/total
        String npcText = I18n.name("gui.wandscape.panel.npc_count", "%s/%s NPC",
                WandscapePanelState.getNpcIdleCount(), WandscapePanelState.getNpcTotalCount()).getString();
        drawText(g, font, npcText, x, textY1, WandscapeTheme.COLOR_TEXT_NORMAL);

        // 8. Help ? button at top right
        int helpX = screenW - rightMargin - 16;
        int helpY = 4;
        int helpW = 14;
        int helpH = 14;
        boolean helpHover = WandscapePanelState.isCursorLifted() && mx >= helpX && mx <= helpX + helpW && my >= helpY && my <= helpY + helpH;
        int helpState = helpHover ? 1 : 0;
        com.wsteam.wandscape.foundation.ui.skin.SkinRender.drawHelpButton(g, helpX, helpY, helpW, helpH, helpState);

        if (helpHover) {
            String keyName = com.wsteam.wandscape.WandscapeClient.GUIDEBOOK_TOGGLE.getTranslatedKeyMessage().getString();
            g.renderTooltip(font, I18n.name("gui.wandscape.panel.open_guidebook", "打开指南 (%s)", keyName), (int) mx, (int) my);
        }

        // ── Row 2: element icons + amounts ──
        int y2 = 17;
        int s2 = 9;
        int textY2 = 19;
        renderElementIcons(g, font, 4, y2, textY2, s2);
    }

    private static void renderElementIcons(GuiGraphics g, Font font, int startX, int y, int textY, int s) {
        int x = startX;

        String[] elementIds = {"earth", "wood", "water", "fire", "metal", "wind", "dark"};
        int[] amounts = {
            WandscapePanelState.getEarthAmount(),
            WandscapePanelState.getWoodAmount(),
            WandscapePanelState.getWaterAmount(),
            WandscapePanelState.getFireAmount(),
            WandscapePanelState.getMetalAmount(),
            WandscapePanelState.getWindAmount(),
            WandscapePanelState.getDarkAmount()
        };

        for (int i = 0; i < elementIds.length; i++) {
            var icon = WandscapeTheme.elementIcon(elementIds[i]);
            int color = WandscapeTheme.elementColor(elementIds[i]);
            WandscapeTheme.drawIcon(g, icon, x, y, s, s, color);
            x += s + 2;
            String val = String.valueOf(amounts[i]);
            drawText(g, font, val, x, textY, color);
            x += font.width(val) + 6;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Stats content ──
    // ═══════════════════════════════════════════════════════════════

    private static void renderStatsContent(GuiGraphics g, Font font, int screenW, int screenH) {
        var stats = WandscapePanelState.getStatsSummary();

        int boxW = 380;
        int boxH = 165;
        int leftX = SIDEBAR_W + 4;
        int topY = TOP_BAR_H + 4;
        WandscapeTheme.drawRtsBox(g, leftX, topY, boxW, boxH, true, false);

        int pad = 10;
        int lineH = font.lineHeight + 3;

        if (stats == null || stats.snapshotCount() == 0) {
            drawText(g, font, I18n.name("gui.wandscape.stats.none", "No statistics available yet.").getString(),
                    leftX + pad, topY + pad, WandscapeTheme.COLOR_TEXT_DIM);
            return;
        }

        // ── Header (full width) ──
        String header = I18n.name("gui.wandscape.stats.title", "Colony Statistics  |  Day %s", stats.currentDay()).getString();
        drawText(g, font, header, leftX + pad, topY + pad, WandscapeTheme.COLOR_TEXT_NORMAL);
        int sepY = topY + pad + font.lineHeight + 2;
        g.fill(leftX + pad, sepY, leftX + boxW - pad, sepY + 1, WandscapeTheme.COLOR_BORDER_NORMAL);
        int y0 = sepY + 6;

        // ── Left column: tourists ──
        int lx = leftX + pad;
        int y = y0;

        drawText(g, font, I18n.name("gui.wandscape.stats.tourists", "Tourists").getString(), lx, y, WandscapeTheme.COLOR_TEXT_ACTIVE);
        y += lineH;
        drawText(g, font, "  " + I18n.name("gui.wandscape.stats.arrived", "In: %s", stats.touristsArrived()).getString(), lx, y, WandscapeTheme.COLOR_TEXT_DIM);
        y += lineH;
        drawText(g, font, "  " + I18n.name("gui.wandscape.stats.departed", "Out: %s", stats.touristsDeparted()).getString(), lx, y, WandscapeTheme.COLOR_TEXT_DIM);
        y += lineH;
        drawText(g, font, "  " + I18n.name("gui.wandscape.stats.tourist_comfort", "Fill C: %s%%", stats.avgComfortRatio()).getString(), lx, y, WandscapeTheme.COLOR_TEXT_DIM);
        y += lineH;
        drawText(g, font, "  " + I18n.name("gui.wandscape.stats.tourist_magic", "Fill M: %s%%", stats.avgMagicRatio()).getString(), lx, y, WandscapeTheme.COLOR_TEXT_DIM);
        y += lineH;
        drawText(g, font, "  " + I18n.name("gui.wandscape.stats.tourist_wonder", "Fill W: %s%%", stats.avgWonderRatio()).getString(), lx, y, WandscapeTheme.COLOR_TEXT_DIM);

    }

    // ═══════════════════════════════════════════════════════════════
    // ── Text helpers ──
    // ═══════════════════════════════════════════════════════════════

    private static void drawText(GuiGraphics g, Font font, String text, float x, float y, int color) {
        font.drawInBatch(text, x, y, color, false,
                g.pose().last().pose(), g.bufferSource(),
                Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0);
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Sidebar hit detection ──
    // ═══════════════════════════════════════════════════════════════

    public static int getSidebarHoveredIcon(double mx, double my, int screenH) {
        if (!WandscapePanelState.isCursorLifted()) return -1;
        if (mx < 0 || mx > SIDEBAR_W) return -1;
        if (my < TOP_BAR_H) return -1;

        int startY = TOP_BAR_H + 8;
        int totalH = SIDEBAR_ICON_S + SIDEBAR_GAP;

        // 建造 / 道路 / 小镇 / 任务 / 设置五个 tab（编号 0-4 对齐 1/2/3/4/5 数字键）
        for (int i = 0; i < SIDEBAR_TAB_COUNT; i++) {
            int iy = startY + i * totalH;
            if (my >= iy && my <= iy + SIDEBAR_ICON_S) return i;
        }

        return -1;
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Tab helpers ──
    // ═══════════════════════════════════════════════════════════════

    private static boolean isTabActive(int tabIndex, WandscapePanelState.SubMode activeMode) {
        return switch (tabIndex) {
            case 0 -> activeMode == WandscapePanelState.SubMode.BUILD_PROJECTION;
            case 1 -> activeMode == WandscapePanelState.SubMode.ROAD_PROJECTION;
            case 2 -> activeMode == WandscapePanelState.SubMode.COLONY;
            case 3 -> activeMode == WandscapePanelState.SubMode.TASKS;
            case 4 -> activeMode == WandscapePanelState.SubMode.SETTINGS;
            default -> false;
        };
    }
}
