package com.wsteam.wandscape.content.colony.exploration.client;

import com.wsteam.wandscape.content.colony.exploration.network.ExplorationRewardPacket;
import com.wsteam.wandscape.content.element.data.ElementType;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.ui.I18n;
import com.wsteam.wandscape.foundation.ui.theme.MedievalColors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Client HUD notification overlay for exploration chest discoveries.
 * Slides down smoothly from the top of the screen and displays gained EXP and elements.
 * Renders on the absolute top layer above any open screens (e.g. chest container GUI).
 * Strictly adheres to project styling: zero emoji/decoration symbols, clean medieval palette.
 */
public final class ExplorationHudOverlay {
    private static final String TAG = "ExplorationHudOverlay";
    private static final long DURATION_MS = 8000L;
    private static final long SLIDE_IN_MS = 250L;
    private static final long FADE_OUT_MS = 400L;
    private static final int TARGET_Y = 14;
    private static final int PADDING_X = 12;
    private static final int TITLE_Y = 6;
    private static final int DETAIL_Y = 18;
    private static final int LINE_HEIGHT = 11;
    private static final int BOTTOM_PADDING = 5;
    private static final String SEPARATOR = " | ";

    private static boolean registered = false;

    private record ActiveNotice(
            String regionName,
            int exp,
            Map<ElementType, Long> elements,
            long startTimeMs
    ) {}

    private static volatile ActiveNotice currentNotice = null;

    private ExplorationHudOverlay() {}

    public static void register() {
        if (registered) return;
        registered = true;
        // In-game HUD when no screen is open
        NeoForge.EVENT_BUS.addListener(RenderGuiEvent.Post.class, ExplorationHudOverlay::onRenderGuiPost);
        // On top of any open screen (chest GUI, inventory, etc.)
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Render.Post.class, ExplorationHudOverlay::onScreenRenderPost);
        Log.info(TAG, "ExplorationHudOverlay registered (top-layer)");
    }

    public static void showReward(ExplorationRewardPacket packet) {
        if (packet == null) return;
        currentNotice = new ActiveNotice(
                packet.regionName(),
                packet.exp(),
                packet.elements(),
                System.currentTimeMillis()
        );
    }

    private static void onRenderGuiPost(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) return; // Let onScreenRenderPost handle rendering on top of the open screen
        if (mc.getWindow() == null) return;

        renderNotice(event.getGuiGraphics(), mc.getWindow().getGuiScaledWidth());
    }

    private static void onScreenRenderPost(ScreenEvent.Render.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getWindow() == null) return;

        renderNotice(event.getGuiGraphics(), mc.getWindow().getGuiScaledWidth());
    }

    private static void renderNotice(GuiGraphics gui, int screenW) {
        ActiveNotice notice = currentNotice;
        if (notice == null) return;

        Minecraft mc = Minecraft.getInstance();
        long elapsed = System.currentTimeMillis() - notice.startTimeMs();
        if (elapsed >= DURATION_MS) {
            currentNotice = null;
            return;
        }

        float alpha = 1.0f;
        if (elapsed > DURATION_MS - FADE_OUT_MS) {
            alpha = Math.max(0.0f, (DURATION_MS - elapsed) / (float) FADE_OUT_MS);
        }

        Font font = mc.font;

        String title = I18n.string("wandscape.exploration.discovered", "探索发现：%s", notice.regionName());

        List<String> segments = new ArrayList<>();
        segments.add(I18n.string("wandscape.exploration.exp_gain", "小镇经验 +%d", notice.exp()));
        for (Map.Entry<ElementType, Long> e : notice.elements().entrySet()) {
            String elemName = I18n.string("element.wandscape." + e.getKey().getId(), e.getKey().getId());
            segments.add(elemName + " +" + e.getValue());
        }

        // Every chest now pays out several elements, so wrap instead of running off-screen.
        int maxContentW = Math.max(120, screenW - 40 - PADDING_X * 2);
        List<String> detailLines = wrapSegments(segments, font, maxContentW);

        int titleW = font.width(title);
        int contentW = titleW;
        for (String line : detailLines) {
            contentW = Math.max(contentW, font.width(line));
        }
        int cardW = Math.min(screenW, Math.max(180, contentW + PADDING_X * 2));
        int cardH = DETAIL_Y + detailLines.size() * LINE_HEIGHT + BOTTOM_PADDING;
        int x = (screenW - cardW) / 2;

        float slideProgress = Math.min(1.0f, elapsed / (float) SLIDE_IN_MS);
        float ease = 1.0f - (1.0f - slideProgress) * (1.0f - slideProgress);
        int y = (int) (-cardH + (TARGET_Y + cardH) * ease);

        int bgAlpha = (int) (alpha * 230);
        int borderAlpha = (int) (alpha * 255);
        int textAlpha = (int) (alpha * 255);

        int bgColor = (bgAlpha << 24) | 0x1A0E04;
        int borderColor = (borderAlpha << 24) | (MedievalColors.BORDER_GOLD & 0x00FFFFFF);
        int titleColor = (textAlpha << 24) | 0xDEC478;
        int detailColor = (textAlpha << 24) | 0x88EE88;

        // Elevate to top-most z layer (800) so nothing in any screen can dim or cover it
        gui.flush();
        gui.pose().pushPose();
        gui.pose().translate(0, 0, 800);

        // Background
        gui.fill(x, y, x + cardW, y + cardH, bgColor);

        // Borders
        gui.fill(x, y, x + cardW, y + 1, borderColor);
        gui.fill(x, y + cardH - 1, x + cardW, y + cardH, borderColor);
        gui.fill(x, y, x + 1, y + cardH, borderColor);
        gui.fill(x + cardW - 1, y, x + cardW, y + cardH, borderColor);

        // Text
        gui.drawString(font, title, x + (cardW - titleW) / 2, y + TITLE_Y, titleColor, false);
        for (int i = 0; i < detailLines.size(); i++) {
            String line = detailLines.get(i);
            gui.drawString(font, line, x + (cardW - font.width(line)) / 2, y + DETAIL_Y + i * LINE_HEIGHT, detailColor, false);
        }

        gui.pose().popPose();
        gui.flush();
    }

    /** Greedily pack segments into lines that fit {@code maxWidth}, keeping the separator between them. */
    private static List<String> wrapSegments(List<String> segments, Font font, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String segment : segments) {
            String candidate = current.length() == 0 ? segment : current + SEPARATOR + segment;
            if (current.length() > 0 && font.width(candidate) > maxWidth) {
                lines.add(current.toString());
                current = new StringBuilder(segment);
            } else {
                current = new StringBuilder(candidate);
            }
        }
        if (current.length() > 0) {
            lines.add(current.toString());
        }
        return lines;
    }
}
