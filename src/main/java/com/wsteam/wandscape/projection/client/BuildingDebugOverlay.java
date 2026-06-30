package com.wsteam.wandscape.projection.client;

import java.util.List;
import java.util.UUID;

import com.wsteam.wandscape.projection.network.BuildingDebugResponsePacket;
import com.wsteam.wandscape.shared.data.WorkItem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Renders a small translucent building-info overlay when the debug
 * inspect mode (G key) is active and the player is looking at a
 * building.
 *
 * <p>No style template / MedievalScreen — just compact text on a
 * transparent dark background.
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
        if (!BuildingDebugClientState.isActive()) {
            return; // too noisy to log every frame
        }

        BuildingDebugResponsePacket data = BuildingDebugClientState.getCachedData();
        if (data == null) {
            // active but no data — logged once per state change in the controller
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        if (mc.screen != null) {
            Log.info(TAG, "[Debug] Overlay skipped — screen is open (class={})",
                    mc.screen.getClass().getSimpleName());
            return;
        }

        GuiGraphics g = event.getGuiGraphics();
        Font font = mc.font;
        int screenW = mc.getWindow().getGuiScaledWidth();

        // ── Build all lines ──
        String l1 = data.buildingTypeId();
        String l1cat = data.category();
        String l1status = getStatusText(data);
        int l1statusColor = getStatusColor(data);

        String l2stats = "C:" + data.comfort() + "  M:" + data.magic()
                + "  W:" + data.wonder() + "  Q:" + data.queueCapacity();

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
        int[] widths = {
                font.width(l1) + GAP + font.width(l1cat) + GAP + font.width(l1status),
                font.width(l2stats),
                font.width(l3id) + GAP + font.width(l3cid) + GAP + font.width(l3anchor),
                font.width(l4queue) + GAP + font.width(l4task)
        };
        int maxW = 0;
        for (int w : widths) if (w > maxW) maxW = w;

        int boxW = maxW + PAD_X * 2;
        int boxH = font.lineHeight * 4 + PAD_Y * 2 + 3;
        int boxX = (screenW - boxW) / 2;
        int boxY = 4;
        float yBase = boxY + PAD_Y;

        Log.info(TAG, "[Debug] Rendering overlay — type={} category={} status={} box=({},{})-({},{})",
                l1, l1cat, l1status, boxX, boxY, boxX + boxW, boxY + boxH);
        Log.info(TAG, "[Debug]   stats={} id={} colony={} anchor={}",
                l2stats, shortUuid(data.buildingId()),
                data.colonyId() != null ? shortUuid(data.colonyId()) : "-",
                l3anchor);
        Log.info(TAG, "[Debug]   queue={} task={}", l4queue, l4task);

        // ── Background ──
        g.fill(RenderType.guiOverlay(), boxX, boxY, boxX + boxW, boxY + boxH, 0, BG_COLOR);
        g.fill(RenderType.guiOverlay(), boxX, boxY + boxH - 1, boxX + boxW, boxY + boxH, 0, BORDER_COLOR);
        g.bufferSource().endBatch(RenderType.guiOverlay());

        // ── Line 1: typeId | category | status ──
        float x = boxX + PAD_X;
        drawText(g, font, l1, x, yBase, TEXT_WHITE);
        x += font.width(l1) + GAP;
        drawText(g, font, l1cat, x, yBase, TEXT_GRAY);
        x += font.width(l1cat) + GAP;
        drawText(g, font, l1status, x, yBase, l1statusColor);

        // ── Line 2: stats ──
        drawText(g, font, l2stats, boxX + PAD_X, yBase + LINE_H, TEXT_STAT);

        // ── Line 3: id | colonyId | anchor ──
        float x3 = boxX + PAD_X;
        drawText(g, font, l3id, x3, yBase + LINE_H * 2, TEXT_DIM);
        x3 += font.width(l3id) + GAP;
        drawText(g, font, l3cid, x3, yBase + LINE_H * 2, TEXT_DIM);
        x3 += font.width(l3cid) + GAP;
        drawText(g, font, l3anchor, x3, yBase + LINE_H * 2, TEXT_GRAY);

        // ── Line 4: queue count | current task ──
        float x4 = boxX + PAD_X;
        drawText(g, font, l4queue, x4, yBase + LINE_H * 3, TEXT_DIM);
        x4 += font.width(l4queue) + GAP;
        drawText(g, font, l4task, x4, yBase + LINE_H * 3, data.currentTaskId() != null ? TEXT_YELLOW : TEXT_DIM);
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

    // ── Text helper (SEE_THROUGH = NO_DEPTH_TEST) ──

    private static void drawText(GuiGraphics g, Font font, String text, float x, float y, int color) {
        font.drawInBatch(text, x, y, color, false,
                g.pose().last().pose(), g.bufferSource(),
                Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0);
    }
}
