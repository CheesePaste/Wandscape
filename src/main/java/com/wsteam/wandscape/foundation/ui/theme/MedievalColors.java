package com.wsteam.wandscape.foundation.ui.theme;

/**
 * Color palette for Wandscape's Arcane Architecture & Gilded Obsidian Slate UI theme.
 * All colors are 0xAARRGGBB ints suitable for {@code GuiGraphics.fill()}.
 */
public final class MedievalColors {

    private MedievalColors() {}

    // ── Surfaces & Glass (Dark Celestial Obsidian Slate) ──
    public static final int PARCHMENT_DEEPEST = 0xFF0D0F16;
    public static final int PARCHMENT_DARK    = 0xFF121520;
    public static final int PARCHMENT_BG      = 0xFF181C28;
    public static final int PARCHMENT_MID     = 0xFF222738;
    public static final int PARCHMENT_LIGHT   = 0xFF2D344A;

    // ── Imperial Antique Gold family ──
    public static final int BORDER_GOLD_DARK   = 0xFF6E5628;
    public static final int BORDER_GOLD        = 0xFFC8A040;
    public static final int BORDER_GOLD_BRIGHT = 0xFFFFE082;
    public static final int ACCENT_GOLD        = 0xFFFFD54F;

    // ── Celestial Twilight & Header family ──
    public static final int PURPLE_BG          = 0xFF191D2C;
    public static final int PURPLE_BORDER      = 0xFF424D6B;
    public static final int PANEL_TITLE_BG     = 0xFF1C2030;
    public static final int ARCANE_PURPLE      = 0xFFA575F0;

    // ── Text colors (High legibility, soft on the eyes) ──
    public static final int TEXT_WARM_WHITE    = 0xFFF2F4F8;
    public static final int TEXT_MUTED         = 0xFFA0ABBC;
    public static final int TEXT_DIM           = 0xFF657184;
    public static final int TEXT_GOLD          = 0xFFFFDF7A;

    // ── Functional & Elemental colors ──
    public static final int DANGER_RED         = 0xFFE53935;
    public static final int SUCCESS_GREEN      = 0xFF43A047;
    public static final int INFO_BLUE          = 0xFF389BFF;
    /** 魔力条填充色（模组魔法主题青蓝）。 */
    public static final int MANA_BLUE          = 0xFF389BFF;
    public static final int WONDER_GOLD        = 0xFFFFB300;
    public static final int COMFORT_GREEN      = 0xFF4CAF50;

    // ── Cards & Inset surfaces ──
    public static final int CARD_BG            = 0x55161924;
    public static final int CARD_BG_HOVER      = 0x77222838;
    public static final int CARD_BG_SELECTED   = 0x992B344A;
    public static final int CARD_BORDER        = 0x444A5470;
    public static final int CARD_BORDER_HOVER  = 0x99C8A040;
    public static final int CARD_BORDER_SELECTED = 0xFFC8A040;
    public static final int INSET_BG           = 0x66080A10;
    public static final int INSET_BORDER       = 0x663A4256;

    // ── Scrollbar ──
    public static final int SCROLLBAR_TRACK    = 0xAA0A0C12;
    public static final int SCROLLBAR_THUMB    = 0xFFB8933A;
    public static final int SCROLLBAR_THUMB_HOVER = 0xFFFFDF7A;

    // ── Widget states ──
    public static final int BUTTON_BG_HOVER    = 0xF6283044;
    public static final int BUTTON_BG_DISABLED = 0x88141620;

    // ── Slider & Progress ──
    public static final int SLIDER_TRACK       = 0xAA080A12;
    public static final int SLIDER_FILL        = 0xFF389BFF;
    public static final int PROGRESS_BG        = 0xAA080A12;
    public static final int PROGRESS_FILL      = 0xFF389BFF;

    // ── Panel chrome ──
    public static final int CORNER_DECORATION  = 0xFFFFDF7A;
}
