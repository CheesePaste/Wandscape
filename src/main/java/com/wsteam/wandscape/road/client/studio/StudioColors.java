package com.wsteam.wandscape.road.client.studio;

/**
 * Color palette for the Road Studio overlay panel.
 * Matches the WandscapeImGuiTheme medieval-RTS palette.
 * All values are 0xAARRGGBB for GuiGraphics.fill().
 */
public final class StudioColors {
    private StudioColors() {}

    // ── Panel backgrounds ──
    public static final int PANEL_BG = 0xF0141418;
    public static final int CHILD_BG = 0xBF1C1C24;
    public static final int HEADER_BG_TOP = 0xD9261A38;
    public static final int HEADER_BG_BOTTOM = 0xD90A0616;

    // ── Borders ──
    public static final int BORDER_GOLD = 0x59C7A040;
    public static final int BORDER_GOLD_BRIGHT = 0xE6C7A040;
    public static final int SEPARATOR = 0x4DC7A040;

    // ── Text ──
    public static final int TEXT_WARM = 0xFFEDE9E0;
    public static final int TEXT_MUTED = 0xFF998E80;
    public static final int TEXT_GOLD = 0xFFF2C74D;
    public static final int TEXT_CYAN = 0xFF66BFF2;
    public static final int TEXT_GREEN = 0xFF59D973;
    public static final int TEXT_RED = 0xFFE65959;
    public static final int TEXT_DISABLED = 0xFF665E50;

    // ── Buttons ──
    public static final int BUTTON_NORMAL = 0xD9332B42;
    public static final int BUTTON_HOVER = 0xF252426B;
    public static final int BUTTON_ACTIVE = 0xFF705B8F;
    public static final int BUTTON_SELECTED_BG = 0xFF594780;
    public static final int BUTTON_SELECTED_BORDER = 0xE6F2C74D;
    public static final int BUTTON_UNSELECTED_BG = 0xD9292438;
    public static final int BUTTON_UNSELECTED_BORDER = 0x66665940;
    public static final int BUTTON_GREEN = 0xE6387340;
    public static final int BUTTON_GREEN_HOVER = 0xFF4D9952;
    public static final int BUTTON_RED = 0xD98C2626;
    public static final int BUTTON_RED_HOVER = 0xF2BF3333;
    public static final int BUTTON_BLUE = 0xE6265280;
    public static final int BUTTON_BLUE_ALT = 0xE633476B;

    // ── Inputs ──
    public static final int INPUT_BG = 0xD924242E;
    public static final int INPUT_BORDER = 0x66665940;
    public static final int INPUT_BORDER_FOCUS = 0xCCF2C74D;

    // ── Slider ──
    public static final int SLIDER_TRACK = 0xFF1A1A24;
    public static final int SLIDER_FILL = 0xFFC7A040;
    public static final int SLIDER_THUMB = 0xFFF2C74D;
    public static final int SLIDER_THUMB_HOVER = 0xFFF2D98C;

    // ── Checkbox / Radio ──
    public static final int CHECK_BOX_BG = 0xD924242E;
    public static final int CHECK_MARK = 0xFFE6BA40;
    public static final int RADIO_DOT = 0xFFE6BA40;

    // ── Selection ──
    public static final int LIST_ITEM_HOVER = 0x335C4A7A;
    public static final int LIST_ITEM_SELECTED = 0x66594780;

    // ── Splitter ──
    public static final int SPLITTER_NORMAL = 0x44FFFFFF;
    public static final int SPLITTER_ACTIVE = 0xFFE6AD2A;

    // ── Tab ──
    public static final int TAB_NORMAL = 0xD924212E;
    public static final int TAB_HOVER = 0xF252426B;
    public static final int TAB_ACTIVE = 0xFF3D3054;
    public static final int TAB_BORDER = 0xCCC7A040;

    // ── Scrollbar ──
    public static final int SCROLLBAR_BG = 0x800F0F14;
    public static final int SCROLLBAR_THUMB = 0xA68C7033;
    public static final int SCROLLBAR_THUMB_HOVER = 0xD9C7A040;
}
