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
    public static final int COLOR_BORDER_ACTIVE = 0xFFC8A040; // Gold accent (RTS style)

    public static final int COLOR_TEXT_NORMAL = 0xFFCCCCCC;   // Soft white (reduced from E0E0E0 for eye comfort)
    public static final int COLOR_TEXT_DIM = 0xFF888888;      // Grey
    public static final int COLOR_TEXT_ACTIVE = 0xFFC8A040;   // Gold text
    
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

    // Element icons (12x12)
    public static final ResourceLocation ICON_ELEMENT_EARTH = ResourceLocation.fromNamespaceAndPath(Wandscape.MODID, "textures/gui/icons/element_earth.png");
    public static final ResourceLocation ICON_ELEMENT_WOOD = ResourceLocation.fromNamespaceAndPath(Wandscape.MODID, "textures/gui/icons/element_wood.png");
    public static final ResourceLocation ICON_ELEMENT_WATER = ResourceLocation.fromNamespaceAndPath(Wandscape.MODID, "textures/gui/icons/element_water.png");
    public static final ResourceLocation ICON_ELEMENT_FIRE = ResourceLocation.fromNamespaceAndPath(Wandscape.MODID, "textures/gui/icons/element_fire.png");
    public static final ResourceLocation ICON_ELEMENT_WIND = ResourceLocation.fromNamespaceAndPath(Wandscape.MODID, "textures/gui/icons/element_wind.png");
    public static final ResourceLocation ICON_ELEMENT_METAL = ResourceLocation.fromNamespaceAndPath(Wandscape.MODID, "textures/gui/icons/element_metal.png");
    public static final ResourceLocation ICON_ELEMENT_DARK = ResourceLocation.fromNamespaceAndPath(Wandscape.MODID, "textures/gui/icons/element_dark.png");

    // UI icons (16x16)
    public static final ResourceLocation ICON_TOURIST = ResourceLocation.fromNamespaceAndPath(Wandscape.MODID, "textures/gui/icons/icon_tourist.png");
    public static final ResourceLocation ICON_WARNING = ResourceLocation.fromNamespaceAndPath(Wandscape.MODID, "textures/gui/icons/icon_warning.png");
    public static final ResourceLocation ICON_LOCK = ResourceLocation.fromNamespaceAndPath(Wandscape.MODID, "textures/gui/icons/icon_lock.png");

    private WandscapeTheme() {}

    /**
     * Map element type ID to its icon ResourceLocation.
     */
    public static ResourceLocation elementIcon(String elementId) {
        return switch (elementId) {
            case "earth" -> ICON_ELEMENT_EARTH;
            case "wood" -> ICON_ELEMENT_WOOD;
            case "water" -> ICON_ELEMENT_WATER;
            case "fire" -> ICON_ELEMENT_FIRE;
            case "wind" -> ICON_ELEMENT_WIND;
            case "metal" -> ICON_ELEMENT_METAL;
            case "dark" -> ICON_ELEMENT_DARK;
            default -> ICON_ELEMENT_EARTH;
        };
    }

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
