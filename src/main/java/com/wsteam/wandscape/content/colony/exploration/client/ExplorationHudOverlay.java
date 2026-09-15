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
import net.neoforged.neoforge.common.NeoForge;

import java.util.Map;

/**
 * Client HUD notification overlay for exploration chest discoveries.
 * Slides down smoothly from the top of the screen and displays gained EXP and elements.
 * Strictly adheres to project styling: zero emoji/decoration symbols, clean medieval palette.
 */
public final class ExplorationHudOverlay {
    private static final String TAG = "ExplorationHudOverlay";
    private static final long DURATION_MS = 4000L;
    private static final long SLIDE_IN_MS = 250L;
    private static final long FADE_OUT_MS = 400L;
    private static final int CARD_HEIGHT = 34;
    private static final int TARGET_Y = 14;

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
        NeoForge.EVENT_BUS.addListener(RenderGuiEvent.Post.class, ExplorationHudOverlay::onRenderGuiPost);
        Log.info(TAG, "ExplorationHudOverlay registered");
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
        ActiveNotice notice = currentNotice;
        if (notice == null) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.getWindow() == null) return;

        long elapsed = System.currentTimeMillis() - notice.startTimeMs();
        if (elapsed >= DURATION_MS) {
            currentNotice = null;
            return;
        }

        float alpha = 1.0f;
        if (elapsed > DURATION_MS - FADE_OUT_MS) {
            alpha = Math.max(0.0f, (DURATION_MS - elapsed) / (float) FADE_OUT_MS);
        }

        float slideProgress = Math.min(1.0f, elapsed / (float) SLIDE_IN_MS);
        float ease = 1.0f - (1.0f - slideProgress) * (1.0f - slideProgress);
        int y = (int) (-CARD_HEIGHT + (TARGET_Y + CARD_HEIGHT) * ease);

        GuiGraphics gui = event.getGuiGraphics();
        Font font = mc.font;
        int screenW = mc.getWindow().getGuiScaledWidth();

        String title = I18n.string("wandscape.exploration.discovered", "探索发现：%s", notice.regionName());

        StringBuilder detailBuilder = new StringBuilder();
        detailBuilder.append(I18n.string("wandscape.exploration.exp_gain", "小镇经验 +%d", notice.exp()));
        if (!notice.elements().isEmpty()) {
            for (Map.Entry<ElementType, Long> e : notice.elements().entrySet()) {
                String elemName = I18n.string("element.wandscape." + e.getKey().getId(), e.getKey().getId());
                detailBuilder.append(" | ").append(elemName).append(" +").append(e.getValue());
            }
        }
        String detail = detailBuilder.toString();

        int titleW = font.width(title);
        int detailW = font.width(detail);
        int contentW = Math.max(titleW, detailW);
        int cardW = Math.max(180, contentW + 24);
        int x = (screenW - cardW) / 2;

        int bgAlpha = (int) (alpha * 220);
        int borderAlpha = (int) (alpha * 240);
        int textAlpha = (int) (alpha * 255);

        int bgColor = (bgAlpha << 24) | 0x1A0E04;
        int borderColor = (borderAlpha << 24) | (MedievalColors.BORDER_GOLD & 0x00FFFFFF);
        int titleColor = (textAlpha << 24) | 0xDEC478;
        int detailColor = (textAlpha << 24) | 0x88EE88;

        // Background
        gui.fill(x, y, x + cardW, y + CARD_HEIGHT, bgColor);

        // Borders
        gui.fill(x, y, x + cardW, y + 1, borderColor);
        gui.fill(x, y + CARD_HEIGHT - 1, x + cardW, y + CARD_HEIGHT, borderColor);
        gui.fill(x, y, x + 1, y + CARD_HEIGHT, borderColor);
        gui.fill(x + cardW - 1, y, x + cardW, y + CARD_HEIGHT, borderColor);

        // Text
        int titleX = x + (cardW - titleW) / 2;
        int detailX = x + (cardW - detailW) / 2;

        gui.drawString(font, title, titleX, y + 6, titleColor, false);
        gui.drawString(font, detail, detailX, y + 18, detailColor, false);
    }
}
