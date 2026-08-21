package com.wsteam.wandscape.building.scanner.client;

import com.wsteam.wandscape.building.scanner.ScannerBlockEntity;

/**
 * Survival Building Scanner GUI.
 * Inherits the complete 4-tab medieval architecture, 3D Gizmo visual adjuster,
 * preset manager, and feedback system from {@link CreativeScannerScreen},
 * while locking the category to custom and auto-evaluating stats from blocks.
 */
public class ScannerScreen extends CreativeScannerScreen {

    public ScannerScreen(ScannerBlockEntity scanner) {
        super(scanner, true);
    }
}