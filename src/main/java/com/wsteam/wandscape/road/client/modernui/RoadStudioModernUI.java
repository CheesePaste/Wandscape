package com.wsteam.wandscape.road.client.modernui;

import com.wsteam.wandscape.road.client.SplineEditorClientState;
import com.wsteam.wandscape.shared.log.Log;

import icyllis.modernui.mc.MuiModApi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * ModernUI manager and bridge for the Wandscape Road & Spline Studio.
 * <p>
 * Maintains studio lifecycle, screen opening/closing, panel geometry,
 * hit-testing for 3D world vs UI interaction, and keyboard focus tracking.
 */
public final class RoadStudioModernUI {
    private static final String TAG = "RoadStudioModernUI";

    private static volatile boolean studioOpen = false;
    private static volatile int panelWidthDp = 430;
    private static volatile boolean keyboardFocused = false;
    private static volatile RoadStudioFragment currentFragment = null;

    private static volatile boolean panelBoundsValid = false;
    private static volatile int panelLeft = 0;
    private static volatile int panelTop = 0;
    private static volatile int panelRight = 0;
    private static volatile int panelBottom = 0;

    private static final int MIN_PANEL_WIDTH_DP = 340;
    private static final int MAX_PANEL_WIDTH_DP = 640;

    private RoadStudioModernUI() {}

    /**
     * Checks if the ModernUI Road Studio is currently open.
     */
    public static boolean isOpen() {
        return studioOpen;
    }

    public static void setPanelBounds(int left, int top, int right, int bottom) {
        panelLeft = left;
        panelTop = top;
        panelRight = right;
        panelBottom = bottom;
        panelBoundsValid = true;
    }

    /**
     * Opens the ModernUI Road Studio screen.
     */
    public static void open() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;

        mc.execute(() -> {
            try {
                if (!SplineEditorClientState.isEditing()) {
                    SplineEditorClientState.enterEditMode();
                    return;
                }
                if (currentFragment == null) {
                    currentFragment = new RoadStudioFragment();
                }
                Screen screen = MuiModApi.get().createScreen(currentFragment);
                mc.setScreen(screen);
                studioOpen = true;
                Log.info(TAG, "Opened ModernUI Road Studio screen");
            } catch (Exception e) {
                Log.error(TAG, "Failed to open ModernUI Road Studio screen", e);
            }
        });
    }

    /**
     * Closes the ModernUI Road Studio screen.
     */
    public static void close() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;

        mc.execute(() -> {
            studioOpen = false;
            keyboardFocused = false;
            panelBoundsValid = false;
            if (mc.screen != null) {
                mc.setScreen(null);
            }
            currentFragment = null;
            Log.info(TAG, "Closed ModernUI Road Studio screen");
        });
    }

    /**
     * Hit-tests whether the mouse cursor (in window pixel coordinates) is over the right-side UI panel.
     *
     * @param mouseX Window cursor X coordinate
     * @param mouseY Window cursor Y coordinate
     * @return true if mouse is over UI panel, false if over 3D viewport
     */
    public static boolean isMouseOverUI(double mouseX, double mouseY) {
        if (!studioOpen) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null) return false;

        int windowWidth = mc.getWindow().getWidth();
        int windowHeight = mc.getWindow().getHeight();
        if (windowWidth <= 0 || windowHeight <= 0) return false;

        if (panelBoundsValid && panelRight > panelLeft) {
            return mouseX >= panelLeft && mouseX <= panelRight && mouseY >= panelTop && mouseY <= panelBottom;
        }

        // Fallback: estimate right dock area
        double scale = (double) windowWidth / Math.max(1, mc.getWindow().getGuiScaledWidth());
        double panelWidthPx = panelWidthDp * scale;
        double panelLeftPx = windowWidth - panelWidthPx;

        return mouseX >= panelLeftPx && mouseX <= windowWidth && mouseY >= 0 && mouseY <= windowHeight;
    }

    /**
     * Gets the current panel width in dp.
     */
    public static int getPanelWidthDp() {
        return panelWidthDp;
    }

    /**
     * Sets the panel width in dp, clamped between min and max.
     */
    public static void setPanelWidthDp(int widthDp) {
        panelWidthDp = Math.max(MIN_PANEL_WIDTH_DP, Math.min(MAX_PANEL_WIDTH_DP, widthDp));
    }

    /**
     * Adjusts the panel width by a delta in dp (e.g. from drag splitter).
     */
    public static void adjustPanelWidthDp(int deltaDp) {
        setPanelWidthDp(panelWidthDp + deltaDp);
    }

    /**
     * Checks whether an edit box in ModernUI currently owns keyboard focus.
     */
    public static boolean isKeyboardFocused() {
        return keyboardFocused;
    }

    /**
     * Sets keyboard focus state.
     */
    public static void setKeyboardFocused(boolean focused) {
        keyboardFocused = focused;
    }

    /**
     * Notifies the current studio fragment to refresh its UI state (e.g. after point selection change).
     */
    public static void refreshStudio() {
        if (currentFragment != null) {
            currentFragment.requestStateRefresh();
        }
    }
}
