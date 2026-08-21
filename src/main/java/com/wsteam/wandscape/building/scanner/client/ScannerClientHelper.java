package com.wsteam.wandscape.building.scanner.client;

import com.wsteam.wandscape.building.scanner.CreativeScannerBlockEntity;
import com.wsteam.wandscape.building.scanner.ScannerBlockEntity;
import net.minecraft.client.Minecraft;

public final class ScannerClientHelper {
    private ScannerClientHelper() {}

    public static void openCreativeScanner(CreativeScannerBlockEntity scanner) {
        if (com.wsteam.wandscape.building.scanner.client.gizmo.ScannerGizmoState.isActive()) return;
        Minecraft.getInstance().setScreen(new CreativeScannerScreen(scanner));
    }

    public static void openSurvivalScanner(ScannerBlockEntity scanner) {
        if (com.wsteam.wandscape.building.scanner.client.gizmo.ScannerGizmoState.isActive()) return;
        Minecraft.getInstance().setScreen(new ScannerScreen(scanner));
    }
}
