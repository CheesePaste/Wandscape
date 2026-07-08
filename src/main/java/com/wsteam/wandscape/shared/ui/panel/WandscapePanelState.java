package com.wsteam.wandscape.shared.ui.panel;

import java.util.Map;
import java.util.UUID;

import com.wsteam.wandscape.building.editor.BuildingEditorClientState;
import com.wsteam.wandscape.building.editor.BuildingEditorController;
import com.wsteam.wandscape.building.network.BuildingEditorEnterPacket;
import com.wsteam.wandscape.projection.client.BuildingDebugClientState;
import com.wsteam.wandscape.projection.client.ProjectionClientState;
import com.wsteam.wandscape.projection.data.BuildingSlot;
import com.wsteam.wandscape.projection.network.ProjectionEnterPacket;
import com.wsteam.wandscape.projection.network.ProjectionExitPacket;
import com.wsteam.wandscape.road.client.RoadPlacementState;
import com.wsteam.wandscape.shared.data.ElementType;
import com.wsteam.wandscape.shared.network.PanelStateTogglePacket;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
/**
 * Client-side static state holder for the Wandscape comprehensive panel.
 */
public final class WandscapePanelState {

    public enum SubMode { NONE, BUILD_PROJECTION, ROAD_PROJECTION, BUILD_EDITOR, STATS, OVERVIEW }

    /** Build projection phase: BAR = selecting building (UI, no ghost), PLACING = in-world placement (ghost visible). */
    public enum BuildPhase { BAR, PLACING }

    private static volatile boolean panelOpen = false;
    private static volatile boolean cursorLifted = false;
    private static volatile SubMode activeSubMode = SubMode.NONE;
    private static volatile UUID colonyId = null;
    private static volatile int comfort = 0;
    private static volatile int magic = 0;
    private static volatile int wonder = 0;
    private static volatile String colonyName = "";
    private static volatile int colonyLevel = 1;
    private static volatile int colonyExperience = 0;

    // ── Stats tab data (set from StatsSyncPacket) ──

    public record StatsSummary(
            long currentDay,
            int buildingsPaid,
            int buildingsShutdown,
            int buildingsRestarted,
            int touristsArrived,
            int touristsDeparted,
            int avgSatisfaction,
            int comfort,
            int magic,
            int wonder,
            Map<ElementType, Long> totalElementsConsumed,
            int snapshotCount
    ) {
        public static final StatsSummary EMPTY = new StatsSummary(
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, Map.of(), 0);
    }

    private static volatile StatsSummary statsSummary = StatsSummary.EMPTY;

    // ── Interaction area overlay (B key toggle) ──
    private static volatile boolean showBuildingAreas = false;

    public static boolean isShowBuildingAreas() { return showBuildingAreas; }
    public static void toggleBuildingAreas() { showBuildingAreas = !showBuildingAreas; }

    // ── Building selection bar ──
    private static volatile boolean buildingBarOpen = false;
    private static volatile String buildingBarCategory = "All";
    private static volatile String buildingBarSearch = "";
    private static volatile int buildingBarSelectedIndex = -1;
    private static volatile int buildingBarScrollOffset = 0;
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
    public static String getColonyName() { return colonyName; }
    public static int getColonyLevel() { return colonyLevel; }
    public static int getColonyExperience() { return colonyExperience; }

    public static WandscapePanelState.StatsSummary getStatsSummary() { return statsSummary; }
    public static void setStatsSummary(WandscapePanelState.StatsSummary summary) { statsSummary = summary; }

    public static void setColonyStats(UUID colonyId, int comfort, int magic, int wonder,
                                      String name, int level, int experience) {
        WandscapePanelState.colonyId = colonyId;
        WandscapePanelState.comfort = comfort;
        WandscapePanelState.magic = magic;
        WandscapePanelState.wonder = wonder;
        WandscapePanelState.colonyName = name;
        WandscapePanelState.colonyLevel = level;
        WandscapePanelState.colonyExperience = experience;
    }

    public static void openPanel() {
        panelOpen = true;
        showBuildingAreas = false;
        BuildingDebugClientState.setActive(true);
        PacketDistributor.sendToServer(new PanelStateTogglePacket(true));
        // Default to overview mode
        enterSubMode(SubMode.OVERVIEW);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(
                    Component.literal("[Panel] Opened — V: close, G: switch mode"), true);
        }
    }

    public static void closePanel() {
        exitCurrentSubMode();
        // Ensure overview is properly exited on panel close
        if (com.wsteam.wandscape.overview.client.OverviewClientState.isActive()) {
            com.wsteam.wandscape.overview.client.OverviewFlightController.exit();
        }
        if (buildingBarOpen) {
            closeBuildingBar();
        }
        if (cursorLifted) {
            releaseCursor();
        }
        panelOpen = false;
        showBuildingAreas = false;
        BuildingDebugClientState.setActive(false);
        cursorLifted = false;
        activeSubMode = SubMode.NONE;
        buildPhase = BuildPhase.BAR;
        PacketDistributor.sendToServer(new PanelStateTogglePacket(false));
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("[Panel] Closed"), true);
        }
    }

    // ── Cursor helpers (shared by BUILD and ROAD modes) ──

    /** Lift cursor: release mouse for UI interaction (preset selection overlay). */
    public static void liftCursorForUI() {
        if (!cursorLifted) {
            cursorLifted = true;
            Minecraft.getInstance().mouseHandler.releaseMouse();
        }
    }

    /** Release cursor to game: grab mouse for in-world interaction. */
    public static void releaseCursorToGame() {
        if (cursorLifted) {
            cursorLifted = false;
            Minecraft.getInstance().mouseHandler.grabMouse();
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

        // Road projection mode: C toggles BAR ↔ PLACING
        if (activeSubMode == SubMode.ROAD_PROJECTION) {
            if (RoadPlacementState.getRoadPhase() == RoadPlacementState.RoadPhase.BAR) {
                // BAR → PLACING
                RoadPlacementState.enterPlacing();
                releaseCursorToGame();
            } else {
                // PLACING → BAR
                RoadPlacementState.enterBar();
                liftCursorForUI();
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
        buildingBarScrollOffset = 0;
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
        buildingBarScrollOffset = 0;
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
    public static void setBuildingBarCategory(String cat) {
        buildingBarCategory = cat;
        buildingBarScrollOffset = 0;
    }

    public static String getBuildingBarSearch() { return buildingBarSearch; }
    public static void setBuildingBarSearch(String search) {
        buildingBarSearch = search;
        buildingBarScrollOffset = 0;
    }

    public static int getBuildingBarSelectedIndex() { return buildingBarSelectedIndex; }
    public static void setBuildingBarSelectedIndex(int idx) { buildingBarSelectedIndex = idx; }

    public static int getBuildingBarScrollOffset() { return buildingBarScrollOffset; }
    public static void setBuildingBarScrollOffset(int offset) { buildingBarScrollOffset = Math.max(0, offset); }

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
        SubMode prev = activeSubMode;

        // OVERVIEW → BUILD_PROJECTION or ROAD_PROJECTION: keep overview camera active
        if (prev == SubMode.OVERVIEW && (mode == SubMode.BUILD_PROJECTION || mode == SubMode.ROAD_PROJECTION)) {
            activeSubMode = mode;
            switch (mode) {
                case BUILD_PROJECTION -> {
                    buildPhase = BuildPhase.BAR;
                    PacketDistributor.sendToServer(new ProjectionEnterPacket());
                    if (!buildingBarOpen) {
                        openBuildingBar();
                    }
                }
                case ROAD_PROJECTION -> {
                    RoadPlacementState.enterProjection();
                    liftCursorForUI();
                }
            }
            return;
        }

        // Normal: exit current, enter new
        exitCurrentSubMode();
        activeSubMode = mode;
        switch (mode) {
            case BUILD_PROJECTION -> {
                buildPhase = BuildPhase.BAR;
                PacketDistributor.sendToServer(new ProjectionEnterPacket());
            }
            case ROAD_PROJECTION -> {
                RoadPlacementState.enterProjection();
                liftCursorForUI();
            }
            case BUILD_EDITOR -> PacketDistributor.sendToServer(BuildingEditorEnterPacket.createNew());
            case OVERVIEW -> com.wsteam.wandscape.overview.client.OverviewFlightController.enter();
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
                // If entered from overview, go back to pure overview
                if (com.wsteam.wandscape.overview.client.OverviewClientState.isActive()) {
                    activeSubMode = SubMode.OVERVIEW;
                    return;
                }
            }
            case ROAD_PROJECTION -> {
                if (RoadPlacementState.isProjecting()) {
                    RoadPlacementState.exitProjection();
                    releaseCursorToGame();
                }
                // If entered from overview, go back to pure overview
                if (com.wsteam.wandscape.overview.client.OverviewClientState.isActive()) {
                    activeSubMode = SubMode.OVERVIEW;
                    return;
                }
            }
            case BUILD_EDITOR -> {
                if (BuildingEditorClientState.isEditing()) {
                    BuildingEditorController.doExit();
                }
            }
            case OVERVIEW -> {
                com.wsteam.wandscape.overview.client.OverviewFlightController.exit();
            }
        }
    }
}
