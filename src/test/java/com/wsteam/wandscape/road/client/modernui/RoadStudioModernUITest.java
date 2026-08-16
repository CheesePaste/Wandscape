package com.wsteam.wandscape.road.client.modernui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RoadStudioModernUITest {

    @Test
    @DisplayName("Panel width defaults and clamping")
    void testPanelWidthClamping() {
        RoadStudioModernUI.setPanelWidthDp(430);
        assertEquals(430, RoadStudioModernUI.getPanelWidthDp());

        // Clamp minimum
        RoadStudioModernUI.setPanelWidthDp(100);
        assertTrue(RoadStudioModernUI.getPanelWidthDp() >= 340);

        // Clamp maximum
        RoadStudioModernUI.setPanelWidthDp(1000);
        assertTrue(RoadStudioModernUI.getPanelWidthDp() <= 640);

        // Delta adjust
        RoadStudioModernUI.setPanelWidthDp(400);
        RoadStudioModernUI.adjustPanelWidthDp(50);
        assertEquals(450, RoadStudioModernUI.getPanelWidthDp());
    }

    @Test
    @DisplayName("Keyboard focus state tracking")
    void testKeyboardFocus() {
        RoadStudioModernUI.setKeyboardFocused(false);
        assertFalse(RoadStudioModernUI.isKeyboardFocused());

        RoadStudioModernUI.setKeyboardFocused(true);
        assertTrue(RoadStudioModernUI.isKeyboardFocused());

        RoadStudioModernUI.setKeyboardFocused(false);
        assertFalse(RoadStudioModernUI.isKeyboardFocused());
    }

    @Test
    @DisplayName("RoadStudioFragment ScreenCallback properties")
    void testFragmentScreenCallbackProperties() {
        RoadStudioFragment fragment = new RoadStudioFragment();
        // Screen must NOT have dark background, must NOT blur, and must NOT pause the game!
        assertFalse(fragment.hasDefaultBackground(), "Must have no default background to allow 3D world view");
        assertFalse(fragment.shouldBlurBackground(), "Must not blur 3D world view");
        assertFalse(fragment.isPauseScreen(), "Must not pause game to allow simultaneous camera/world interaction");
    }
}
