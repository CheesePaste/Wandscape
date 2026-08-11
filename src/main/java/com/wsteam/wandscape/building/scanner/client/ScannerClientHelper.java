package com.wsteam.wandscape.building.scanner.client;

import com.wsteam.wandscape.building.scanner.CreativeScannerBlockEntity;
import com.wsteam.wandscape.building.scanner.ScannerBlockEntity;
import net.minecraft.client.Minecraft;

public final class ScannerClientHelper {
    private ScannerClientHelper() {}

    public static void openCreativeScanner(CreativeScannerBlockEntity scanner) {
        Minecraft.getInstance().setScreen(new CreativeScannerScreen(scanner));
    }

    public static void openSurvivalScanner(ScannerBlockEntity scanner) {
        Minecraft.getInstance().setScreen(new ScannerScreen(scanner));
    }
}
