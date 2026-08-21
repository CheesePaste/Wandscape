package com.wsteam.wandscape.building.scanner.client.gizmo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScannerGizmoStateTest {

    @Test
    void testDimensionsAndVolume() {
        ScannerGizmoState.setMin(-2, 0, -3);
        ScannerGizmoState.setMax(5, 4, 2);

        assertEquals(8, ScannerGizmoState.getWidth());
        assertEquals(5, ScannerGizmoState.getHeight());
        assertEquals(6, ScannerGizmoState.getDepth());
        assertEquals(240L, ScannerGizmoState.getVolume());
    }

    @Test
    void testAnchorToggle() {
        ScannerGizmoState.setSelectedAnchor(ScannerGizmoState.Anchor.MIN);
        assertEquals(ScannerGizmoState.Anchor.MIN, ScannerGizmoState.getSelectedAnchor());

        ScannerGizmoState.toggleAnchor();
        assertEquals(ScannerGizmoState.Anchor.MAX, ScannerGizmoState.getSelectedAnchor());

        ScannerGizmoState.toggleAnchor();
        assertEquals(ScannerGizmoState.Anchor.MIN, ScannerGizmoState.getSelectedAnchor());
    }

    @Test
    void testAdjustMinMax() {
        ScannerGizmoState.setMin(0, 0, 0);
        ScannerGizmoState.setMax(10, 10, 10);

        ScannerGizmoState.adjustMin(1, 2, 3);
        assertEquals(1, ScannerGizmoState.getCurrentMin().x());
        assertEquals(2, ScannerGizmoState.getCurrentMin().y());
        assertEquals(3, ScannerGizmoState.getCurrentMin().z());

        ScannerGizmoState.adjustMax(-1, -2, -3);
        assertEquals(9, ScannerGizmoState.getCurrentMax().x());
        assertEquals(8, ScannerGizmoState.getCurrentMax().y());
        assertEquals(7, ScannerGizmoState.getCurrentMax().z());
    }

    @Test
    void testToastMessage() {
        ScannerGizmoState.showToast("Test Toast", 0xFF55FF55);
        assertEquals("Test Toast", ScannerGizmoState.getToastMessage());
        assertEquals(0xFF55FF55, ScannerGizmoState.getToastColor());
    }
}