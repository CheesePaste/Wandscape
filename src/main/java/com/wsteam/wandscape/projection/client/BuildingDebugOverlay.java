package com.wsteam.wandscape.projection.client;

import java.util.List;
import java.util.UUID;

import com.wsteam.wandscape.projection.network.BuildingActionPacket;
import com.wsteam.wandscape.projection.network.BuildingDebugResponsePacket;
import com.wsteam.wandscape.shared.data.WorkItem;
import com.wsteam.wandscape.shared.ui.I18n;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import com.wsteam.wandscape.shared.log.Log;

import org.lwjgl.glfw.GLFW;

/**
 * Renders a small translucent building-info overlay when debug inspect mode
 * is active (now tied to V panel open/close) and the player is looking at a
 * building.
 *
 * <p>Includes shutdown/restart and destroy action buttons below the info box.
 */
public final class BuildingDebugOverlay {

    private static final String TAG = "BuildingDebugOverlay";

    private static final int BG_COLOR = 0x99000000;
    private static final int BORDER_COLOR = 0x66448822;
    private static final int TEXT_WHITE = 0xFFFFFFFF;
    private static final int TEXT_GRAY = 0xFFAAAAAA;
    private static final int TEXT_STAT = 0xFFCCBB88;
    private static final int TEXT_GREEN = 0xFF88CC88;
    private static final int TEXT_YELLOW = 0xFFFFCC66;
    private static final int TEXT_RED = 0xFFFF8888;
    private static final int TEXT_DIM = 0xFF888888;
    private static final int PAD_X = 10;
    private static final int PAD_Y = 5;
    private static final int GAP = 6;
    private static final int LINE_H = 10;

    // Button colors
    private static final int BTN_SHUTDOWN_BG = 0xCC8B4513;
    private static final int BTN_RESTART_BG = 0xCC2E7D32;
    private static final int BTN_DESTROY_BG = 0xCC8B0000;
    private static final int BTN_HOVER_BRIGHTEN = 0x33333333;
    private static final int BTN_TEXT = 0xFFFFFFFF;
    private static final int BTN_HEIGHT = 16;
    private static final int BTN_GAP = 4;

    // Button bounds — set each frame, read by mouse handler
    private static volatile int btnShutdownX, btnShutdownY, btnShutdownW;
    private static volatile int btnDestroyX, btnDestroyY, btnDestroyW;
    private static volatile boolean buttonsVisible = false;

    private static boolean registered = false;

    private BuildingDebugOverlay() {}

    public static void register() {
        if (registered) return;
        registered = true;
        NeoForge.EVENT_BUS.register(BuildingDebugOverlay.class);
        Log.info(TAG, "[Debug] Overlay registered");
    }

    @SubscribeEvent
    public static void onRenderGuiPost(RenderGuiEvent.Post event) {
        if (!BuildingDebugClientState.isActive()) return;

        BuildingDebugResponsePacket data = BuildingDebugClientState.getDisplayData();
        if (data == null) {
            buttonsVisible = false;
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        if (mc.screen != null) {
            buttonsVisible = false;
            return;
        }

        GuiGraphics g = event.getGuiGraphics();
        Font font = mc.font;
        int screenW = mc.getWindow().getGuiScaledWidth();

        // ── Build all lines ──
        Component l1 = I18n.name("building.wandscape." + data.buildingTypeId(), data.displayName());
        String l1cat = data.category();
        String l1status = getStatusText(data);
        int l1statusColor = getStatusColor(data);

        // We will render stats with icons manually.
        String comfortStr = String.valueOf(data.comfort());
        String magicStr = String.valueOf(data.magic());
        String wonderStr = String.valueOf(data.wonder());
        String queueStr = String.valueOf(data.queueCapacity());
        String l3id = "id:" + shortUuid(data.buildingId());
        String l3cid = data.colonyId() != null ? "cid:" + shortUuid(data.colonyId()) : "no colony";
        String l3anchor = posStr(data.anchor());

        List<WorkItem> queue = data.queue();
        int qSize = queue != null ? queue.size() : 0;
        String l4queue = "queue:" + qSize + " tasks";
        String l4task = data.currentTaskId() != null
                ? "current:" + shortUuid(data.currentTaskId())
                : "no task";

        // ── Measure ──
        int iconW = 9;
        int statsW = (iconW + 2 + font.width(comfortStr))
                   + 10 + (iconW + 2 + font.width(magicStr))
                   + 10 + (iconW + 2 + font.width(wonderStr))
                   + 10 + (font.width("Q:") + 2 + font.width(queueStr));

        int[] widths = {
                font.width(l1) + GAP + font.width(l1cat) + GAP + font.width(l1status),
                statsW,
                font.width(l3id) + GAP + font.width(l3cid) + GAP + font.width(l3anchor),
                font.width(l4queue) + GAP + font.width(l4task)
        };
        int maxW = 0;
        for (int w : widths) if (w > maxW) maxW = w;

        int boxW = maxW + PAD_X * 2 + 24; // Extra padding to make the box wider
        int boxH = font.lineHeight * 4 + PAD_Y * 2 + 3;
        int boxX = (screenW - boxW) / 2;
        int boxY = 4;
        float yBase = boxY + PAD_Y;

        // ── Background ──
        com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.drawRtsBox(g, boxX, boxY, boxW, boxH, false, false);
        g.bufferSource().endBatch(RenderType.guiOverlay());

        // ── Line 1: typeId | category | status ──
        float x = boxX + PAD_X;
        drawText(g, font, l1, x, yBase, com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.COLOR_TEXT_NORMAL);
        x += font.width(l1) + GAP;
        drawText(g, font, l1cat, x, yBase, com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.COLOR_TEXT_DIM);
        x += font.width(l1cat) + GAP;
        drawText(g, font, l1status, x, yBase, l1statusColor);

        // ── Line 2: stats ──
        float x2 = boxX + PAD_X;
        float y2 = yBase + LINE_H;
        
        com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.drawIcon(g, com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.ICON_COMFORT, (int)x2, (int)y2 - 1, 9, 9, com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.COLOR_COMFORT);
        x2 += 11;
        drawText(g, font, comfortStr, x2, y2, com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.COLOR_COMFORT);
        x2 += font.width(comfortStr) + 10;
        
        com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.drawIcon(g, com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.ICON_MAGIC, (int)x2, (int)y2 - 1, 9, 9, com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.COLOR_MAGIC);
        x2 += 11;
        drawText(g, font, magicStr, x2, y2, com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.COLOR_MAGIC);
        x2 += font.width(magicStr) + 10;
        
        com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.drawIcon(g, com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.ICON_WONDER, (int)x2, (int)y2 - 1, 9, 9, com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.COLOR_WONDER);
        x2 += 11;
        drawText(g, font, wonderStr, x2, y2, com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.COLOR_WONDER);
        x2 += font.width(wonderStr) + 10;
        
        drawText(g, font, "Q:", x2, y2, com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.COLOR_TEXT_DIM);
        x2 += font.width("Q:") + 2;
        drawText(g, font, queueStr, x2, y2, com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.COLOR_TEXT_NORMAL);

        // ── Line 3: id | colonyId | anchor ──
        float x3 = boxX + PAD_X;
        drawText(g, font, l3id, x3, yBase + LINE_H * 2, com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.COLOR_TEXT_DIM);
        x3 += font.width(l3id) + GAP;
        drawText(g, font, l3cid, x3, yBase + LINE_H * 2, com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.COLOR_TEXT_DIM);
        x3 += font.width(l3cid) + GAP;
        drawText(g, font, l3anchor, x3, yBase + LINE_H * 2, com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.COLOR_TEXT_DIM);

        // ── Line 4: queue count | current task ──
        float x4 = boxX + PAD_X;
        drawText(g, font, l4queue, x4, yBase + LINE_H * 3, com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.COLOR_TEXT_DIM);
        x4 += font.width(l4queue) + GAP;
        drawText(g, font, l4task, x4, yBase + LINE_H * 3, data.currentTaskId() != null ? com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.COLOR_WONDER : com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.COLOR_TEXT_DIM);

        // ── Buttons ──
        int btnY = boxY + boxH + 2;
        String leftLabel = data.shutdown() ? "Restart" : "Shutdown";

        // Measure button widths from labels
        int leftLabelW = font.width(leftLabel);
        int rightLabelW = font.width("Destroy");
        int btnTotalW = leftLabelW + rightLabelW + PAD_X * 4 + BTN_GAP + 8;
        int btnAreaW = Math.max(btnTotalW, boxW);
        int btnStartX = boxX + (boxW - btnAreaW) / 2;

        int leftW = leftLabelW + PAD_X * 2 + 4;
        int rightW = rightLabelW + PAD_X * 2 + 4;
        int leftX = btnStartX + (btnAreaW - leftW - rightW - BTN_GAP) / 2;
        int rightX = leftX + leftW + BTN_GAP;

        // Hover state
        double guiScale = mc.getWindow().getGuiScale();
        double mx = mc.mouseHandler.xpos() / guiScale;
        double my = mc.mouseHandler.ypos() / guiScale;
        boolean hoverLeft = mx >= leftX && mx <= leftX + leftW && my >= btnY && my <= btnY + BTN_HEIGHT;
        boolean hoverRight = mx >= rightX && mx <= rightX + rightW && my >= btnY && my <= btnY + BTN_HEIGHT;

        // Left button (shutdown / restart)
        com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.drawRtsBox(g, leftX, btnY, leftW, BTN_HEIGHT, false, hoverLeft);
        // Draw a small colored rect to indicate action color (orange/green)
        int leftAccent = data.shutdown() ? BTN_RESTART_BG : BTN_SHUTDOWN_BG;
        g.fill(RenderType.guiOverlay(), leftX, btnY + BTN_HEIGHT - 2, leftX + leftW, btnY + BTN_HEIGHT, 0, leftAccent);
        drawCenteredText(g, font, leftLabel, leftX + leftW / 2, btnY + (BTN_HEIGHT - font.lineHeight) / 2, com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.COLOR_TEXT_NORMAL);

        // Right button (destroy)
        com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.drawRtsBox(g, rightX, btnY, rightW, BTN_HEIGHT, false, hoverRight);
        g.fill(RenderType.guiOverlay(), rightX, btnY + BTN_HEIGHT - 2, rightX + rightW, btnY + BTN_HEIGHT, 0, BTN_DESTROY_BG);
        drawCenteredText(g, font, "Destroy", rightX + rightW / 2, btnY + (BTN_HEIGHT - font.lineHeight) / 2, com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.COLOR_TEXT_NORMAL);

        g.bufferSource().endBatch(RenderType.guiOverlay());

        // Store bounds for mouse handler
        btnShutdownX = leftX;
        btnShutdownY = btnY;
        btnShutdownW = leftW;
        btnDestroyX = rightX;
        btnDestroyY = btnY;
        btnDestroyW = rightW;
        buttonsVisible = true;
    }

    // ── Mouse click handler ──

    @SubscribeEvent
    public static void onMouseButtonPre(InputEvent.MouseButton.Pre event) {
        if (!buttonsVisible) return;
        if (event.getAction() != GLFW.GLFW_PRESS) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) return;

        BuildingDebugResponsePacket data = BuildingDebugClientState.getDisplayData();
        if (data == null) return;
        double guiScale = mc.getWindow().getGuiScale();
        double mx = mc.mouseHandler.xpos() / guiScale;
        double my = mc.mouseHandler.ypos() / guiScale;

        // Check left button (shutdown / restart)
        if (mx >= btnShutdownX && mx <= btnShutdownX + btnShutdownW
                && my >= btnShutdownY && my <= btnShutdownY + BTN_HEIGHT) {
            event.setCanceled(true);
            String action = data.shutdown() ? "restart" : "shutdown";
            PacketDistributor.sendToServer(new BuildingActionPacket(data.buildingId(), action));
            Log.info(TAG, "[Debug] Button click: {} on building {}", action, shortUuid(data.buildingId()));
            return;
        }

        // Check right button (destroy)
        if (mx >= btnDestroyX && mx <= btnDestroyX + btnDestroyW
                && my >= btnDestroyY && my <= btnDestroyY + BTN_HEIGHT) {
            event.setCanceled(true);
            PacketDistributor.sendToServer(new BuildingActionPacket(data.buildingId(), "destroy"));
            Log.info(TAG, "[Debug] Button click: destroy on building {}", shortUuid(data.buildingId()));
        }
    }

    // ── Status helpers ──

    private static String getStatusText(BuildingDebugResponsePacket data) {
        if (data.shutdown()) return "[STOPPED]";
        if (!data.intact()) return "[BROKEN]";
        return "[OK]";
    }

    private static int getStatusColor(BuildingDebugResponsePacket data) {
        if (data.shutdown()) return TEXT_RED;
        if (!data.intact()) return TEXT_YELLOW;
        return TEXT_GREEN;
    }

    // ── Formatting helpers ──

    private static String shortUuid(UUID id) {
        return id.toString().substring(0, 8);
    }

    private static String posStr(BlockPos pos) {
        if (pos == null) return "-";
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static int brighten(int color) {
        int a = (color >> 24) & 0xFF;
        int r = Math.min(0xFF, ((color >> 16) & 0xFF) + 0x30);
        int g = Math.min(0xFF, ((color >> 8) & 0xFF) + 0x30);
        int b = Math.min(0xFF, (color & 0xFF) + 0x30);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    // ── Text helpers ──

    private static void drawText(GuiGraphics g, Font font, String text, float x, float y, int color) {
        font.drawInBatch(text, x, y, color, false,
                g.pose().last().pose(), g.bufferSource(),
                Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0);
    }

    private static void drawText(GuiGraphics g, Font font, Component text, float x, float y, int color) {
        font.drawInBatch(text, x, y, color, false,
                g.pose().last().pose(), g.bufferSource(),
                Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0);
    }

    private static void drawCenteredText(GuiGraphics g, Font font, String text, int x, float y, int color) {
        drawText(g, font, text, x - font.width(text) / 2f, y, color);
    }
}
