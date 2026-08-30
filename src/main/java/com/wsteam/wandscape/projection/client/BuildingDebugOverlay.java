package com.wsteam.wandscape.projection.client;

import com.wsteam.wandscape.projection.network.BuildingActionPacket;
import com.wsteam.wandscape.projection.network.BuildingDebugResponsePacket;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.ui.I18n;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.UUID;

/**
 * Renders a small translucent building-info overlay when debug inspect mode
 * is active (now tied to V panel open/close) and the player is looking at a
 * building.
 *
 * <p>Includes repair/undo and destroy action buttons below the info box.
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
    private static final int TEXT_BLUE = 0xFF88AAFF;
    private static final int TEXT_DIM = 0xFF888888;
    private static final int PAD_X = 10;
    private static final int PAD_Y = 5;
    private static final int GAP = 6;
    private static final int LINE_H = 10;

    // Button colors
    private static final int BTN_REPAIR_BG = 0xCC2E7D32;
    private static final int BTN_CANCEL_BG = 0xCCB8860B;
    private static final int BTN_DESTROY_BG = 0xCC8B0000;
    private static final int BTN_HOVER_BRIGHTEN = 0x33333333;
    private static final int BTN_TEXT = 0xFFFFFFFF;
    private static final int BTN_HEIGHT = 16;
    private static final int BTN_GAP = 4;

    // Button bounds — set each frame, read by mouse handler
    private static volatile int btnRepairX, btnRepairY, btnRepairW;
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
        // 建筑信息顶栏仅在俯瞰(OVERVIEW)模式显示——其余子模式（含 TASKS）一律不弹
        if (!com.wsteam.wandscape.shared.ui.panel.WandscapePanelState.isInspectContext()
                || com.wsteam.wandscape.shared.ui.panel.WandscapePanelState.isPanelHidden()
                || com.wsteam.wandscape.shared.ui.panel.TaskManagementOverlay.isActive()) {
            buttonsVisible = false;
            return;
        }

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
        Component l1status = getStatusText(data);
        int l1statusColor = getStatusColor(data);

        // We will render stats with icons manually.
        String comfortStr = String.valueOf(data.comfort());
        String magicStr = String.valueOf(data.magic());
        String wonderStr = String.valueOf(data.wonder());

        // ── Measure ──
        int iconW = 9;
        int statsW = (iconW + 2 + font.width(comfortStr))
                   + 10 + (iconW + 2 + font.width(magicStr))
                   + 10 + (iconW + 2 + font.width(wonderStr));

        int[] widths = {
                font.width(l1) + GAP + font.width(l1cat) + GAP + font.width(l1status),
                statsW
        };
        int maxW = 0;
        for (int w : widths) if (w > maxW) maxW = w;

        int boxW = maxW + PAD_X * 2 + 24; // Extra padding to make the box wider
        int boxH = font.lineHeight * 2 + PAD_Y * 2 + 3;
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

        // ── Buttons: Repair/Undo | Destroy ──
        int btnY = boxY + boxH + 2;
        // Under-construction buildings aren't damaged yet — the first button becomes
        // 撤销 (undo) so a mis-placed building can be cancelled instead of a useless repair.
        boolean underConstruction = data.underConstruction();
        boolean demolishing = data.demolishing();
        Component repairLabel = underConstruction
                ? I18n.name("gui.wandscape.building_action.cancel", "Undo")
                : I18n.name("gui.wandscape.building_action.repair", "Repair");
        Component destroyLabel = demolishing
                ? I18n.name("gui.wandscape.building_action.demolishing", "Demolishing...")
                : I18n.name("gui.wandscape.building_action.destroy", "Destroy");

        int repairLabelW = font.width(repairLabel);
        int destroyLabelW = font.width(destroyLabel);

        int repairW = repairLabelW + PAD_X * 2 + 4;
        int destroyW = destroyLabelW + PAD_X * 2 + 4;
        int btnTotalW = repairW + destroyW + BTN_GAP;
        int btnAreaW = Math.max(btnTotalW, boxW);
        int btnStartX = boxX + (boxW - btnAreaW) / 2;

        int repairX = btnStartX + (btnAreaW - btnTotalW) / 2;
        int destroyX = repairX + repairW + BTN_GAP;

        // Hover state
        double guiScale = mc.getWindow().getGuiScale();
        double mx = mc.mouseHandler.xpos() / guiScale;
        double my = mc.mouseHandler.ypos() / guiScale;
        boolean repairEnabled = !demolishing && (underConstruction || data.needsRepair());
        boolean destroyEnabled = !demolishing;
        boolean hoverRepair = repairEnabled && mx >= repairX && mx <= repairX + repairW && my >= btnY && my <= btnY + BTN_HEIGHT;
        boolean hoverDestroy = destroyEnabled && mx >= destroyX && mx <= destroyX + destroyW && my >= btnY && my <= btnY + BTN_HEIGHT;

        // Undo/repair button (left) — undo for under-construction, else repair when damaged
        com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.drawRtsBox(g, repairX, btnY, repairW, BTN_HEIGHT, false, hoverRepair);
        int repairAccent = underConstruction ? BTN_CANCEL_BG : BTN_REPAIR_BG;
        g.fill(RenderType.guiOverlay(), repairX, btnY + BTN_HEIGHT - 2, repairX + repairW, btnY + BTN_HEIGHT, 0,
                repairEnabled ? repairAccent : 0x66445544);
        drawCenteredText(g, font, repairLabel, repairX + repairW / 2, btnY + (BTN_HEIGHT - font.lineHeight) / 2,
                repairEnabled ? com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.COLOR_TEXT_NORMAL : TEXT_DIM);

        // Destroy button (dark red, right)
        com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.drawRtsBox(g, destroyX, btnY, destroyW, BTN_HEIGHT, false, hoverDestroy);
        g.fill(RenderType.guiOverlay(), destroyX, btnY + BTN_HEIGHT - 2, destroyX + destroyW, btnY + BTN_HEIGHT, 0,
                destroyEnabled ? BTN_DESTROY_BG : 0x66553333);
        drawCenteredText(g, font, destroyLabel, destroyX + destroyW / 2, btnY + (BTN_HEIGHT - font.lineHeight) / 2,
                destroyEnabled ? com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.COLOR_TEXT_NORMAL : TEXT_DIM);

        g.bufferSource().endBatch(RenderType.guiOverlay());

        // Store bounds for mouse handler
        btnRepairX = repairX;
        btnRepairY = btnY;
        btnRepairW = repairW;
        btnDestroyX = destroyX;
        btnDestroyY = btnY;
        btnDestroyW = destroyW;
        buttonsVisible = true;
    }

    // ── Mouse click handler ──

    @SubscribeEvent
    public static void onMouseButtonPre(InputEvent.MouseButton.Pre event) {
        if (!com.wsteam.wandscape.shared.ui.panel.WandscapePanelState.isInspectContext()
                || com.wsteam.wandscape.shared.ui.panel.TaskManagementOverlay.isActive()) {
            buttonsVisible = false;
            return;
        }
        if (!buttonsVisible) return;
        if (com.wsteam.wandscape.shared.ui.panel.WandscapePanelState.isPanelHidden()) return;
        if (event.getAction() != GLFW.GLFW_PRESS) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) return;

        BuildingDebugResponsePacket data = BuildingDebugClientState.getDisplayData();
        if (data == null) return;
        double guiScale = mc.getWindow().getGuiScale();
        double mx = mc.mouseHandler.xpos() / guiScale;
        double my = mc.mouseHandler.ypos() / guiScale;

        // Check undo/repair button (left) — undo for under-construction, repair when damaged
        if (mx >= btnRepairX && mx <= btnRepairX + btnRepairW
                && my >= btnRepairY && my <= btnRepairY + BTN_HEIGHT) {
            if (data.demolishing()) return;
            if (data.underConstruction()) {
                event.setCanceled(true);
                mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                if (mc.player != null) {
                    mc.player.displayClientMessage(
                            Component.literal("§e[建筑] 已撤销「" + data.displayName() + "」的建造"), true);
                }
                PacketDistributor.sendToServer(new BuildingActionPacket(data.buildingId(), "cancel"));
                Log.info(TAG, "[Debug] Button click: cancel on building {}", shortUuid(data.buildingId()));
                return;
            }
            if (data.needsRepair()) {
                event.setCanceled(true);
                mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                if (mc.player != null) {
                    mc.player.displayClientMessage(
                            Component.literal("§a[建筑] 正在维修「" + data.displayName() + "」... 已下发修复任务"), true);
                }
                PacketDistributor.sendToServer(new BuildingActionPacket(data.buildingId(), "repair"));
                Log.info(TAG, "[Debug] Button click: repair on building {}", shortUuid(data.buildingId()));
                return;
            }
        }

        // Check destroy button (right)
        if (mx >= btnDestroyX && mx <= btnDestroyX + btnDestroyW
                && my >= btnDestroyY && my <= btnDestroyY + BTN_HEIGHT) {
            if (data.demolishing()) return;
            event.setCanceled(true);
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        Component.literal("§c[建筑] 正在拆除「" + data.displayName() + "」... 已下发拆除任务"), true);
            }
            PacketDistributor.sendToServer(new BuildingActionPacket(data.buildingId(), "destroy"));
            Log.info(TAG, "[Debug] Button click: destroy on building {}", shortUuid(data.buildingId()));
        }
    }

    // ── Status helpers ──

    private static Component getStatusText(BuildingDebugResponsePacket data) {
        if (data.demolishing()) return I18n.name("gui.wandscape.building_status.demolishing", "Demolishing");
        if (data.underConstruction()) {
            return data.constructionStarted()
                    ? I18n.name("gui.wandscape.building_status.under_construction", "Under Construction")
                    : I18n.name("gui.wandscape.building_status.waiting_materials", "Waiting for Materials");
        }
        return I18n.name("gui.wandscape.building_status.ok", "Operational");
    }

    private static int getStatusColor(BuildingDebugResponsePacket data) {
        if (data.demolishing()) return TEXT_RED;
        if (data.underConstruction()) {
            return data.constructionStarted() ? TEXT_BLUE : TEXT_STAT;
        }
        return TEXT_GREEN;
    }

    // ── Formatting helpers ──

    private static String shortUuid(UUID id) {
        return id.toString().substring(0, 8);
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

    private static void drawCenteredText(GuiGraphics g, Font font, Component text, int x, float y, int color) {
        drawText(g, font, text, x - font.width(text) / 2f, y, color);
    }
}
