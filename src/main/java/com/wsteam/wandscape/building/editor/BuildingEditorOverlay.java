package com.wsteam.wandscape.building.editor;

import java.util.List;
import java.util.Locale;

import com.wsteam.wandscape.building.data.BlockOffset;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Pure HUD overlay for the building editor right-side panel.
 * Drawn via {@link RenderGuiEvent.Post} — no Screen, no input blocking,
 * no world dimming. The editor remains fully playable in spectator-style flight.
 *
 * <p>All interaction (clicking buttons, selecting fields for text input)
 * is handled by {@link BuildingEditorInputHandler} via raw GLFW polling
 * and hit-testing against the panel layout defined here.
 *
 * <p>Layout constants are public so the input handler can hit-test.
 */
public final class BuildingEditorOverlay {

    // Panel geometry
    public static final int PANEL_W = 195;
    /** Pixels from right edge of window. */
    public static final int MARGIN_RIGHT = 5;
    public static final int MARGIN_TOP = 5;
    /** Height of the header bar. */
    public static final int HEADER_H = 18;
    public static final int PADDING = 5;
    public static final int LINE_H = 14;
    public static final int GAP = 2;

    // Derived — computed each frame from window size
    public static int panelLeft;
    public static int panelTop;
    public static int panelHeight;

    // Colors
    private static final int BG_COLOR = 0xD0101010;
    private static final int BORDER_COLOR = 0xFF888888;
    private static final int HEADER_BG = 0xD02A1A1A;
    private static final int FIELD_BG = 0xD0252525;
    private static final int FIELD_BORDER = 0xFF555555;
    private static final int FIELD_BORDER_FOCUS = 0xFFD4A017;
    private static final int TEXT_PRIMARY = 0xFFFFFFFF;
    private static final int TEXT_DIM = 0xFFAAAAAA;
    private static final int SECTION_COLOR = 0xFF888888;

    // Button colors
    private static final int BTN_EXPORT_BG = 0xD01A4A1A;
    private static final int BTN_HOVER_BG = 0xD03A3A3A;
    private static final int BTN_TEXT = 0xFFFFFFFF;

    private static boolean registered = false;

    private BuildingEditorOverlay() {}

    public static void register() {
        if (registered) return;
        registered = true;
        NeoForge.EVENT_BUS.addListener(RenderGuiEvent.Post.class, BuildingEditorOverlay::onRenderGui);
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Render ──
    // ═══════════════════════════════════════════════════════════════

    static void onRenderGui(RenderGuiEvent.Post event) {
        if (!BuildingEditorClientState.isEditing() || !BuildingEditorClientState.isScreenVisible()) return;

        GuiGraphics g = event.getGuiGraphics();
        DeltaTracker dt = event.getPartialTick();
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();

        // Compute panel position
        panelLeft = screenW - PANEL_W - MARGIN_RIGHT;
        panelTop = MARGIN_TOP;
        panelHeight = Math.min(screenH - 10, 580);

        String focusedField = BuildingEditorClientState.getFocusedField();

        // ── Panel background ──
        g.fill(panelLeft, panelTop, panelLeft + PANEL_W, panelTop + panelHeight, BG_COLOR);
        g.renderOutline(panelLeft, panelTop, PANEL_W, panelHeight, BORDER_COLOR);

        // ── Header ──
        g.fill(panelLeft, panelTop, panelLeft + PANEL_W, panelTop + HEADER_H, HEADER_BG);
        g.drawCenteredString(font, "§lBuilding Editor", panelLeft + PANEL_W / 2, panelTop + 5, TEXT_PRIMARY);

        int y = panelTop + HEADER_H + 4;

        // ── ID ──
        drawField(g, font, y, "ID", BuildingEditorClientState.getBuildingId(), "id".equals(focusedField), 0);
        y += LINE_H + GAP;

        // ── Name ──
        drawField(g, font, y, "Name", BuildingEditorClientState.getDisplayName(), "name".equals(focusedField), 0);
        y += LINE_H + GAP;

        // ── Category ──
        drawField(g, font, y, "Category", BuildingEditorClientState.getCategory(), "category".equals(focusedField), 0);
        y += LINE_H + 4;

        // ── Section: Three Values ──
        drawSectionLabel(g, font, y, "Three Values");
        y += LINE_H;
        int thirdW = (PANEL_W - PADDING * 2 - 4) / 3;
        drawIntField(g, font, y, "Comfort", BuildingEditorClientState.getComfort(), "comfort".equals(focusedField), 0, thirdW);
        drawIntField(g, font, y, "Magic", BuildingEditorClientState.getMagic(), "magic".equals(focusedField), 1, thirdW);
        drawIntField(g, font, y, "Wonder", BuildingEditorClientState.getWonder(), "wonder".equals(focusedField), 2, thirdW);
        y += LINE_H + 4;

        // ── Section: Unlock ──
        drawSectionLabel(g, font, y, "Unlock Req.");
        y += LINE_H;
        drawIntField(g, font, y, "minC", BuildingEditorClientState.getUnlockMinComfort(), "unlockComfort".equals(focusedField), 0, thirdW);
        drawIntField(g, font, y, "minM", BuildingEditorClientState.getUnlockMinMagic(), "unlockMagic".equals(focusedField), 1, thirdW);
        drawIntField(g, font, y, "minW", BuildingEditorClientState.getUnlockMinWonder(), "unlockWonder".equals(focusedField), 2, thirdW);
        y += LINE_H + 4;

        // ── Queue + Interaction ──
        int halfW = (PANEL_W - PADDING * 2 - 4) / 2;
        drawIntField(g, font, y, "Queue cap", BuildingEditorClientState.getQueueCapacity(), "queueCapacity".equals(focusedField), 0, halfW);
        drawIntField(g, font, y, "Interact R", BuildingEditorClientState.getInteractionRadius(), "interactRadius".equals(focusedField), 1, halfW);
        y += LINE_H + 4;

        // ── Maintenance interval ──
        drawIntField(g, font, y, "Maint ticks", BuildingEditorClientState.getMaintenanceIntervalTicks(), "maintInterval".equals(focusedField), 0, PANEL_W - PADDING * 2);
        y += LINE_H + 4;

        // ── Blueprint ID ──
        drawField(g, font, y, "Blueprint", BuildingEditorClientState.getBlueprintId(), "blueprint".equals(focusedField), 0);
        y += LINE_H + 4;

        // ── Status line ──
        y += 2;
        if (BuildingEditorClientState.hasAABB()) {
            BlockOffset min = BuildingEditorClientState.getEditMin();
            BlockOffset max = BuildingEditorClientState.getEditMax();
            g.drawString(font, "§7AABB [" + min.toKey() + "] → [" + max.toKey() + "]",
                    panelLeft + PADDING, y, TEXT_DIM);
            y += 10;
            g.drawString(font, "§7" + BuildingEditorClientState.getPattern().size() + " blocks",
                    panelLeft + PADDING, y, TEXT_DIM);
        } else {
            g.drawString(font, "§7Click world to set AABB", panelLeft + PADDING, y, TEXT_DIM);
        }
        y += 14;

        // ── Buttons ──
        int btnW = (PANEL_W - PADDING * 2 - 8) / 3;
        int btnY = panelTop + panelHeight - 20;
        drawButton(g, font, "Export", panelLeft + PADDING, btnY, btnW, 14,
                hover("exportBtn"), BTN_EXPORT_BG);
        drawButton(g, font, "Preview", panelLeft + PADDING + btnW + 4, btnY, btnW, 14,
                hover("previewBtn"), BTN_HOVER_BG);
        drawButton(g, font, "Exit", panelLeft + PADDING + (btnW + 4) * 2, btnY, btnW, 14,
                hover("exitBtn"), BTN_HOVER_BG);

        // ── JSON Preview overlay ──
        if (BuildingEditorClientState.isShowPreview()) {
            int prevW = panelLeft - 15;
            int prevH = screenH - 40;
            g.fill(5, 20, 5 + prevW, 20 + prevH, 0xC0101010);
            g.renderOutline(5, 20, prevW, prevH, 0xFF888888);
            g.drawString(font, "§eJSON Preview", 10, 24, TEXT_PRIMARY);
            String[] lines = BuildingEditorClientState.getPreviewJson().split("\n");
            int lineY = 36;
            for (String line : lines) {
                if (lineY > 20 + prevH - 10) break;
                g.drawString(font, line, 10, lineY, 0xFFCCCCCC);
                lineY += 9;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Drawing helpers ──
    // ═══════════════════════════════════════════════════════════════

    private static void drawField(GuiGraphics g, Font font, int topY,
                                   String label, String value, boolean focused, int col) {
        int x = panelLeft + PADDING;
        int fullW = PANEL_W - PADDING * 2;
        g.drawString(font, "§7" + label + ":", x, topY, TEXT_DIM);
        int valY = topY + LINE_H;
        int borderCol = focused ? FIELD_BORDER_FOCUS : FIELD_BORDER;
        g.fill(x, valY, x + fullW, valY + LINE_H, FIELD_BG);
        g.renderOutline(x, valY, fullW, LINE_H, borderCol);
        g.drawString(font, value.isEmpty() ? "§8..." : value, x + 2, valY + 3, TEXT_PRIMARY);
    }

    private static void drawIntField(GuiGraphics g, Font font, int topY,
                                      String label, int value, boolean focused, int col, int w) {
        int x = panelLeft + PADDING + col * (w + 2);
        g.drawString(font, "§7" + label + ":", x, topY, TEXT_DIM);
        int valY = topY + LINE_H;
        int borderCol = focused ? FIELD_BORDER_FOCUS : FIELD_BORDER;
        g.fill(x, valY, x + w, valY + LINE_H, FIELD_BG);
        g.renderOutline(x, valY, w, LINE_H, borderCol);
        g.drawString(font, String.valueOf(value), x + 2, valY + 3, TEXT_PRIMARY);
    }

    private static void drawSectionLabel(GuiGraphics g, Font font, int y, String text) {
        g.drawString(font, "§7§l" + text, panelLeft + PADDING, y, SECTION_COLOR);
    }

    private static void drawButton(GuiGraphics g, Font font, String text,
                                    int x, int y, int w, int h, boolean hover, int bgColor) {
        int col = hover ? BTN_HOVER_BG : bgColor;
        g.fill(x, y, x + w, y + h, col);
        g.renderOutline(x, y, w, h, FIELD_BORDER);
        g.drawCenteredString(font, text, x + w / 2, y + 3, BTN_TEXT);
    }

    // ── Hit testing ──

    private static boolean hover(String buttonId) {
        return buttonId.equals(BuildingEditorClientState.getHoveredButton());
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Layout hit-testing support for InputHandler ──
    // ═══════════════════════════════════════════════════════════════

    /** All buttons in the panel (id → bounds in gui-scaled coords). */
    public static HitResult hitTest(int mouseX, int mouseY) {
        if (mouseX < panelLeft || mouseX > panelLeft + PANEL_W) return null;
        if (mouseY < panelTop || mouseY > panelTop + panelHeight) return null;

        int btnY = panelTop + panelHeight - 20;
        int btnW = (PANEL_W - PADDING * 2 - 8) / 3;
        int btnH = 14;

        // Check buttons first
        if (mouseY >= btnY && mouseY <= btnY + btnH) {
            for (int i = 0; i < 3; i++) {
                int bx = panelLeft + PADDING + i * (btnW + 4);
                if (mouseX >= bx && mouseX <= bx + btnW) {
                    return HitResult.button(switch (i) {
                        case 0 -> "exportBtn";
                        case 1 -> "previewBtn";
                        case 2 -> "exitBtn";
                        default -> null;
                    });
                }
            }
        }

        // Check text fields — compute their positions matching drawField/drawIntField
        int y = panelTop + HEADER_H + 4;
        int fieldH = LINE_H;

        // "id" field
        if (hitField(mouseX, mouseY, y + LINE_H, fieldH)) return HitResult.field("id");
        y += LINE_H + GAP;

        // "name" field
        if (hitField(mouseX, mouseY, y + LINE_H, fieldH)) return HitResult.field("name");
        y += LINE_H + GAP;

        // "category" field
        if (hitField(mouseX, mouseY, y + LINE_H, fieldH)) return HitResult.field("category");
        y += LINE_H + 4;

        // Three Values section
        y += LINE_H;
        int thirdW = (PANEL_W - PADDING * 2 - 4) / 3;
        if (hitIntField(mouseX, mouseY, y + LINE_H, fieldH, 0, thirdW)) return HitResult.field("comfort");
        if (hitIntField(mouseX, mouseY, y + LINE_H, fieldH, 1, thirdW)) return HitResult.field("magic");
        if (hitIntField(mouseX, mouseY, y + LINE_H, fieldH, 2, thirdW)) return HitResult.field("wonder");
        y += LINE_H + 4;

        // Unlock section
        y += LINE_H;
        if (hitIntField(mouseX, mouseY, y + LINE_H, fieldH, 0, thirdW)) return HitResult.field("unlockComfort");
        if (hitIntField(mouseX, mouseY, y + LINE_H, fieldH, 1, thirdW)) return HitResult.field("unlockMagic");
        if (hitIntField(mouseX, mouseY, y + LINE_H, fieldH, 2, thirdW)) return HitResult.field("unlockWonder");
        y += LINE_H + 4;

        // Queue + Interact
        int halfW = (PANEL_W - PADDING * 2 - 4) / 2;
        if (hitIntField(mouseX, mouseY, y + LINE_H, fieldH, 0, halfW)) return HitResult.field("queueCapacity");
        if (hitIntField(mouseX, mouseY, y + LINE_H, fieldH, 1, halfW)) return HitResult.field("interactRadius");
        y += LINE_H + 4;

        // Maint interval
        if (hitIntField(mouseX, mouseY, y + LINE_H, fieldH, 0, PANEL_W - PADDING * 2)) return HitResult.field("maintInterval");
        y += LINE_H + 4;

        // Blueprint
        if (hitField(mouseX, mouseY, y + LINE_H, fieldH)) return HitResult.field("blueprint");

        return HitResult.panel(); // Clicked somewhere in panel but not on a field/button
    }

    private static boolean hitField(int mx, int my, int fieldY, int fieldH) {
        int x = panelLeft + PADDING;
        int w = PANEL_W - PADDING * 2;
        return mx >= x && mx <= x + w && my >= fieldY && my <= fieldY + fieldH;
    }

    private static boolean hitIntField(int mx, int my, int fieldY, int fieldH, int col, int w) {
        int x = panelLeft + PADDING + col * (w + 2);
        return mx >= x && mx <= x + w && my >= fieldY && my <= fieldY + fieldH;
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Hit result ──
    // ═══════════════════════════════════════════════════════════════

    public record HitResult(String type, String fieldId, boolean isButton, boolean isField) {
        public HitResult {
            // compact canonical constructor — valid in records
        }
        static HitResult button(String id) { return new HitResult("button", id, true, false); }
        static HitResult field(String id) { return new HitResult("field", id, false, true); }
        static HitResult panel() { return new HitResult("panel", null, false, false); }
    }
}
