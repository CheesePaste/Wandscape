package com.wsteam.wandscape.projection.client;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;
import com.wsteam.wandscape.shared.registry.WandscapeApis;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
/**
 * Standalone debug-inspect mode (G key) — completely independent of
 * soul projection (V key).
 *
 * <p>When active, raycasts from the camera on left-click to find
 * buildings and displays their debug info.
 */
public final class BuildingDebugClientState {

    private static final String TAG = "BuildingDebugClientState";

    private static volatile boolean active = false;

    private BuildingDebugClientState() {}

    public static boolean isActive() {
        return active;
    }

    public static void setActive(boolean v) {
        active = v;
    }
}
