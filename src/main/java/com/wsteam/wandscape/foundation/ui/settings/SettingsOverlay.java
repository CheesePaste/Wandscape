package com.wsteam.wandscape.foundation.ui.settings;

import com.wsteam.wandscape.content.colony.overview.client.OverviewClientState;
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
    private static final int CARD_H = 54;
    private static final int CARD_GAP = 6;
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

    public static void collapseToPrevious() {
        WandscapePanelState.exitCurrentSubMode();
        if (!OverviewClientState.isActive()) {
            WandscapePanelState.setSubMode(WandscapePanelState.SubMode.NONE);
        }
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

        // Title on the left
        String colony = WandscapePanelState.getColonyName();
        String titlePrefix = (colony != null && !colony.isEmpty()) ? colony : "魔法小镇";
        String fullTitle = titlePrefix + " 设置中心";
        g.drawString(font, fullTitle, 16, 12, WandscapeTheme.COLOR_TEXT_ACTIVE, false);

        int titleEnd = 16 + font.width(fullTitle) + 24;
        int btnY = 6;
        int btnH = 22;

        // Tabs
        SettingTab[] tabs = SettingTab.values();
        int totalTabsW = 0;
        for (SettingTab tab : tabs) {
            totalTabsW += font.width(tab.getDisplayName()) + 26;
        }
        int curX = Math.max(titleEnd, (screenW - totalTabsW) / 2);
        for (SettingTab tab : tabs) {
            String label = tab.getDisplayName();
            int tabW = font.width(label) + 20;
            boolean active = (tab == activeTab);
            boolean hover = mx >= curX && mx <= curX + tabW && my >= btnY && my <= btnY + btnH;

            int bg = active ? 0xFF2A3240 : (hover ? 0x883E4A5E : 0x441E242E);
            g.fill(RenderType.guiOverlay(), curX, btnY, curX + tabW, btnY + btnH, 0, bg);
            if (active) {
                g.fill(RenderType.guiOverlay(), curX, btnY + btnH - 2, curX + tabW, btnY + btnH, 0, BORDER_GOLD);
            }
            int textColor = active ? WandscapeTheme.COLOR_TEXT_ACTIVE : (hover ? 0xFFFFFFFF : WandscapeTheme.COLOR_TEXT_NORMAL);
            g.drawString(font, label, curX + 10, btnY + 7, textColor, false);

            curX += tabW + 6;
        }

        // Close / Exit Button [返回 (ESC)]
        int closeW = HEADER_CLOSE_W;
        int closeX = screenW - closeW - HEADER_CLOSE_GAP;
        boolean closeHover = mx >= closeX && mx <= closeX + closeW && my >= btnY && my <= btnY + btnH;
        int closeBg = closeHover ? 0xCCE53935 : 0x883A2020;
        g.fill(RenderType.guiOverlay(), closeX, btnY, closeX + closeW, btnY + btnH, 0, closeBg);
        String closeText = "返回 (ESC)";
        g.drawString(font, closeText, closeX + (closeW - font.width(closeText)) / 2, btnY + 7, 0xFFFFFFFF, false);
    }

    private static void renderToolbar(GuiGraphics g, Font font, int screenW, double mx, double my) {
        int y = HEADER_H;
        g.fill(RenderType.guiOverlay(), 0, y, screenW, y + TOOLBAR_H, 0, TOOLBAR_BG);

        // Status / prompt
        String hint = (activeTab == SettingTab.PACKAGES)
                ? "管理已加载的建筑包。停用的建筑包将不会在建造栏中显示（即时生效）"
                : "配置项修改即时生效并自动持久化保存（支持热重载）";
        g.drawString(font, hint, 20, y + 8, WandscapeTheme.COLOR_TEXT_DIM, false);

        // Reset Page Defaults button on the right
        int rBtnW = 110;
        int rBtnH = 18;
        int rBtnX = screenW - rBtnW - 20;
        int rBtnY = y + 4;
        boolean rHover = mx >= rBtnX && mx <= rBtnX + rBtnW && my >= rBtnY && my <= rBtnY + rBtnH;
        int rBg = rHover ? 0xFFC8A040 : 0x44262E3B;
        int rTextColor = rHover ? 0xFF111214 : WandscapeTheme.COLOR_TEXT_NORMAL;
        g.fill(RenderType.guiOverlay(), rBtnX, rBtnY, rBtnX + rBtnW, rBtnY + rBtnH, 0, rBg);
        String rText = "恢复本页默认";
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

        // ── Left: Info ──
        int titleColor = cardHover ? WandscapeTheme.COLOR_TEXT_ACTIVE : 0xFFFFFFFF;
        g.drawString(font, item.title(), x + 10, y + 8, titleColor, false);

        int badgeX = x + 10 + font.width(item.title()) + 8;

        // Badge 1: Hot-reload tag
        if (item.isHotReloadable()) {
            drawBadge(g, font, badgeX, y + 6, "[即时生效]", 0xFF4CAF50, 0x334CAF50);
            badgeX += font.width("[即时生效]") + 6;
        } else {
            drawBadge(g, font, badgeX, y + 6, "[需重启]", 0xFFFFA000, 0x33FFA000);
            badgeX += font.width("[需重启]") + 6;
        }

        // Badge 2: Scope tag
        if (item.isClientOnly()) {
            drawBadge(g, font, badgeX, y + 6, "[客户端]", 0xFF42A5F5, 0x3342A5F5);
            badgeX += font.width("[客户端]") + 6;
        } else {
            drawBadge(g, font, badgeX, y + 6, "[通用配置]", 0xFF9E9E9E, 0x339E9E9E);
            badgeX += font.width("[通用配置]") + 6;
        }

        // Key path
        g.drawString(font, item.key(), badgeX + 4, y + 8, 0xFF666666, false);

        // Line 2: Description
        int maxDescW = w - 260;
        String desc = item.description();
        if (font.width(desc) > maxDescW) {
            desc = font.plainSubstrByWidth(desc, maxDescW - 10) + "...";
        }
        g.drawString(font, desc, x + 10, y + 23, 0xFFAAAAAA, false);

        // Line 3: Range / Default hint
        g.drawString(font, item.rangeHint(), x + 10, y + 38, 0xFF777777, false);

        // ── Right: Controls ──
        int ctrlRight = x + w - 10;
        int rstBtnW = 34;
        int rstBtnH = 22;
        int rstBtnX = ctrlRight - rstBtnW;
        int rstBtnY = y + 16;

        // [默认] Reset button
        boolean canReset = !item.isDefault();
        boolean rstHover = canReset && mx >= rstBtnX && mx <= rstBtnX + rstBtnW && my >= rstBtnY && my <= rstBtnY + rstBtnH;
        int rstBg = canReset ? (rstHover ? 0xFFC8A040 : 0x663E4A5E) : 0x221E242E;
        int rstTextColor = canReset ? (rstHover ? 0xFF111214 : 0xFFFFFFFF) : 0xFF555555;
        g.fill(RenderType.guiOverlay(), rstBtnX, rstBtnY, rstBtnX + rstBtnW, rstBtnY + rstBtnH, 0, rstBg);
        g.drawString(font, "默认", rstBtnX + (rstBtnW - font.width("默认")) / 2, rstBtnY + 7, rstTextColor, false);

        int controlAreaRight = rstBtnX - 8;

        switch (item.type()) {
            case BOOLEAN -> {
                SettingItem.BooleanSetting bs = (SettingItem.BooleanSetting) item;
                int btnW = 80;
                int btnH = 22;
                int btnX = controlAreaRight - btnW;
                int btnY = y + 16;
                boolean bHover = mx >= btnX && mx <= btnX + btnW && my >= btnY && my <= btnY + btnH;
                boolean val = bs.get();

                int bg = val ? (bHover ? 0xFFD4AF37 : 0xFFC8A040) : (bHover ? 0x883E4A5E : 0x44262E3B);
                int txtColor = val ? 0xFF111214 : (bHover ? 0xFFFFFFFF : 0xFF888888);
                g.fill(RenderType.guiOverlay(), btnX, btnY, btnX + btnW, btnY + btnH, 0, bg);
                String btnText = val ? "开启 [ON]" : "关闭 [OFF]";
                g.drawString(font, btnText, btnX + (btnW - font.width(btnText)) / 2, btnY + 7, txtColor, false);
            }
            case DOUBLE_STEP, INT_STEP -> {
                int btnSize = 22;
                int valBoxW = 90;
                int plusX = controlAreaRight - btnSize;
                int valX = plusX - valBoxW - 4;
                int minusX = valX - btnSize - 4;
                int btnY = y + 16;

                boolean minusHover = mx >= minusX && mx <= minusX + btnSize && my >= btnY && my <= btnY + btnSize;
                boolean plusHover = mx >= plusX && mx <= plusX + btnSize && my >= btnY && my <= btnY + btnSize;

                // [-]
                g.fill(RenderType.guiOverlay(), minusX, btnY, minusX + btnSize, btnY + btnSize, 0,
                        minusHover ? 0x883E4A5E : 0x44262E3B);
                g.drawString(font, "-", minusX + (btnSize - font.width("-")) / 2, btnY + 7, 0xFFFFFFFF, false);

                // Value box
                g.fill(RenderType.guiOverlay(), valX, btnY, valX + valBoxW, btnY + btnSize, 0, 0x66161B24);
                String valStr = item.formatValue();
                g.drawString(font, valStr, valX + (valBoxW - font.width(valStr)) / 2, btnY + 7, 0xFFFFFFFF, false);

                // [+]
                g.fill(RenderType.guiOverlay(), plusX, btnY, plusX + btnSize, btnY + btnSize, 0,
                        plusHover ? 0x883E4A5E : 0x44262E3B);
                g.drawString(font, "+", plusX + (btnSize - font.width("+")) / 2, btnY + 7, 0xFFFFFFFF, false);
            }
            case OPTIONS -> {
                int btnSize = 22;
                int valBoxW = 100;
                int nextX = controlAreaRight - btnSize;
                int valX = nextX - valBoxW - 4;
                int prevX = valX - btnSize - 4;
                int btnY = y + 16;

                boolean prevHover = mx >= prevX && mx <= prevX + btnSize && my >= btnY && my <= btnY + btnSize;
                boolean nextHover = mx >= nextX && mx <= nextX + btnSize && my >= btnY && my <= btnY + btnSize;

                // [<]
                g.fill(RenderType.guiOverlay(), prevX, btnY, prevX + btnSize, btnY + btnSize, 0,
                        prevHover ? 0x883E4A5E : 0x44262E3B);
                g.drawString(font, "<", prevX + (btnSize - font.width("<")) / 2, btnY + 7, 0xFFFFFFFF, false);

                // Value box
                g.fill(RenderType.guiOverlay(), valX, btnY, valX + valBoxW, btnY + btnSize, 0, 0x66161B24);
                String optStr = item.formatValue();
                g.drawString(font, optStr, valX + (valBoxW - font.width(optStr)) / 2, btnY + 7, 0xFFFFFFFF, false);

                // [>]
                g.fill(RenderType.guiOverlay(), nextX, btnY, nextX + btnSize, btnY + btnSize, 0,
                        nextHover ? 0x883E4A5E : 0x44262E3B);
                g.drawString(font, ">", nextX + (btnSize - font.width(">")) / 2, btnY + 7, 0xFFFFFFFF, false);
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
            int btnY = 6;
            int btnH = 22;

            // Close button
            int closeW = HEADER_CLOSE_W;
            int closeX = screenW - closeW - HEADER_CLOSE_GAP;
            if (mx >= closeX && mx <= closeX + closeW && my >= btnY && my <= btnY + btnH) {
                collapseToPrevious();
                playClickSound();
                return true;
            }

            // Tab buttons
            String colony = WandscapePanelState.getColonyName();
            String fullTitle = (colony != null && !colony.isEmpty() ? colony : "魔法小镇") + " 设置中心";
            int titleEnd = 16 + Minecraft.getInstance().font.width(fullTitle) + 24;
            int totalTabsW = 0;
            for (SettingTab tab : SettingTab.values()) {
                totalTabsW += Minecraft.getInstance().font.width(tab.getDisplayName()) + 26;
            }
            int curX = Math.max(titleEnd, (screenW - totalTabsW) / 2);

            for (SettingTab tab : SettingTab.values()) {
                int tabW = Minecraft.getInstance().font.width(tab.getDisplayName()) + 20;
                if (mx >= curX && mx <= curX + tabW && my >= btnY && my <= btnY + btnH) {
                    setActiveTab(tab);
                    playClickSound();
                    return true;
                }
                curX += tabW + 6;
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
                SettingsRegistry.resetTab(activeTab);
                showToast("已恢复本页默认设置");
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
                    int rstBtnY = cy + 16;

                    // Reset button
                    if (!item.isDefault() && mx >= rstBtnX && mx <= rstBtnX + rstBtnW && my >= rstBtnY && my <= rstBtnY + rstBtnH) {
                        item.resetToDefault();
                        showToast("已重置默认: " + item.title());
                        playClickSound();
                        return true;
                    }

                    int controlAreaRight = rstBtnX - 8;

                    switch (item.type()) {
                        case BOOLEAN -> {
                            SettingItem.BooleanSetting bs = (SettingItem.BooleanSetting) item;
                            int btnW = 80;
                            int btnH = 22;
                            int btnX = controlAreaRight - btnW;
                            int btnY = cy + 16;
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
                            int btnY = cy + 16;

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
                            int btnY = cy + 16;

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
                            int btnY = cy + 16;

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
