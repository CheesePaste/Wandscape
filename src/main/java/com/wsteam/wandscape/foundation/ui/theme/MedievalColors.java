package com.wsteam.wandscape.foundation.ui.theme;

/**
 * Color palette for the medieval magic UI theme.
 * All colors are 0xAARRGGBB ints suitable for {@code GuiGraphics.fill()}.
 */
public final class MedievalColors {

    private MedievalColors() {}

    // ── Parchment layers (darkest edge → light center) ──
    public static final int PARCHMENT_DEEPEST = 0xFF1A0E04;
    public static final int PARCHMENT_DARK    = 0xFF221408;
    public static final int PARCHMENT_BG      = 0xFF2A1A0A;
    public static final int PARCHMENT_MID     = 0xFF3D2A14;
    public static final int PARCHMENT_LIGHT   = 0xFF4D3A20;

    // ── Gold family ──
    public static final int BORDER_GOLD_DARK  = 0xFF9A7A40;
    public static final int BORDER_GOLD       = 0xFFC8A040;
    public static final int ACCENT_GOLD       = 0xFFBB86FC;

    // ── Purple family ──
    public static final int PURPLE_BG         = 0xFF2D1050;
    public static final int PURPLE_BORDER     = 0xFF6B30A0;

    // ── Text colors ──
    public static final int TEXT_WARM_WHITE   = 0xFFFFFFFF;
    public static final int TEXT_MUTED        = 0xFFB0A090;
    public static final int TEXT_DIM          = 0xFF7A6A5A;

    // ── Functional colors ──
    public static final int DANGER_RED        = 0xFF8B0000;
    public static final int SUCCESS_GREEN     = 0xFF2E8B57;
    public static final int INFO_BLUE         = 0xFF4A90D9;
    /** 魔力条填充色（模组魔法主题青蓝）。 */
    public static final int MANA_BLUE         = 0xFF42A5F5;

    // ── Scrollbar ──
    public static final int SCROLLBAR_TRACK   = 0xFF1A0E04;
    public static final int SCROLLBAR_THUMB   = 0xFFB8960F;

    // ── Widget states ──
    public static final int BUTTON_BG_HOVER   = 0xFF3D2060;
    public static final int BUTTON_BG_DISABLED = 0xFF2A2A2A;
    // ── Slider (blue/black, minimal) ──
    public static final int SLIDER_TRACK      = 0xFF0A0A1A;
    public static final int SLIDER_FILL       = 0xFF3355AA;
    public static final int PROGRESS_BG       = 0xFF0A0A1A;
    public static final int PROGRESS_FILL     = 0xFF3355AA;

    // ── Panel chrome ──
    public static final int PANEL_TITLE_BG    = 0xFF2D1050;
    public static final int CORNER_DECORATION = 0xFFBB86FC;
}
