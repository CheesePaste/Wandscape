package com.wsteam.wandscape.imgui;

import imgui.ImGui;
import imgui.ImGuiStyle;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiDir;

/**
 * Modern Medieval-RTS UI Theme for ImGui in Wandscape.
 * Matches MedievalColors and WandscapeTheme design specifications:
 * - Translucent deep slate/violet background
 * - Antique gold accent & borders
 * - Soft warm white typography with muted secondary labels
 * - Distinctive primary/action/danger button states
 */
public final class WandscapeImGuiTheme {

    private WandscapeImGuiTheme() {}

    /**
     * Applies the Wandscape Medieval-RTS palette and dimensions to ImGui.
     */
    public static void apply() {
        ImGuiStyle style = ImGui.getStyle();

        // ── Geometry & Rounding ──
        style.setWindowRounding(8.0f);
        style.setFrameRounding(4.0f);
        style.setPopupRounding(6.0f);
        style.setScrollbarRounding(6.0f);
        style.setGrabRounding(4.0f);
        style.setTabRounding(5.0f);

        style.setWindowPadding(14.0f, 14.0f);
        style.setFramePadding(10.0f, 6.0f);
        style.setItemSpacing(8.0f, 8.0f);
        style.setItemInnerSpacing(6.0f, 6.0f);
        style.setWindowBorderSize(1.0f);
        style.setFrameBorderSize(1.0f);
        style.setPopupBorderSize(1.0f);

        // ── Colors Palette (RGBA) ──

        // Text
        style.setColor(ImGuiCol.Text, 0.93f, 0.91f, 0.88f, 1.00f);                  // Soft warm white
        style.setColor(ImGuiCol.TextDisabled, 0.60f, 0.56f, 0.50f, 1.00f);          // Muted warm grey

        // Backgrounds
        style.setColor(ImGuiCol.WindowBg, 0.08f, 0.08f, 0.10f, 0.94f);              // Deep slate black with slight purple tint
        style.setColor(ImGuiCol.ChildBg, 0.11f, 0.11f, 0.14f, 0.75f);              // Card background
        style.setColor(ImGuiCol.PopupBg, 0.09f, 0.09f, 0.11f, 0.96f);              // Popup / Context menu

        // Borders (Gold theme)
        style.setColor(ImGuiCol.Border, 0.78f, 0.63f, 0.25f, 0.35f);               // Antique gold (semi-transparent)
        style.setColor(ImGuiCol.BorderShadow, 0.00f, 0.00f, 0.00f, 0.00f);

        // Inputs / Frames
        style.setColor(ImGuiCol.FrameBg, 0.14f, 0.14f, 0.18f, 0.85f);               // Dark input box
        style.setColor(ImGuiCol.FrameBgHovered, 0.22f, 0.20f, 0.28f, 0.95f);       // Subtle hover
        style.setColor(ImGuiCol.FrameBgActive, 0.28f, 0.24f, 0.36f, 1.00f);        // Focus

        // Titlebar
        style.setColor(ImGuiCol.TitleBg, 0.12f, 0.09f, 0.17f, 1.00f);               // Medieval deep purple header
        style.setColor(ImGuiCol.TitleBgActive, 0.20f, 0.14f, 0.28f, 1.00f);         // Active header
        style.setColor(ImGuiCol.TitleBgCollapsed, 0.08f, 0.07f, 0.11f, 0.85f);

        // Scrollbar
        style.setColor(ImGuiCol.ScrollbarBg, 0.06f, 0.06f, 0.08f, 0.50f);
        style.setColor(ImGuiCol.ScrollbarGrab, 0.55f, 0.44f, 0.20f, 0.65f);         // Muted gold grab
        style.setColor(ImGuiCol.ScrollbarGrabHovered, 0.78f, 0.63f, 0.25f, 0.85f); // Bright gold hover
        style.setColor(ImGuiCol.ScrollbarGrabActive, 0.95f, 0.78f, 0.30f, 1.00f);

        // Controls (Checkmark, Slider, Drag)
        style.setColor(ImGuiCol.CheckMark, 0.90f, 0.73f, 0.25f, 1.00f);            // Bright gold check
        style.setColor(ImGuiCol.SliderGrab, 0.78f, 0.63f, 0.25f, 0.90f);           // Gold slider handle
        style.setColor(ImGuiCol.SliderGrabActive, 0.95f, 0.78f, 0.30f, 1.00f);

        // Buttons
        style.setColor(ImGuiCol.Button, 0.20f, 0.17f, 0.26f, 0.85f);               // Deep violet button
        style.setColor(ImGuiCol.ButtonHovered, 0.32f, 0.26f, 0.42f, 0.95f);        // Hover violet
        style.setColor(ImGuiCol.ButtonActive, 0.44f, 0.36f, 0.56f, 1.00f);         // Click active

        // Headers (Selectable / CollapsingHeader)
        style.setColor(ImGuiCol.Header, 0.24f, 0.20f, 0.32f, 0.75f);
        style.setColor(ImGuiCol.HeaderHovered, 0.36f, 0.29f, 0.48f, 0.90f);
        style.setColor(ImGuiCol.HeaderActive, 0.48f, 0.38f, 0.62f, 1.00f);

        // Separators
        style.setColor(ImGuiCol.Separator, 0.78f, 0.63f, 0.25f, 0.30f);            // Soft gold divider line
        style.setColor(ImGuiCol.SeparatorHovered, 0.78f, 0.63f, 0.25f, 0.70f);
        style.setColor(ImGuiCol.SeparatorActive, 0.95f, 0.78f, 0.30f, 1.00f);

        // Tabs
        style.setColor(ImGuiCol.Tab, 0.14f, 0.13f, 0.18f, 0.85f);
        style.setColor(ImGuiCol.TabHovered, 0.32f, 0.26f, 0.42f, 0.95f);
        style.setColor(ImGuiCol.TabActive, 0.24f, 0.19f, 0.33f, 1.00f);
        style.setColor(ImGuiCol.TabUnfocused, 0.10f, 0.09f, 0.13f, 0.90f);
        style.setColor(ImGuiCol.TabUnfocusedActive, 0.18f, 0.15f, 0.24f, 1.00f);

        // Misc
        style.setColor(ImGuiCol.PlotLines, 0.78f, 0.63f, 0.25f, 1.00f);
        style.setColor(ImGuiCol.PlotLinesHovered, 0.95f, 0.78f, 0.30f, 1.00f);
        style.setColor(ImGuiCol.PlotHistogram, 0.78f, 0.63f, 0.25f, 1.00f);
        style.setColor(ImGuiCol.PlotHistogramHovered, 0.95f, 0.78f, 0.30f, 1.00f);
        style.setColor(ImGuiCol.TextSelectedBg, 0.45f, 0.32f, 0.60f, 0.50f);
        style.setColor(ImGuiCol.NavHighlight, 0.78f, 0.63f, 0.25f, 1.00f);
    }

    // ── Helper Styling Utilities ──

    public static void textGold(String text) {
        ImGui.textColored(0.95f, 0.78f, 0.30f, 1.00f, text);
    }

    public static void textMuted(String text) {
        ImGui.textColored(0.60f, 0.56f, 0.50f, 1.00f, text);
    }

    public static void textCyan(String text) {
        ImGui.textColored(0.40f, 0.75f, 0.95f, 1.00f, text);
    }

    public static void textGreen(String text) {
        ImGui.textColored(0.35f, 0.85f, 0.45f, 1.00f, text);
    }

    public static void textRed(String text) {
        ImGui.textColored(0.90f, 0.35f, 0.35f, 1.00f, text);
    }

    public static void drawSectionHeader(String icon, String title) {
        ImGui.spacing();
        if (icon != null && !icon.isEmpty()) {
            ImGui.textColored(0.95f, 0.78f, 0.30f, 1.00f, icon + " " + title);
        } else {
            ImGui.textColored(0.95f, 0.78f, 0.30f, 1.00f, title);
        }
        ImGui.separator();
        ImGui.spacing();
    }

    public static void drawTooltip(String tooltipText) {
        if (ImGui.isItemHovered()) {
            ImGui.beginTooltip();
            ImGui.pushTextWrapPos(ImGui.getFontSize() * 20.0f);
            ImGui.textUnformatted(tooltipText);
            ImGui.popTextWrapPos();
            ImGui.endTooltip();
        }
    }
}
