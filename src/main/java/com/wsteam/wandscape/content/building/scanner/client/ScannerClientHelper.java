package com.wsteam.wandscape.content.building.scanner.client;

import com.wsteam.wandscape.content.building.scanner.CreativeScannerBlockEntity;
import com.wsteam.wandscape.content.building.scanner.ScannerBlockEntity;
import com.wsteam.wandscape.content.building.scanner.client.gizmo.ScannerGizmoState;
import net.minecraft.client.Minecraft;

public final class ScannerClientHelper {
    private ScannerClientHelper() {}

    public static void openCreativeScanner(CreativeScannerBlockEntity scanner) {
        if (ScannerGizmoState.isActive()) return;
        Minecraft.getInstance().setScreen(new CreativeScannerScreen(scanner));
    }

    public static void openSurvivalScanner(ScannerBlockEntity scanner) {
        if (ScannerGizmoState.isActive()) return;
        Minecraft.getInstance().setScreen(new ScannerScreen(scanner));
    }
}
