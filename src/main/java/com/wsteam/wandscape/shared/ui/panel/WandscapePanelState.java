package com.wsteam.wandscape.shared.ui.panel;

import com.wsteam.wandscape.building.editor.BuildingEditorClientState;
import com.wsteam.wandscape.building.editor.BuildingEditorController;
import com.wsteam.wandscape.building.network.BuildingEditorEnterPacket;
import com.wsteam.wandscape.projection.client.ProjectionClientState;
import com.wsteam.wandscape.projection.data.BuildingSlot;
import com.wsteam.wandscape.projection.network.ProjectionEnterPacket;
import com.wsteam.wandscape.projection.network.ProjectionExitPacket;
import com.wsteam.wandscape.road.client.RoadProjectionClientState;
import com.wsteam.wandscape.road.network.RoadEditorTogglePacket;
import com.wsteam.wandscape.shared.network.PanelStateTogglePacket;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.UUID;

/**
 * Client-side static state holder for the Wandscape comprehensive panel.
 */
public final class WandscapePanelState {

    public enum SubMode { NONE, BUILD_PROJECTION, ROAD_PROJECTION, BUILD_EDITOR }

    /** Build projection phase: BAR = selecting building (UI, no ghost), PLACING = in-world placement (ghost visible). */
    public enum BuildPhase { BAR, PLACING }

    private static volatile boolean panelOpen = false;
    private static volatile boolean cursorLifted = false;
    private static volatile SubMode activeSubMode = SubMode.NONE;
    private static volatile UUID colonyId = null;
    private static volatile int comfort = 0;
    private static volatile int magic = 0;
    private static volatile int wonder = 0;

    // ── Building selection bar ──
    private static volatile boolean buildingBarOpen = false;
    private static volatile String buildingBarCategory = "All";
    private static volatile String buildingBarSearch = "";
    private static volatile int buildingBarSelectedIndex = -1;
    private static volatile BuildPhase buildPhase = BuildPhase.BAR;
    private static volatile long lastClickTime = 0;
    private static volatile int lastClickIndex = -1;
    private static final long DOUBLE_CLICK_MS = 400;

    private WandscapePanelState() {}

    public static boolean isPanelOpen() { return panelOpen; }
    public static boolean isCursorLifted() { return cursorLifted; }
    public static SubMode getActiveSubMode() { return activeSubMode; }
    public static UUID getColonyId() { return colonyId; }
    public static int getComfort() { return comfort; }
    public static int getMagic() { return magic; }
    public static int getWonder() { return wonder; }

    public static void setColonyStats(UUID colonyId, int comfort, int magic, int wonder) {
        WandscapePanelState.colonyId = colonyId;
        WandscapePanelState.comfort = comfort;
        WandscapePanelState.magic = magic;
        WandscapePanelState.wonder = wonder;
    }

    public static void openPanel() {
        panelOpen = true;
        PacketDistributor.sendToServer(new PanelStateTogglePacket(true));
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(
                    Component.literal("[Panel] Opened — V: close"), true);
        }
    }

    public static void closePanel() {
        exitCurrentSubMode();
        if (buildingBarOpen) {
            closeBuildingBar();
        }
        if (cursorLifted) {
            releaseCursor();
        }
        panelOpen = false;
        cursorLifted = false;
        activeSubMode = SubMode.NONE;
        buildPhase = BuildPhase.BAR;
        PacketDistributor.sendToServer(new PanelStateTogglePacket(false));
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("[Panel] Closed"), true);
        }
    }

    public static void toggleCursor() {
        if (!panelOpen) return;

        // Build projection mode: C toggles the building selection bar
        if (activeSubMode == SubMode.BUILD_PROJECTION) {
            if (buildingBarOpen) {
                closeBuildingBar();
            } else {
                openBuildingBar();
            }
            return;
        }

        cursorLifted = !cursorLifted;
        Minecraft mc = Minecraft.getInstance();
        if (cursorLifted) {
            mc.mouseHandler.releaseMouse();
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        Component.literal("[Panel] Cursor lifted — click tabs to switch mode"), true);
            }
        } else {
            mc.mouseHandler.grabMouse();
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        Component.literal("[Panel] Cursor released to game"), true);
            }
        }
    }

    // ── Building selection bar ──

    public static boolean isBuildingBarOpen() { return buildingBarOpen; }
    public static BuildPhase getBuildPhase() { return buildPhase; }

    public static void openBuildingBar() {
        buildingBarOpen = true;
        buildingBarCategory = "All";
        buildingBarSearch = "";
        buildingBarSelectedIndex = ProjectionClientState.getSelectedSlotIndex();
        lastClickTime = 0;
        lastClickIndex = -1;
        buildPhase = BuildPhase.BAR;
        // Clear ghost — no preview while selecting
        ProjectionClientState.setGhostPos(null);
        if (!cursorLifted) {
            cursorLifted = true;
            Minecraft.getInstance().mouseHandler.releaseMouse();
        }
    }

    public static void closeBuildingBar() {
        buildingBarOpen = false;
        buildingBarCategory = "All";
        buildingBarSearch = "";
        buildingBarSelectedIndex = -1;
        lastClickTime = 0;
        lastClickIndex = -1;
        if (cursorLifted) {
            cursorLifted = false;
            Minecraft.getInstance().mouseHandler.grabMouse();
        }
    }

    /** Double-click: enter PLACING phase (bar closed, cursor in game, ghost visible). */
    public static void enterPlacingPhase() {
        closeBuildingBar();
        buildPhase = BuildPhase.PLACING;
    }

    /** ESC from PLACING phase: return to building selection bar. */
    public static void returnToBar() {
        buildPhase = BuildPhase.BAR;
        openBuildingBar();
    }

    public static String getBuildingBarCategory() { return buildingBarCategory; }
    public static void setBuildingBarCategory(String cat) { buildingBarCategory = cat; }

    public static String getBuildingBarSearch() { return buildingBarSearch; }
    public static void setBuildingBarSearch(String search) { buildingBarSearch = search; }

    public static int getBuildingBarSelectedIndex() { return buildingBarSelectedIndex; }
    public static void setBuildingBarSelectedIndex(int idx) { buildingBarSelectedIndex = idx; }

    /** @return true if double-click (selects building, enters PLACING phase). */
    public static boolean handleBuildingSlotClick(int index) {
        long now = System.currentTimeMillis();
        if (index == lastClickIndex && (now - lastClickTime) < DOUBLE_CLICK_MS) {
            lastClickTime = 0;
            lastClickIndex = -1;
            return true;
        }
        lastClickTime = now;
        lastClickIndex = index;
        buildingBarSelectedIndex = index;
        return false;
    }

    private static void releaseCursor() {
        Minecraft.getInstance().mouseHandler.releaseMouse();
    }

    // ── Sub-mode ──

    public static void setSubMode(SubMode mode) {
        activeSubMode = mode;
    }

    public static void enterSubMode(SubMode mode) {
        exitCurrentSubMode();
        activeSubMode = mode;
        switch (mode) {
            case BUILD_PROJECTION -> {
                buildPhase = BuildPhase.BAR;
                PacketDistributor.sendToServer(new ProjectionEnterPacket());
                // Bar opens reactively when server grants projection
            }
            case ROAD_PROJECTION -> {
                RoadProjectionClientState.setExpectingSync(true);
                PacketDistributor.sendToServer(new RoadEditorTogglePacket());
            }
            case BUILD_EDITOR -> PacketDistributor.sendToServer(BuildingEditorEnterPacket.createNew());
        }
    }

    public static void exitCurrentSubMode() {
        switch (activeSubMode) {
            case BUILD_PROJECTION -> {
                if (buildingBarOpen) {
                    closeBuildingBar();
                }
                buildPhase = BuildPhase.BAR;
                if (ProjectionClientState.isProjecting()) {
                    PacketDistributor.sendToServer(new ProjectionExitPacket());
                    ProjectionClientState.exitProjection();
                }
            }
            case ROAD_PROJECTION -> {
                if (RoadProjectionClientState.isProjecting()) {
                    RoadProjectionClientState.exitProjection();
                    PacketDistributor.sendToServer(new RoadEditorTogglePacket());
                }
            }
            case BUILD_EDITOR -> {
                if (BuildingEditorClientState.isEditing()) {
                    BuildingEditorController.doExit();
                }
            }
        }
    }
}
