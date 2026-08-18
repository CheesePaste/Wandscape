package com.wsteam.wandscape.shared.ui.panel;

import java.util.List;
import java.util.UUID;

import com.wsteam.wandscape.projection.client.BuildingDebugClientState;
import com.wsteam.wandscape.projection.client.ProjectionClientState;
import com.wsteam.wandscape.projection.data.BuildingSlot;
import com.wsteam.wandscape.projection.network.ProjectionEnterPacket;
import com.wsteam.wandscape.projection.network.ProjectionExitPacket;
import com.wsteam.wandscape.road.client.RoadPlacementState;
import com.wsteam.wandscape.shared.network.PanelStateTogglePacket;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;
/**
 * Client-side static state holder for the Wandscape comprehensive panel.
 */
public final class WandscapePanelState {

    public enum SubMode { NONE, BUILD_PROJECTION, ROAD_PROJECTION, STATS, OVERVIEW }

    /** Build projection phase: BAR = selecting building (UI, no ghost), PLACING = in-world placement (ghost visible). */
    public enum BuildPhase { BAR, PLACING }

    private static volatile boolean panelOpen = false;
    private static volatile boolean panelHidden = false;
    private static volatile boolean cursorLifted = false;
    private static volatile SubMode activeSubMode = SubMode.NONE;
    private static volatile UUID colonyId = null;
    private static volatile int comfort = 0;
    private static volatile int magic = 0;
    private static volatile int wonder = 0;
    private static volatile String colonyName = "";
    private static volatile int colonyLevel = 1;
    private static volatile int colonyExperience = 0;

    // ── HUD fields (synced from ColonyStatsSyncPacket) ──
    private static volatile int touristCount = 0;
    private static volatile int overnightStayerCount = 0;
    private static volatile int shutdownCount = 0;
    private static volatile int npcIdleCount = 0;
    private static volatile int npcTotalCount = 0;
    private static volatile int earthAmount = 0;
    private static volatile int woodAmount = 0;
    private static volatile int waterAmount = 0;
    private static volatile int fireAmount = 0;
    private static volatile int windAmount = 0;
    private static volatile int metalAmount = 0;
    private static volatile int darkAmount = 0;
    private static volatile List<String> shutdownBuildingNames = List.of();
    private static volatile List<UUID> shutdownBuildingIds = List.of();
    private static volatile int brokenCount = 0;
    private static volatile List<UUID> brokenBuildingIds = List.of();
    private static volatile List<String> brokenBuildingNames = List.of();
    private static volatile int underConstructionCount = 0;
    private static volatile List<UUID> underConstructionBuildingIds = List.of();
    private static volatile List<String> underConstructionBuildingNames = List.of();
    private static volatile List<Boolean> underConstructionStarted = List.of();

    // ── Sidebar warning overlay toggle ──
    private static volatile boolean warningOverlayActive = false;

    // ── Stats tab data (set from StatsSyncPacket) ──

    public record StatsSummary(
            long currentDay,
            int touristsArrived,
            int touristsDeparted,
            int avgComfortRatio,
            int avgMagicRatio,
            int avgWonderRatio,
            int comfort,
            int magic,
            int wonder,
            int snapshotCount
    ) {
        public static final StatsSummary EMPTY = new StatsSummary(
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    private static volatile StatsSummary statsSummary = StatsSummary.EMPTY;

    // ── Interaction area overlay (B key toggle) ──
    private static volatile boolean showBuildingAreas = false;

    public static boolean isShowBuildingAreas() { return showBuildingAreas; }
    public static void toggleBuildingAreas() { showBuildingAreas = !showBuildingAreas; }

    // ── Building selection bar ──
    private static volatile boolean buildingBarOpen = false;
    private static volatile boolean buildingBarSearchFocused = false;
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
    /** 面板是否从视图中隐藏：F4 专用隐藏键（panelHidden）或原版 F1 隐藏全部 GUI（options.hideGui）。
     *  隐藏时视觉不渲染、输入穿透，面板仍处于打开态，可恢复。 */
    public static boolean isPanelHidden() {
        if (panelHidden) return true;
        Minecraft mc = Minecraft.getInstance();
        return mc != null && mc.options != null && mc.options.hideGui;
    }
    public static void setPanelHidden(boolean hidden) { panelHidden = hidden; }
    /** 真实光标意图：受控（false，游戏层）或抬起（true，UI 层）。与面板开关解耦。 */
    public static boolean isCursorLifted() { return cursorLifted; }
    public static SubMode getActiveSubMode() { return activeSubMode; }
    public static UUID getColonyId() { return colonyId; }
    public static int getComfort() { return comfort; }
    public static int getMagic() { return magic; }
    public static int getWonder() { return wonder; }
    public static String getColonyName() { return colonyName; }
    public static int getColonyLevel() { return colonyLevel; }
    public static int getColonyExperience() { return colonyExperience; }

    // ── HUD field getters ──
    public static int getTouristCount() { return touristCount; }
    public static int getOvernightStayerCount() { return overnightStayerCount; }
    public static int getShutdownCount() { return shutdownCount; }
    public static int getNpcIdleCount() { return npcIdleCount; }
    public static int getNpcTotalCount() { return npcTotalCount; }
    public static int getEarthAmount() { return earthAmount; }
    public static int getWoodAmount() { return woodAmount; }
    public static int getWaterAmount() { return waterAmount; }
    public static int getFireAmount() { return fireAmount; }
    public static int getWindAmount() { return windAmount; }
    public static int getMetalAmount() { return metalAmount; }
    public static int getDarkAmount() { return darkAmount; }
    public static List<String> getShutdownBuildingNames() { return shutdownBuildingNames; }
    public static List<UUID> getShutdownBuildingIds() { return shutdownBuildingIds; }

    // ── Anomaly fields ──
    public static int getBrokenCount() { return brokenCount; }
    public static List<UUID> getBrokenBuildingIds() { return brokenBuildingIds; }
    public static List<String> getBrokenBuildingNames() { return brokenBuildingNames; }

    /** Buildings still under construction (never completed) — not anomalies. */
    public static int getUnderConstructionCount() { return underConstructionCount; }
    public static List<UUID> getUnderConstructionBuildingIds() { return underConstructionBuildingIds; }
    public static List<String> getUnderConstructionBuildingNames() { return underConstructionBuildingNames; }
    public static List<Boolean> getUnderConstructionStarted() { return underConstructionStarted; }

    /** Total anomalies across all types (shutdown + broken). */
    public static int getTotalAnomalyCount() { return shutdownCount + brokenCount; }

    // ── Warning overlay ──
    public static boolean isWarningOverlayActive() { return warningOverlayActive; }
    public static void toggleWarningOverlay() { warningOverlayActive = !warningOverlayActive; }

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

    public static void setColonyStats(UUID colonyId, int comfort, int magic, int wonder,
                                      String name, int level, int experience,
                                      int touristCount, int overnightStayerCount, int shutdownCount,
                                      int npcIdleCount, int npcTotalCount,
                                      int earth, int wood, int water, int fire, int wind,
                                      int metal, int dark,
                                      List<String> shutdownNames,
                                      List<UUID> shutdownIds,
                                      int brokenCount,
                                      List<UUID> brokenIds,
                                      List<String> brokenNames,
                                      int underConstructionCount,
                                      List<UUID> underConstructionIds,
                                      List<String> underConstructionNames,
                                      List<Boolean> underConstructionStarted) {
        setColonyStats(colonyId, comfort, magic, wonder, name, level, experience);
        WandscapePanelState.touristCount = touristCount;
        WandscapePanelState.overnightStayerCount = overnightStayerCount;
        WandscapePanelState.shutdownCount = shutdownCount;
        WandscapePanelState.npcIdleCount = npcIdleCount;
        WandscapePanelState.npcTotalCount = npcTotalCount;
        WandscapePanelState.earthAmount = earth;
        WandscapePanelState.woodAmount = wood;
        WandscapePanelState.waterAmount = water;
        WandscapePanelState.fireAmount = fire;
        WandscapePanelState.windAmount = wind;
        WandscapePanelState.metalAmount = metal;
        WandscapePanelState.darkAmount = dark;
        WandscapePanelState.shutdownBuildingNames = shutdownNames != null ? shutdownNames : List.of();
        WandscapePanelState.shutdownBuildingIds = shutdownIds != null ? shutdownIds : List.of();
        WandscapePanelState.brokenCount = brokenCount;
        WandscapePanelState.brokenBuildingIds = brokenIds != null ? brokenIds : List.of();
        WandscapePanelState.brokenBuildingNames = brokenNames != null ? brokenNames : List.of();
        WandscapePanelState.underConstructionCount = underConstructionCount;
        WandscapePanelState.underConstructionBuildingIds =
                underConstructionIds != null ? underConstructionIds : List.of();
        WandscapePanelState.underConstructionBuildingNames =
                underConstructionNames != null ? underConstructionNames : List.of();
        WandscapePanelState.underConstructionStarted =
                underConstructionStarted != null ? underConstructionStarted : List.of();
    }

    private static boolean panelEverOpened = false;

    public static boolean isPanelEverOpened() {
        return panelEverOpened;
    }

    public static void openPanel() {
        panelOpen = true;
        // 常态 = 游戏层（抓取鼠标/准心/右键交互），不再默认抬光标
        cursorLifted = false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.mouseHandler != null) {
            mc.mouseHandler.grabMouse();
        }
        showBuildingAreas = false;
        BuildingDebugClientState.setActive(true);
        PacketDistributor.sendToServer(new PanelStateTogglePacket(true));
        // Default to overview mode
        enterSubMode(SubMode.OVERVIEW);

        if (!panelEverOpened) {
            panelEverOpened = true;
            WandscapePanelController.openPanelHelpDocument();
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
        panelOpen = false;
        panelHidden = false;
        showBuildingAreas = false;
        BuildingDebugClientState.setActive(false);
        cursorLifted = false;
        activeSubMode = SubMode.NONE;
        buildPhase = BuildPhase.BAR;
        warningOverlayActive = false;
        PacketDistributor.sendToServer(new PanelStateTogglePacket(false));
        // Panel closed → the per-tick reconciler no longer runs, so return the
        // cursor to gameplay (grabbed) directly here.
        grabMouseForGame();
    }

    /**
     * Hard-reset all panel + related client UI state. Called on client disconnect so state
     * from the previous world does not leak into the next one. Unlike {@link #closePanel()},
     * performs no network sends and no cursor/mouse changes.
     */
    public static void reset() {
        com.wsteam.wandscape.overview.client.OverviewFlightController.exit();
        // 清空空中视角相机缓存（exitOverview 是 suspend 语义、保留缓存），
        // 防止上一世界的相机位置泄漏到下一世界
        com.wsteam.wandscape.overview.client.OverviewClientState.hardReset();
        if (ProjectionClientState.isProjecting()) {
            ProjectionClientState.exitProjection();
        }
        if (RoadPlacementState.isProjecting()) {
            RoadPlacementState.exitProjection();
        }
        if (com.wsteam.wandscape.road.client.SplineEditorClientState.isEditing()) {
            com.wsteam.wandscape.road.client.SplineEditorClientState.exitEditMode();
        }
        BuildingDebugClientState.setActive(false);

        panelOpen = false;
        panelHidden = false;
        cursorLifted = false;
        activeSubMode = SubMode.NONE;
        colonyId = null;
        comfort = 0;
        magic = 0;
        wonder = 0;
        colonyName = "";
        colonyLevel = 1;
        colonyExperience = 0;
        touristCount = 0;
        overnightStayerCount = 0;
        shutdownCount = 0;
        npcIdleCount = 0;
        npcTotalCount = 0;
        earthAmount = 0;
        woodAmount = 0;
        waterAmount = 0;
        fireAmount = 0;
        windAmount = 0;
        metalAmount = 0;
        darkAmount = 0;
        shutdownBuildingNames = List.of();
        shutdownBuildingIds = List.of();
        brokenCount = 0;
        brokenBuildingIds = List.of();
        brokenBuildingNames = List.of();
        underConstructionCount = 0;
        underConstructionBuildingIds = List.of();
        underConstructionBuildingNames = List.of();
        underConstructionStarted = List.of();
        warningOverlayActive = false;
        statsSummary = StatsSummary.EMPTY;
        showBuildingAreas = false;
        buildingBarOpen = false;
        buildingBarSearchFocused = false;
        buildingBarCategory = "All";
        buildingBarSearch = "";
        buildingBarSelectedIndex = -1;
        buildingBarScrollOffset = 0;
        buildPhase = BuildPhase.BAR;
        lastClickTime = 0;
        lastClickIndex = -1;
    }

    // ── Cursor helpers (shared by BUILD and ROAD modes) ──
    // These only set the intent flag `cursorLifted`. The OS cursor (grab/release +
    // position restore) is applied solely by WandscapePanelController.onClientTickPost,
    // so sub-mode transitions never race by grabbing then immediately releasing.

    /** Lift cursor: show/free it for UI interaction. */
    public static void liftCursorForUI() {
        cursorLifted = true;
    }

    /** Release cursor to game: hide/lock it for in-world interaction. */
    public static void releaseCursorToGame() {
        cursorLifted = false;
    }

    /**
     * 状态迁移时重算光标意图。常态（OVERVIEW/NONE）与 STATS（旧模式纯覆盖层）保持游戏层；
     * BUILD/ROAD（新模式）抬起光标。手动 Tab 翻转不在此覆盖，仅在迁移时调用。
     */
    public static void syncCursorToState() {
        if (!panelOpen) {
            cursorLifted = false;
            return;
        }
        switch (activeSubMode) {
            case OVERVIEW, NONE, STATS -> cursorLifted = false;
            case BUILD_PROJECTION, ROAD_PROJECTION -> cursorLifted = true;
        }
    }

    // ── Building selection bar ──

    public static boolean isBuildingBarOpen() { return buildingBarOpen; }
    public static BuildPhase getBuildPhase() { return buildPhase; }

    /** Search box only accepts keyboard input once clicked/activated. */
    public static boolean isBuildingBarSearchFocused() { return buildingBarSearchFocused; }
    public static void setBuildingBarSearchFocused(boolean focused) { buildingBarSearchFocused = focused; }

    public static void openBuildingBar() {
        buildingBarOpen = true;
        buildingBarSearchFocused = false;
        // Preserve category/search/scroll from last open (selection cache). Resync the
        // highlighted slot from the persisted projection selection.
        buildingBarSelectedIndex = ProjectionClientState.getSelectedSlotIndex();
        lastClickTime = 0;
        lastClickIndex = -1;
        buildPhase = BuildPhase.BAR;
        // Keep ghost/pinned so toggling the bar does not discard a placement in progress.
        cursorLifted = true;
    }

    public static void closeBuildingBar() {
        buildingBarOpen = false;
        buildingBarSearchFocused = false;
        // Preserve category/search/scroll (selection cache). selectedIndex resyncs on reopen.
        buildingBarSelectedIndex = -1;
        lastClickTime = 0;
        lastClickIndex = -1;
        // BUILD 是新模式（自由光标）：关 bar 不放下光标，光标意图由 syncCursorToState 在
        // 子模式迁移时决定（退出 BUILD → OVERVIEW 常态 → 抓取）。
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

    private static void grabMouseForGame() {
        Minecraft.getInstance().mouseHandler.grabMouse();
    }

    // ── Sub-mode ──

    public static void setSubMode(SubMode mode) {
        activeSubMode = mode;
    }

    public static void enterSubMode(SubMode mode) {
        SubMode prev = activeSubMode;

        // OVERVIEW → BUILD_PROJECTION / ROAD_PROJECTION / STATS: keep overview camera active
        // (STATS is an overlay tab — closing it must return to the overview camera, not kill it)
        if (prev == SubMode.OVERVIEW && (mode == SubMode.BUILD_PROJECTION || mode == SubMode.ROAD_PROJECTION
                || mode == SubMode.STATS)) {
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
                    com.wsteam.wandscape.road.client.SplineEditorClientState.enterEditMode();
                    com.wsteam.wandscape.road.client.studio.RoadStudioOverlay.open();
                    liftCursorForUI();
                }
            }
            syncCursorToState();
            return;
        }

        // Normal: exit current, enter new
        exitCurrentSubMode();
        activeSubMode = mode;
        switch (mode) {
            case BUILD_PROJECTION -> {
                buildPhase = BuildPhase.BAR;
                PacketDistributor.sendToServer(new ProjectionEnterPacket());
                if (!buildingBarOpen) openBuildingBar();
            }
            case ROAD_PROJECTION -> {
                RoadPlacementState.enterProjection();
                com.wsteam.wandscape.road.client.SplineEditorClientState.enterEditMode();
                com.wsteam.wandscape.road.client.studio.RoadStudioOverlay.open();
                liftCursorForUI();
            }
            case OVERVIEW -> com.wsteam.wandscape.overview.client.OverviewFlightController.enter();
        }
        syncCursorToState();
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
                    ProjectionClientState.suspendProjection();
                }
                // If entered from overview, go back to pure overview
                if (com.wsteam.wandscape.overview.client.OverviewClientState.isActive()) {
                    activeSubMode = SubMode.OVERVIEW;
                    syncCursorToState();
                    return;
                }
            }
            case ROAD_PROJECTION -> {
                // Closing the ROAD mode / panel leaves the embedded spline editor.
                if (com.wsteam.wandscape.road.client.SplineEditorClientState.isEditing()) {
                    com.wsteam.wandscape.road.client.SplineEditorClientState.exitEditMode();
                }
                if (RoadPlacementState.isProjecting()) {
                    RoadPlacementState.suspendProjection();
                    com.wsteam.wandscape.road.client.SplineEditorClientState.exitEditMode();
                    com.wsteam.wandscape.road.client.studio.RoadStudioOverlay.close();
                }
                // If entered from overview, go back to pure overview
                if (com.wsteam.wandscape.overview.client.OverviewClientState.isActive()) {
                    activeSubMode = SubMode.OVERVIEW;
                    syncCursorToState();
                    return;
                }
            }
            case STATS -> {
                // STATS is a pure overlay tab: entered from overview the camera stays active,
                // so leaving must return to pure overview (handlePanelEscape/G-key rely on this).
                if (com.wsteam.wandscape.overview.client.OverviewClientState.isActive()) {
                    activeSubMode = SubMode.OVERVIEW;
                    syncCursorToState();
                    return;
                }
            }
            case OVERVIEW -> {
                com.wsteam.wandscape.overview.client.OverviewFlightController.exit();
            }
        }
        syncCursorToState();
    }
}
