package com.wsteam.wandscape.foundation.ui.settings;

import com.wsteam.wandscape.content.colony.overview.client.OverviewClientState;
import com.wsteam.wandscape.foundation.ui.I18n;
import com.wsteam.wandscape.foundation.ui.panel.WandscapePanelState;
import com.wsteam.wandscape.foundation.ui.theme.WandscapeTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

import java.util.List;

/**
 * Full-screen RTS Settings Center overlay for the Wandscape panel.
 * Accessed via pressing 4 or clicking the Settings icon on the panel sidebar.
 */
public final class SettingsOverlay {

    private static final int HEADER_H = 34;
    private static final int TOOLBAR_H = 26;
    private static final int CARD_H = 44;
    private static final int CARD_GAP = 6;
    /** 卡片内控件（默认/开关/加减）顶边相对卡片顶的距离。渲染与点击命中都要用，只留这一处。 */
    private static final int CONTROL_Y = 11;
    private static final int HEADER_CLOSE_W = 100;
    private static final int HEADER_CLOSE_GAP = 16;

    private static final int BG_BACKDROP = 0xAA080B10;
    private static final int HEADER_BG = 0xEE11151D;
    private static final int TOOLBAR_BG = 0xCC161B24;
    private static final int BORDER_GOLD = 0xFFC8A040;
    private static final int CARD_BG = 0xDD181D26;
    private static final int CARD_BG_HOVER = 0xF2222834;

    private static SettingTab activeTab = SettingTab.VISUAL;
    private static int scrollOffset = 0;
    private static String toastMessage = "";
    private static long toastExpiryTime = 0;

    private SettingsOverlay() {}

    public static boolean isActive() {
        return WandscapePanelState.isPanelOpen()
                && !WandscapePanelState.isPanelHidden()
                && WandscapePanelState.getActiveSubMode() == WandscapePanelState.SubMode.SETTINGS;
    }

    public static SettingTab getActiveTab() {
        return activeTab;
    }

    public static void setActiveTab(SettingTab tab) {
        activeTab = tab;
        scrollOffset = 0;
    }

    public static void showToast(String message) {
        toastMessage = message;
        toastExpiryTime = System.currentTimeMillis() + 2500;
    }

    public static void playClickSound() {
        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
    }

    private static Boolean testPermissionOverride = null;

    public static void setTestPermissionOverride(Boolean override) {
        testPermissionOverride = override;
    }

    public static boolean canModifySettings() {
        if (testPermissionOverride != null) {
            return testPermissionOverride;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) {
            return true;
        }
        if (mc.isLocalServer()) {
            return true;
        }
        return mc.player.hasPermissions(2);
    }

    public static void collapseToPrevious() {
        WandscapePanelState.exitCurrentSubMode();
        if (!OverviewClientState.isActive()) {
            WandscapePanelState.setSubMode(WandscapePanelState.SubMode.NONE);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Header Layout Engine (prevents tab & close button overlap) ──
    // ═══════════════════════════════════════════════════════════════

    public record HeaderLayout(
            String titleDraw,
            int titleX,
            int titleY,
            int[] tabX,
            int[] tabW,
            int closeX,
            int closeY,
            int closeW,
            int closeH
    ) {
        public int getTabAt(double mx, double my) {
            int btnY = 6;
            int btnH = 22;
            if (my < btnY || my > btnY + btnH) return -1;
            for (int i = 0; i < tabX.length; i++) {
                if (mx >= tabX[i] && mx <= tabX[i] + tabW[i]) {
                    return i;
                }
            }
            return -1;
        }

        public boolean isCloseHovered(double mx, double my) {
            return mx >= closeX && mx <= closeX + closeW && my >= closeY && my <= closeY + closeH;
        }
    }

    public static HeaderLayout layoutHeader(Font font, int screenW) {
        String closeText = I18n.string("gui.wandscape.settings.close", "返回 (ESC)");
        int closeW = Math.max(76, strWidth(font, closeText) + 16);
        int closeX = screenW - closeW - 12;
        int closeY = 6;
        int closeH = 22;
        int contentRight = closeX - 10;
        int leftMargin = 16;
        int avail = Math.max(50, contentRight - leftMargin);

        String colony = WandscapePanelState.getColonyName();
        String fullTitle = I18n.string("gui.wandscape.settings.title", "%s 设置中心",
                (colony != null && !colony.isEmpty())
                        ? colony
                        : I18n.string("gui.wandscape.settings.default_town_name", "魔法小镇"));
        String shortTitle = I18n.string("gui.wandscape.settings.title_short", "设置中心");

        SettingTab[] tabs = SettingTab.values();
        int tabCount = tabs.length;
        int[] rawW = new int[tabCount];
        for (int i = 0; i < tabCount; i++) {
            rawW[i] = strWidth(font, tabs[i].getDisplayName());
        }

        String chosenTitle = "";
        int pad = 16;
        int gap = 6;

        int fullTitleW = strWidth(font, fullTitle);
        int shortTitleW = strWidth(font, shortTitle);

        int tabsW16 = sumWidths(rawW, 16, 6);
        int tabsW12 = sumWidths(rawW, 12, 4);

        if (fullTitleW + 16 + tabsW16 <= avail) {
            chosenTitle = fullTitle;
            pad = 16;
            gap = 6;
        } else if (shortTitleW + 16 + tabsW16 <= avail) {
            chosenTitle = shortTitle;
            pad = 16;
            gap = 6;
        } else if (shortTitleW + 12 + tabsW12 <= avail) {
            chosenTitle = shortTitle;
            pad = 12;
            gap = 4;
        } else if (tabsW16 <= avail) {
            chosenTitle = "";
            pad = 16;
            gap = 6;
        } else if (tabsW12 <= avail) {
            chosenTitle = "";
            pad = 12;
            gap = 4;
        } else {
            chosenTitle = "";
            pad = 8;
            gap = 3;
        }

        int totalTabsW = sumWidths(rawW, pad, gap);
        int titleEnd = chosenTitle.isEmpty() ? leftMargin : (leftMargin + strWidth(font, chosenTitle) + 16);

        int startX;
        if (!chosenTitle.isEmpty()) {
            int remaining = contentRight - titleEnd;
            startX = titleEnd + Math.max(0, (remaining - totalTabsW) / 2);
            if (startX + totalTabsW > contentRight) {
                startX = contentRight - totalTabsW;
            }
            if (startX < titleEnd) {
                startX = titleEnd;
            }
        } else {
            startX = leftMargin + Math.max(0, (avail - totalTabsW) / 2);
            if (startX + totalTabsW > contentRight) {
                startX = contentRight - totalTabsW;
            }
            if (startX < leftMargin) {
                startX = leftMargin;
            }
        }

        int[] tabX = new int[tabCount];
        int[] tabW = new int[tabCount];
        int curX = startX;
        for (int i = 0; i < tabCount; i++) {
            tabX[i] = curX;
            tabW[i] = rawW[i] + pad;
            curX += tabW[i] + gap;
        }

        return new HeaderLayout(chosenTitle, leftMargin, 12, tabX, tabW, closeX, closeY, closeW, closeH);
    }

    private static int strWidth(Font font, String str) {
        if (str == null || str.isEmpty()) return 0;
        return (font != null) ? font.width(str) : str.length() * 6;
    }

    private static int sumWidths(int[] rawW, int pad, int gap) {
        int sum = 0;
        for (int w : rawW) {
            sum += w + pad;
        }
        sum += gap * (rawW.length - 1);
        return sum;
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Render Pipeline ──
    // ═══════════════════════════════════════════════════════════════

    public static void render(GuiGraphics g, Font font, int screenW, int screenH, double mx, double my) {
        if (!isActive()) return;

        // 1. Semi-transparent backdrop over game world
        g.fill(RenderType.guiOverlay(), 0, 0, screenW, screenH, 0, BG_BACKDROP);

        // 2. Top Header Bar
        renderHeader(g, font, screenW, mx, my);

        // 3. Sub-Header Toolbar
        renderToolbar(g, font, screenW, mx, my);

        // 4. Main Settings List
        renderSettingsList(g, font, screenW, screenH, mx, my);

        // 5. Toast feedback message (bottom center)
        renderToast(g, font, screenW, screenH);
    }

    private static void renderHeader(GuiGraphics g, Font font, int screenW, double mx, double my) {
        g.fill(RenderType.guiOverlay(), 0, 0, screenW, HEADER_H, 0, HEADER_BG);
        g.fill(RenderType.guiOverlay(), 0, HEADER_H - 1, screenW, HEADER_H, 0, BORDER_GOLD);

        HeaderLayout lo = layoutHeader(font, screenW);

        // Title on the left (if visible)
        if (!lo.titleDraw().isEmpty()) {
            g.drawString(font, lo.titleDraw(), lo.titleX(), lo.titleY(), WandscapeTheme.COLOR_TEXT_ACTIVE, false);
        }

        int btnY = lo.closeY();
        int btnH = lo.closeH();

        // Tabs
        SettingTab[] tabs = SettingTab.values();
        for (int i = 0; i < tabs.length; i++) {
            SettingTab tab = tabs[i];
            String label = tab.getDisplayName();
            int tabX = lo.tabX()[i];
            int tabW = lo.tabW()[i];
            boolean active = (tab == activeTab);
            boolean hover = mx >= tabX && mx <= tabX + tabW && my >= btnY && my <= btnY + btnH;

            int bg = active ? 0xFF2A3240 : (hover ? 0x883E4A5E : 0x441E242E);
            g.fill(RenderType.guiOverlay(), tabX, btnY, tabX + tabW, btnY + btnH, 0, bg);
            if (active) {
                g.fill(RenderType.guiOverlay(), tabX, btnY + btnH - 2, tabX + tabW, btnY + btnH, 0, BORDER_GOLD);
            }
            int textColor = active ? WandscapeTheme.COLOR_TEXT_ACTIVE : (hover ? 0xFFFFFFFF : WandscapeTheme.COLOR_TEXT_NORMAL);
            g.drawString(font, label, tabX + (tabW - font.width(label)) / 2, btnY + 7, textColor, false);
        }

        // Close / Exit Button [返回 (ESC)]
        boolean closeHover = lo.isCloseHovered(mx, my);
        int closeBg = closeHover ? 0xCCE53935 : 0x883A2020;
        g.fill(RenderType.guiOverlay(), lo.closeX(), lo.closeY(), lo.closeX() + lo.closeW(), lo.closeY() + lo.closeH(), 0, closeBg);
        String closeText = I18n.string("gui.wandscape.settings.close", "返回 (ESC)");
        g.drawString(font, closeText, lo.closeX() + (lo.closeW() - font.width(closeText)) / 2, lo.closeY() + 7, 0xFFFFFFFF, false);
    }

    private static void renderToolbar(GuiGraphics g, Font font, int screenW, double mx, double my) {
        int y = HEADER_H;
        g.fill(RenderType.guiOverlay(), 0, y, screenW, y + TOOLBAR_H, 0, TOOLBAR_BG);

        boolean canEdit = canModifySettings();

        // Status / prompt
        if (!canEdit) {
            String hint = I18n.string("gui.wandscape.settings.hint.readonly", "只读模式：仅管理员 (OP 等级 2) 可修改设置");
            g.drawString(font, hint, 20, y + 8, 0xFFFFB74D, false);
        } else {
            String hint = (activeTab == SettingTab.PACKAGES)
                    ? I18n.string("gui.wandscape.settings.hint.packages",
                            "管理已加载的建筑包。停用的建筑包将不会在建造栏中显示（即时生效）")
                    : I18n.string("gui.wandscape.settings.hint.general",
                            "配置项修改即时生效并自动持久化保存（支持热重载）");
            g.drawString(font, hint, 20, y + 8, WandscapeTheme.COLOR_TEXT_DIM, false);
        }

        // Reset Page Defaults button on the right
        int rBtnW = 110;
        int rBtnH = 18;
        int rBtnX = screenW - rBtnW - 20;
        int rBtnY = y + 4;
        boolean rHover = canEdit && mx >= rBtnX && mx <= rBtnX + rBtnW && my >= rBtnY && my <= rBtnY + rBtnH;
        int rBg = canEdit ? (rHover ? 0xFFC8A040 : 0x44262E3B) : 0x221E242E;
        int rTextColor = canEdit ? (rHover ? 0xFF111214 : WandscapeTheme.COLOR_TEXT_NORMAL) : 0xFF555555;
        g.fill(RenderType.guiOverlay(), rBtnX, rBtnY, rBtnX + rBtnW, rBtnY + rBtnH, 0, rBg);
        String rText = canEdit
                ? I18n.string("gui.wandscape.settings.reset_tab", "恢复本页默认")
                : I18n.string("gui.wandscape.settings.reset_locked", "锁定 (需 OP)");
        g.drawString(font, rText, rBtnX + (rBtnW - font.width(rText)) / 2, rBtnY + 5, rTextColor, false);
    }

    private static void renderSettingsList(GuiGraphics g, Font font, int screenW, int screenH, double mx, double my) {
        int listY = HEADER_H + TOOLBAR_H + 8;
        int listH = screenH - listY - 10;
        int listW = Math.min(screenW - 40, 780);
        int padX = (screenW - listW) / 2;

        List<SettingItem> items = SettingsRegistry.getItems(activeTab);
        int totalContentH = items.size() * (CARD_H + CARD_GAP);
        int maxScroll = Math.max(0, totalContentH - listH);
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset));

        g.enableScissor(padX - 2, listY, padX + listW + 16, listY + listH);

        for (int i = 0; i < items.size(); i++) {
            int cy = listY - scrollOffset + i * (CARD_H + CARD_GAP);
            if (cy + CARD_H < listY || cy > listY + listH) continue;

            SettingItem item = items.get(i);
            renderSettingCard(g, font, padX, cy, listW, CARD_H, item, mx, my);
        }

        g.disableScissor();

        // Scrollbar if needed
        if (maxScroll > 0) {
            int sbX = padX + listW + 4;
            int sbW = 4;
            g.fill(RenderType.guiOverlay(), sbX, listY, sbX + sbW, listY + listH, 0, 0x33FFFFFF);
            int thumbH = Math.max(20, (int) ((float) listH / totalContentH * listH));
            int thumbY = listY + (int) ((float) scrollOffset / maxScroll * (listH - thumbH));
            g.fill(RenderType.guiOverlay(), sbX, thumbY, sbX + sbW, thumbY + thumbH, 0, BORDER_GOLD);
        }
    }

    private static void renderSettingCard(GuiGraphics g, Font font, int x, int y, int w, int h,
                                         SettingItem item, double mx, double my) {
        boolean cardHover = mx >= x && mx <= x + w && my >= y && my <= y + h;
        g.fill(RenderType.guiOverlay(), x, y, x + w, y + h, 0, cardHover ? CARD_BG_HOVER : CARD_BG);
        g.fill(RenderType.guiOverlay(), x, y, x + w, y + 1, 0, cardHover ? BORDER_GOLD : WandscapeTheme.COLOR_BORDER_NORMAL);
        g.fill(RenderType.guiOverlay(), x, y + h - 1, x + w, y + h, 0, WandscapeTheme.COLOR_BORDER_NORMAL);

        boolean canEdit = canModifySettings();

        // ── Left: Info ──
        int titleColor = cardHover ? WandscapeTheme.COLOR_TEXT_ACTIVE : 0xFFFFFFFF;
        g.drawString(font, item.title(), x + 10, y + 8, titleColor, false);

        int badgeX = x + 10 + font.width(item.title()) + 8;

        // Badge 0: Read-only tag if not OP
        if (!canEdit) {
            String readonlyBadge = I18n.string("gui.wandscape.settings.badge.readonly", "[只读]");
            drawBadge(g, font, badgeX, y + 6, readonlyBadge, 0xFFFFB74D, 0x33FFB74D);
            badgeX += font.width(readonlyBadge) + 6;
        }

        // Badge 1: Hot-reload tag
        String hotBadge = I18n.string("gui.wandscape.settings.badge.hot_reload", "[即时生效]");
        String restartBadge = I18n.string("gui.wandscape.settings.badge.restart", "[需重启]");
        if (item.isHotReloadable()) {
            drawBadge(g, font, badgeX, y + 6, hotBadge, 0xFF4CAF50, 0x334CAF50);
            badgeX += font.width(hotBadge) + 6;
        } else {
            drawBadge(g, font, badgeX, y + 6, restartBadge, 0xFFFFA000, 0x33FFA000);
            badgeX += font.width(restartBadge) + 6;
        }

        // Badge 2: Scope tag
        String clientBadge = I18n.string("gui.wandscape.settings.badge.client", "[客户端]");
        String generalBadge = I18n.string("gui.wandscape.settings.badge.general", "[通用配置]");
        if (item.isClientOnly()) {
            drawBadge(g, font, badgeX, y + 6, clientBadge, 0xFF42A5F5, 0x3342A5F5);
            badgeX += font.width(clientBadge) + 6;
        } else {
            drawBadge(g, font, badgeX, y + 6, generalBadge, 0xFF9E9E9E, 0x339E9E9E);
            badgeX += font.width(generalBadge) + 6;
        }

        // Key path
        g.drawString(font, item.key(), badgeX + 4, y + 8, 0xFF666666, false);

        // Line 2: 默认值。介绍文案与取值区间都不上屏——横排一行放不下，截断后只剩半句废话。
        g.drawString(font, item.defaultHint(), x + 10, y + 24, 0xFFAAAAAA, false);

        // ── Right: Controls ──
        int ctrlRight = x + w - 10;
        int rstBtnW = 34;
        int rstBtnH = 22;
        int rstBtnX = ctrlRight - rstBtnW;
        int rstBtnY = y + CONTROL_Y;

        // [默认] Reset button
        boolean canReset = canEdit && !item.isDefault();
        boolean rstHover = canReset && mx >= rstBtnX && mx <= rstBtnX + rstBtnW && my >= rstBtnY && my <= rstBtnY + rstBtnH;
        int rstBg = canReset ? (rstHover ? 0xFFC8A040 : 0x663E4A5E) : 0x221E242E;
        int rstTextColor = canReset ? (rstHover ? 0xFF111214 : 0xFFFFFFFF) : 0xFF555555;
        g.fill(RenderType.guiOverlay(), rstBtnX, rstBtnY, rstBtnX + rstBtnW, rstBtnY + rstBtnH, 0, rstBg);
        String rstLabel = I18n.string("gui.wandscape.settings.reset", "默认");
        g.drawString(font, rstLabel, rstBtnX + (rstBtnW - font.width(rstLabel)) / 2, rstBtnY + 7, rstTextColor, false);

        int controlAreaRight = rstBtnX - 8;

        switch (item.type()) {
            case BOOLEAN -> {
                SettingItem.BooleanSetting bs = (SettingItem.BooleanSetting) item;
                int btnW = 80;
                int btnH = 22;
                int btnX = controlAreaRight - btnW;
                int btnY = y + 16;
                boolean bHover = canEdit && mx >= btnX && mx <= btnX + btnW && my >= btnY && my <= btnY + btnH;
                boolean val = bs.get();

                int bg;
                int txtColor;
                if (!canEdit) {
                    bg = val ? 0x66C8A040 : 0x22262E3B;
                    txtColor = 0xFF777777;
                } else {
                    bg = val ? (bHover ? 0xFFD4AF37 : 0xFFC8A040) : (bHover ? 0x883E4A5E : 0x44262E3B);
                    txtColor = val ? 0xFF111214 : (bHover ? 0xFFFFFFFF : 0xFF888888);
                }
                g.fill(RenderType.guiOverlay(), btnX, btnY, btnX + btnW, btnY + btnH, 0, bg);
                String btnText = val
                        ? I18n.string("gui.wandscape.settings.toggle_on", "开启 [ON]")
                        : I18n.string("gui.wandscape.settings.toggle_off", "关闭 [OFF]");
                g.drawString(font, btnText, btnX + (btnW - font.width(btnText)) / 2, btnY + 7, txtColor, false);
            }
            case DOUBLE_STEP, INT_STEP -> {
                int btnSize = 22;
                int valBoxW = 90;
                int plusX = controlAreaRight - btnSize;
                int valX = plusX - valBoxW - 4;
                int minusX = valX - btnSize - 4;
                int btnY = y + CONTROL_Y;

                boolean minusHover = canEdit && mx >= minusX && mx <= minusX + btnSize && my >= btnY && my <= btnY + btnSize;
                boolean plusHover = canEdit && mx >= plusX && mx <= plusX + btnSize && my >= btnY && my <= btnY + btnSize;

                // [-]
                int minusBg = canEdit ? (minusHover ? 0x883E4A5E : 0x44262E3B) : 0x221E242E;
                int minusTxt = canEdit ? 0xFFFFFFFF : 0xFF555555;
                g.fill(RenderType.guiOverlay(), minusX, btnY, minusX + btnSize, btnY + btnSize, 0, minusBg);
                g.drawString(font, "-", minusX + (btnSize - font.width("-")) / 2, btnY + 7, minusTxt, false);

                // Value box
                g.fill(RenderType.guiOverlay(), valX, btnY, valX + valBoxW, btnY + btnSize, 0, 0x66161B24);
                String valStr = item.formatValue();
                g.drawString(font, valStr, valX + (valBoxW - font.width(valStr)) / 2, btnY + 7, canEdit ? 0xFFFFFFFF : 0xFFAAAAAA, false);

                // [+]
                int plusBg = canEdit ? (plusHover ? 0x883E4A5E : 0x44262E3B) : 0x221E242E;
                int plusTxt = canEdit ? 0xFFFFFFFF : 0xFF555555;
                g.fill(RenderType.guiOverlay(), plusX, btnY, plusX + btnSize, btnY + btnSize, 0, plusBg);
                g.drawString(font, "+", plusX + (btnSize - font.width("+")) / 2, btnY + 7, plusTxt, false);
            }
            case OPTIONS -> {
                int btnSize = 22;
                int valBoxW = 100;
                int nextX = controlAreaRight - btnSize;
                int valX = nextX - valBoxW - 4;
                int prevX = valX - btnSize - 4;
                int btnY = y + CONTROL_Y;

                boolean prevHover = canEdit && mx >= prevX && mx <= prevX + btnSize && my >= btnY && my <= btnY + btnSize;
                boolean nextHover = canEdit && mx >= nextX && mx <= nextX + btnSize && my >= btnY && my <= btnY + btnSize;

                // [<]
                int prevBg = canEdit ? (prevHover ? 0x883E4A5E : 0x44262E3B) : 0x221E242E;
                int prevTxt = canEdit ? 0xFFFFFFFF : 0xFF555555;
                g.fill(RenderType.guiOverlay(), prevX, btnY, prevX + btnSize, btnY + btnSize, 0, prevBg);
                g.drawString(font, "<", prevX + (btnSize - font.width("<")) / 2, btnY + 7, prevTxt, false);

                // Value box
                g.fill(RenderType.guiOverlay(), valX, btnY, valX + valBoxW, btnY + btnSize, 0, 0x66161B24);
                String optStr = item.formatValue();
                g.drawString(font, optStr, valX + (valBoxW - font.width(optStr)) / 2, btnY + 7, canEdit ? 0xFFFFFFFF : 0xFFAAAAAA, false);

                // [>]
                int nextBg = canEdit ? (nextHover ? 0x883E4A5E : 0x44262E3B) : 0x221E242E;
                int nextTxt = canEdit ? 0xFFFFFFFF : 0xFF555555;
                g.fill(RenderType.guiOverlay(), nextX, btnY, nextX + btnSize, btnY + btnSize, 0, nextBg);
                g.drawString(font, ">", nextX + (btnSize - font.width(">")) / 2, btnY + 7, nextTxt, false);
            }
        }
    }

    private static void drawBadge(GuiGraphics g, Font font, int x, int y, String text, int textColor, int bgColor) {
        int w = font.width(text) + 6;
        int h = font.lineHeight + 2;
        g.fill(RenderType.guiOverlay(), x, y, x + w, y + h, 0, bgColor);
        g.drawString(font, text, x + 3, y + 2, textColor, false);
    }

    private static void renderToast(GuiGraphics g, Font font, int screenW, int screenH) {
        if (System.currentTimeMillis() > toastExpiryTime || toastMessage.isEmpty()) return;

        int toastW = font.width(toastMessage) + 24;
        int toastH = 22;
        int tx = (screenW - toastW) / 2;
        int ty = screenH - toastH - 16;

        g.fill(RenderType.guiOverlay(), tx, ty, tx + toastW, ty + toastH, 0, 0xEE1E242E);
        g.fill(RenderType.guiOverlay(), tx, ty, tx + toastW, ty + 1, 0, BORDER_GOLD);
        g.drawString(font, toastMessage, tx + 12, ty + 7, WandscapeTheme.COLOR_TEXT_ACTIVE, false);
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Mouse Click & Scroll Handlers ──
    // ═══════════════════════════════════════════════════════════════

    public static boolean handleMouseClick(double mx, double my, int screenW, int screenH) {
        if (!isActive()) return false;

        // 1. Header Clicks
        if (my <= HEADER_H) {
            HeaderLayout lo = layoutHeader(Minecraft.getInstance().font, screenW);

            // Close button
            if (lo.isCloseHovered(mx, my)) {
                collapseToPrevious();
                playClickSound();
                return true;
            }

            // Tab buttons
            int clickedTab = lo.getTabAt(mx, my);
            if (clickedTab >= 0 && clickedTab < SettingTab.values().length) {
                setActiveTab(SettingTab.values()[clickedTab]);
                playClickSound();
                return true;
            }
            return true;
        }

        // 2. Toolbar Clicks
        if (my >= HEADER_H && my <= HEADER_H + TOOLBAR_H) {
            int rBtnW = 110;
            int rBtnH = 18;
            int rBtnX = screenW - rBtnW - 20;
            int rBtnY = HEADER_H + 4;
            if (mx >= rBtnX && mx <= rBtnX + rBtnW && my >= rBtnY && my <= rBtnY + rBtnH) {
                if (!canModifySettings()) {
                    showToast(I18n.string("gui.wandscape.settings.toast.no_permission",
                                "权限不足：仅管理员 (OP) 可修改设置"));
                    playClickSound();
                    return true;
                }
                SettingsRegistry.resetTab(activeTab);
                showToast(I18n.string("gui.wandscape.settings.reset_tab_toast", "已恢复本页默认设置"));
                playClickSound();
                return true;
            }
            return true;
        }

        // 3. Main Settings List Clicks
        int listY = HEADER_H + TOOLBAR_H + 8;
        int listH = screenH - listY - 10;
        int listW = Math.min(screenW - 40, 780);
        int padX = (screenW - listW) / 2;

        if (my >= listY && my <= listY + listH && mx >= padX && mx <= padX + listW) {
            List<SettingItem> items = SettingsRegistry.getItems(activeTab);
            boolean shift = Screen.hasShiftDown();

            for (int i = 0; i < items.size(); i++) {
                int cy = listY - scrollOffset + i * (CARD_H + CARD_GAP);
                if (my >= cy && my <= cy + CARD_H) {
                    SettingItem item = items.get(i);
                    int ctrlRight = padX + listW - 10;
                    int rstBtnW = 34;
                    int rstBtnH = 22;
                    int rstBtnX = ctrlRight - rstBtnW;
                    int rstBtnY = cy + CONTROL_Y;

                    // Reset button
                    if (!item.isDefault() && mx >= rstBtnX && mx <= rstBtnX + rstBtnW && my >= rstBtnY && my <= rstBtnY + rstBtnH) {
                        if (!canModifySettings()) {
                            showToast(I18n.string("gui.wandscape.settings.toast.no_permission",
                                "权限不足：仅管理员 (OP) 可修改设置"));
                            playClickSound();
                            return true;
                        }
                        item.resetToDefault();
                        showToast(I18n.string("gui.wandscape.settings.reset_item_toast",
                                "已重置默认: %s", item.title()));
                        playClickSound();
                        return true;
                    }

                    int controlAreaRight = rstBtnX - 8;

                    // If not permitted to modify, clicking anywhere in control area triggers toast and aborts
                    if (!canModifySettings()) {
                        if (mx >= controlAreaRight - 150 && mx <= ctrlRight && my >= cy + 16 && my <= cy + 38) {
                            showToast(I18n.string("gui.wandscape.settings.toast.no_permission",
                                "权限不足：仅管理员 (OP) 可修改设置"));
                            playClickSound();
                        }
                        return true;
                    }

                    switch (item.type()) {
                        case BOOLEAN -> {
                            SettingItem.BooleanSetting bs = (SettingItem.BooleanSetting) item;
                            int btnW = 80;
                            int btnH = 22;
                            int btnX = controlAreaRight - btnW;
                            int btnY = cy + CONTROL_Y;
                            if (mx >= btnX && mx <= btnX + btnW && my >= btnY && my <= btnY + btnH) {
                                bs.toggle();
                                showToast(bs.title() + ": " + bs.formatValue());
                                playClickSound();
                                return true;
                            }
                        }
                        case DOUBLE_STEP -> {
                            SettingItem.DoubleSetting ds = (SettingItem.DoubleSetting) item;
                            int btnSize = 22;
                            int valBoxW = 90;
                            int plusX = controlAreaRight - btnSize;
                            int valX = plusX - valBoxW - 4;
                            int minusX = valX - btnSize - 4;
                            int btnY = cy + CONTROL_Y;

                            if (mx >= minusX && mx <= minusX + btnSize && my >= btnY && my <= btnY + btnSize) {
                                ds.adjust(false, shift);
                                showToast(ds.title() + " → " + ds.formatValue());
                                playClickSound();
                                return true;
                            }
                            if (mx >= plusX && mx <= plusX + btnSize && my >= btnY && my <= btnY + btnSize) {
                                ds.adjust(true, shift);
                                showToast(ds.title() + " → " + ds.formatValue());
                                playClickSound();
                                return true;
                            }
                        }
                        case INT_STEP -> {
                            SettingItem.IntSetting is = (SettingItem.IntSetting) item;
                            int btnSize = 22;
                            int valBoxW = 90;
                            int plusX = controlAreaRight - btnSize;
                            int valX = plusX - valBoxW - 4;
                            int minusX = valX - btnSize - 4;
                            int btnY = cy + CONTROL_Y;

                            if (mx >= minusX && mx <= minusX + btnSize && my >= btnY && my <= btnY + btnSize) {
                                is.adjust(false, shift);
                                showToast(is.title() + " → " + is.formatValue());
                                playClickSound();
                                return true;
                            }
                            if (mx >= plusX && mx <= plusX + btnSize && my >= btnY && my <= btnY + btnSize) {
                                is.adjust(true, shift);
                                showToast(is.title() + " → " + is.formatValue());
                                playClickSound();
                                return true;
                            }
                        }
                        case OPTIONS -> {
                            SettingItem.OptionsSetting os = (SettingItem.OptionsSetting) item;
                            int btnSize = 22;
                            int valBoxW = 100;
                            int nextX = controlAreaRight - btnSize;
                            int valX = nextX - valBoxW - 4;
                            int prevX = valX - btnSize - 4;
                            int btnY = cy + CONTROL_Y;

                            if (mx >= prevX && mx <= prevX + btnSize && my >= btnY && my <= btnY + btnSize) {
                                os.cycle(false);
                                showToast(os.title() + " → " + os.formatValue());
                                playClickSound();
                                return true;
                            }
                            if (mx >= nextX && mx <= nextX + btnSize && my >= btnY && my <= btnY + btnSize) {
                                os.cycle(true);
                                showToast(os.title() + " → " + os.formatValue());
                                playClickSound();
                                return true;
                            }
                        }
                    }
                    return true;
                }
            }
            return true;
        }

        return true;
    }

    public static boolean handleMouseScroll(double deltaY) {
        if (!isActive()) return false;
        List<SettingItem> items = SettingsRegistry.getItems(activeTab);
        Minecraft mc = Minecraft.getInstance();
        int screenH = mc.getWindow().getGuiScaledHeight();
        int listY = HEADER_H + TOOLBAR_H + 8;
        int listH = screenH - listY - 10;
        int totalContentH = items.size() * (CARD_H + CARD_GAP);
        int maxScroll = Math.max(0, totalContentH - listH);
        int delta = deltaY > 0 ? -28 : 28;
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset + delta));
        return true;
    }
}
