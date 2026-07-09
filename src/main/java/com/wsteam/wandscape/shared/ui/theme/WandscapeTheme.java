package com.wsteam.wandscape.shared.ui.theme;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import com.wsteam.wandscape.Wandscape;

/**
 * Wandscape RTS-style UI Theme primitives.
 * All rendering methods bypass bulky vanilla textures in favor of clean,
 * code-driven translucent boxes and slim borders.
 */
public final class WandscapeTheme {

    // ── Palette ──
    public static final int COLOR_BG_MAIN = 0xCC111214;       // 80% opacity dark grey-blue
    public static final int COLOR_BG_HOVER = 0xEE1A1C20;      // Lighter dark on hover
    public static final int COLOR_BORDER_NORMAL = 0xFF3A3E4A; // Dim grey border
    public static final int COLOR_BORDER_ACTIVE = 0xFF78A563; // Pale green accent (RTS style)
    
    public static final int COLOR_TEXT_NORMAL = 0xFFE0E0E0;   // Soft white
    public static final int COLOR_TEXT_DIM = 0xFF888888;      // Grey
    public static final int COLOR_TEXT_ACTIVE = 0xFF78A563;   // Pale green text
    
    public static final int COLOR_COMFORT = 0xFF4CAF50;
    public static final int COLOR_MAGIC = 0xFF42A5F5;
    public static final int COLOR_WONDER = 0xFFC8A040;

    // ── Icons ──
    public static final ResourceLocation ICON_TAB_BUILD = ResourceLocation.fromNamespaceAndPath(Wandscape.MODID, "textures/gui/icons/tab_build.png");
    public static final ResourceLocation ICON_TAB_ROAD = ResourceLocation.fromNamespaceAndPath(Wandscape.MODID, "textures/gui/icons/tab_road.png");
    public static final ResourceLocation ICON_TAB_EDITOR = ResourceLocation.fromNamespaceAndPath(Wandscape.MODID, "textures/gui/icons/tab_editor.png");
    public static final ResourceLocation ICON_TAB_STATS = ResourceLocation.fromNamespaceAndPath(Wandscape.MODID, "textures/gui/icons/tab_stats.png");
    public static final ResourceLocation ICON_COLONY = ResourceLocation.fromNamespaceAndPath(Wandscape.MODID, "textures/gui/icons/icon_colony.png");
    public static final ResourceLocation ICON_COMFORT = ResourceLocation.fromNamespaceAndPath(Wandscape.MODID, "textures/gui/icons/icon_comfort.png");
    public static final ResourceLocation ICON_MAGIC = ResourceLocation.fromNamespaceAndPath(Wandscape.MODID, "textures/gui/icons/icon_magic.png");
    public static final ResourceLocation ICON_WONDER = ResourceLocation.fromNamespaceAndPath(Wandscape.MODID, "textures/gui/icons/icon_wonder.png");

    private WandscapeTheme() {}

    /**
     * Draws a crisp RTS-style box with a translucent background and 1px border.
     * Always uses NO_DEPTH_TEST (guiOverlay) to render on top of everything.
     */
    public static void drawRtsBox(GuiGraphics g, int x, int y, int width, int height, boolean active, boolean hovered) {
        int bgColor = hovered ? COLOR_BG_HOVER : COLOR_BG_MAIN;
        int borderColor = active ? COLOR_BORDER_ACTIVE : COLOR_BORDER_NORMAL;

        // Background
        g.fill(RenderType.guiOverlay(), x, y, x + width, y + height, 0, bgColor);

        // Top border
        g.fill(RenderType.guiOverlay(), x, y, x + width, y + 1, 0, borderColor);
        // Bottom border
        g.fill(RenderType.guiOverlay(), x, y + height - 1, x + width, y + height, 0, borderColor);
        // Left border
        g.fill(RenderType.guiOverlay(), x, y + 1, x + 1, y + height - 1, 0, borderColor);
        // Right border
        g.fill(RenderType.guiOverlay(), x + width - 1, y + 1, x + width, y + height - 1, 0, borderColor);
    }

    /**
     * Draws an icon tinted with the given color.
     */
    public static void drawIcon(GuiGraphics g, ResourceLocation icon, int x, int y, int width, int height, int color) {
        float a = ((color >> 24) & 0xFF) / 255.0F;
        float r = ((color >> 16) & 0xFF) / 255.0F;
        float g_c = ((color >> 8) & 0xFF) / 255.0F;
        float b = (color & 0xFF) / 255.0F;

        g.setColor(r, g_c, b, a);
        // Draw the full icon scaled down to the requested size
        g.blit(icon, x, y, 0, 0, width, height, width, height);
        g.setColor(1.0f, 1.0f, 1.0f, 1.0f); // Reset color
    }
}
