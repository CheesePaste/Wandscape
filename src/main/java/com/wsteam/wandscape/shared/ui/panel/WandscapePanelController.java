package com.wsteam.wandscape.shared.ui.panel;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.common.NeoForge;

import org.lwjgl.glfw.GLFW;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.building.internal.BuildingUnlockChecker;
import com.wsteam.wandscape.projection.client.ProjectionClientState;
import com.wsteam.wandscape.road.client.RoadPlacementOverlay;
import com.wsteam.wandscape.road.client.RoadPlacementState;
import com.wsteam.wandscape.shared.log.Log;


/**
 * Input controller for the Wandscape comprehensive panel.
 * Handles Escape key to close the panel and mouse clicks on UI tabs
 * when the cursor is lifted (C key toggled).
 */
public final class WandscapePanelController {

    private static final String TAG = "WandscapePanelController";

    // Tab layout constants — keep in sync with WandscapePanelOverlay
    public static final int TAB_W = 24;
    public static final int TAB_GAP = 4;
    public static final int TAB_COUNT = 3;
    public static final int TOP_BAR_HEIGHT = 26;

    private static boolean registered = false;

    private WandscapePanelController() {}

    public static void register() {
        if (registered) return;
        registered = true;
        var bus = NeoForge.EVENT_BUS;
        bus.addListener(MovementInputUpdateEvent.class, WandscapePanelController::onMovementInputUpdate);
        bus.addListener(ClientTickEvent.Post.class, WandscapePanelController::onClientTickPost);
        bus.addListener(InputEvent.MouseButton.Pre.class, WandscapePanelController::onMouseButtonPre);
        bus.addListener(InputEvent.MouseScrollingEvent.class, WandscapePanelController::onMouseScroll);
        bus.addListener(InputEvent.Key.class, WandscapePanelController::onKey);
        Log.info(TAG, "[Panel] Controller registered");
    }

    /**
     * Global: when cursor is lifted, zero out movement input so the player cannot move.
     * Uses {@link MovementInputUpdateEvent} which fires after {@code Input.tick()} reads
     * GLFW key states but before the values are applied to player movement.
     */
    static void onMovementInputUpdate(MovementInputUpdateEvent event) {
        if (!WandscapePanelState.isPanelOpen()) return;
        if (!WandscapePanelState.isCursorLifted()) return;

        var input = event.getInput();
        input.forwardImpulse = 0;
        input.leftImpulse = 0;
        input.up = false;
        input.down = false;
        input.left = false;
        input.right = false;
        input.jumping = false;
        input.shiftKeyDown = false;
    }

    static void onClientTickPost(ClientTickEvent.Post event) {
        if (!WandscapePanelState.isPanelOpen()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        // Enforce cursor state: when a Screen closes, MC grabs the mouse back to game.
        // If cursor was lifted, re-release it to the UI layer.
        if (WandscapePanelState.isCursorLifted() && mc.screen == null) {
            mc.mouseHandler.releaseMouse();
        }
    }

    static void onMouseButtonPre(InputEvent.MouseButton.Pre event) {
        if (!WandscapePanelState.isPanelOpen()) return;
        if (!WandscapePanelState.isCursorLifted()) return;

        if (event.getAction() != GLFW.GLFW_PRESS) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) return;
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();
        double guiScale = mc.getWindow().getGuiScale();
        double mouseX = mc.mouseHandler.xpos() / guiScale;
        double mouseY = mc.mouseHandler.ypos() / guiScale;

        // ── Guidance close (×) button — dismiss "Getting Started" guide ──
        if (com.wsteam.wandscape.shared.ui.guidance.GuideSession.shouldShow()) {
            boolean buildMode = WandscapePanelState.getActiveSubMode() == WandscapePanelState.SubMode.BUILD_PROJECTION;
            boolean isPlacing = WandscapePanelState.getBuildPhase() == WandscapePanelState.BuildPhase.PLACING;
            boolean isBar = WandscapePanelState.getBuildPhase() == WandscapePanelState.BuildPhase.BAR;
            if (com.wsteam.wandscape.shared.ui.guidance.GuideRenderer.isCloseClicked(mc.font, mouseX, mouseY,
                    screenW, WandscapePanelOverlay.TOP_BAR_H,
                    com.wsteam.wandscape.shared.ui.guidance.GuideRegistry.step(
                            com.wsteam.wandscape.shared.ui.guidance.GuideSession.currentStep()),
                    buildMode, isPlacing, isBar)) {
                com.wsteam.wandscape.shared.ui.guidance.GuideSession.dismiss();
                event.setCanceled(true);
                return;
            }
        }

        // ── Building selection bar handling ──
        if (BuildingSelectionOverlay.isActive()) {
            // Scrollbar click — jump to position
            if (BuildingSelectionOverlay.isOverScrollbar(mouseX, screenW)) {
                int barY = BuildingSelectionOverlay.getBarY(screenH);
                int gridY = barY + BuildingSelectionOverlay.GRID_TOP_OFFSET;
                int gridH = BuildingSelectionOverlay.BAR_HEIGHT - BuildingSelectionOverlay.GRID_TOP_OFFSET;
                int maxScroll = BuildingSelectionOverlay.getMaxScrollOffset();
                if (maxScroll > 0 && mouseY > gridY) {
                    float ratio = Math.min(1f, (float) (mouseY - gridY) / gridH);
                    int targetScroll = Math.round(ratio * maxScroll);
                    WandscapePanelState.setBuildingBarScrollOffset(targetScroll);
                }
                event.setCanceled(true);
                return;
            }
            // Category tab click
            int catIdx = BuildingSelectionOverlay.getCategoryAt(mouseX, mouseY, screenW, screenH);
            if (catIdx >= 0) {
                handleCategoryClick(catIdx);
                event.setCanceled(true);
                return;
            }
            // Building slot click
            int slotIdx = BuildingSelectionOverlay.getSlotAt(mouseX, mouseY, screenW, screenH);
            if (slotIdx >= 0) {
                handleBuildingSlotClick(slotIdx);
                event.setCanceled(true);
                return;
            }
            // Click on search bar area — handled by key events; just consume
            int barY = BuildingSelectionOverlay.getBarY(screenH);
            if (mouseY >= barY && mouseY <= barY + BuildingSelectionOverlay.BAR_HEIGHT) {
                event.setCanceled(true);
                return;
            }
        }

        // ── Road placement overlay (preset selection) ──
        // Single-click = highlight, double-click = confirm → enter PLACING phase
        if (RoadPlacementState.isProjecting()
                && WandscapePanelState.getActiveSubMode() == WandscapePanelState.SubMode.ROAD_PROJECTION
                && RoadPlacementState.getRoadPhase() == RoadPlacementState.RoadPhase.BAR) {
            
            RoadPlacementState.ToolMode toolMode = RoadPlacementOverlay.getToolModeClicked(mouseX, mouseY, screenW, screenH);
            if (toolMode != null) {
                RoadPlacementState.setActiveTool(toolMode);
                if (toolMode == RoadPlacementState.ToolMode.SPLINE) {
                    // SPLINE mode: keep the V-panel open and embed the native spline editor.
                    // SplineEditorController takes over world input (right-click camera, WASD flight, gizmo drag).
                    com.wsteam.wandscape.road.client.SplineEditorClientState.enterEditMode();
                    Log.debug(TAG, "[Spline Editor] Entered spline edit mode (right-drag camera, WASD flight, left-click add/select point, drag gizmo, ESC exit)");
                } else {
                    // REPLACE / FILL / DESTROY_FILL: leave the spline editor, enter PLACING phase as before
                    if (com.wsteam.wandscape.road.client.SplineEditorClientState.isEditing()) {
                        com.wsteam.wandscape.road.client.SplineEditorClientState.exitEditMode();
                    }
                    RoadPlacementState.enterPlacing();
                    WandscapePanelState.releaseCursorToGame();
                    String hint = switch (toolMode) {
                        case FILL -> "[Fill] Right-click set corner 1, Left-click set corner 2, Enter to submit";
                        case DESTROY_FILL -> "[Destroy/Fill] Right-click a block to set ref height & block, Left-click to set area, Enter to submit";
                        default -> "[Road Replace] Right-click set start, Left-click set end, Enter to submit";
                    };
                    Log.debug(TAG, hint);
                }
                event.setCanceled(true);
                return;
            }

            int presetIdx = RoadPlacementOverlay.getPresetAt(mouseX, mouseY, screenW, screenH);
            if (presetIdx >= 0) {
                boolean doubleClicked = RoadPlacementState.handlePresetDoubleClick(presetIdx);
                if (doubleClicked) {
                    // Double-click: confirm preset, enter PLACING phase (overlay hidden, cursor in game)
                    if (com.wsteam.wandscape.road.client.SplineEditorClientState.isEditing()) {
                        com.wsteam.wandscape.road.client.SplineEditorClientState.exitEditMode();
                    }
                    RoadPlacementState.enterPlacing();
                    WandscapePanelState.releaseCursorToGame();
                    String name = RoadPlacementState.getSelectedPreset().displayName();
                    Log.debug(TAG, "[Road] Placing preset: {} (right-click start, left-click end, ESC reselect)", name);
                }
                // Single click: highlight only (handlePresetDoubleClick already set selectedPresetIndex)
                event.setCanceled(true);
                return;
            }
        }

        // ── Top Bar Help ? button ──
        if (mouseY <= WandscapePanelOverlay.TOP_BAR_H) {
            int helpX = screenW - 24;
            if (mouseX >= helpX - 4 && mouseX <= helpX + 18 && mouseY >= 2 && mouseY <= 20) {
                openPanelHelpDocument();
                event.setCanceled(true);
                return;
            }
        }

        // ── Sidebar tabs ──
        if (mouseX <= WandscapePanelOverlay.SIDEBAR_W && mouseY >= WandscapePanelOverlay.TOP_BAR_H) {
            int sidebarIconIndex = getSidebarIconAt(mouseX, mouseY, screenW, screenH);
            if (sidebarIconIndex >= 0 && sidebarIconIndex < 3) {
                handleTabClick(sidebarIconIndex);
                event.setCanceled(true);
                return;
            } else if (sidebarIconIndex == 3) {
                Minecraft.getInstance().setScreen(new AnomalyScreen());
                event.setCanceled(true);
                return;
            }
        }
    }

    // ── Hit detection ──

    public static boolean isInTopBar(double mouseY, int screenH) {
        return mouseY < WandscapePanelOverlay.TOP_BAR_H;
    }

    public static boolean isInSidebar(double mouseX, double mouseY, int screenH) {
        return mouseX < WandscapePanelOverlay.SIDEBAR_W && mouseY > WandscapePanelOverlay.TOP_BAR_H;
    }

    public static int getSidebarIconAt(double mouseX, double mouseY, int screenW, int screenH) {
        if (mouseX < 0 || mouseX > WandscapePanelOverlay.SIDEBAR_W) return -1;
        if (mouseY < WandscapePanelOverlay.TOP_BAR_H) return -1;

        int startY = WandscapePanelOverlay.TOP_BAR_H + 8;
        int totalH = WandscapePanelOverlay.SIDEBAR_ICON_S + WandscapePanelOverlay.SIDEBAR_GAP;

        // Tabs 0–2 (Build, Road, Stats)
        for (int i = 0; i < 3; i++) {
            int iy = startY + i * totalH;
            if (mouseY >= iy && mouseY <= iy + WandscapePanelOverlay.SIDEBAR_ICON_S) return i;
        }

        // Warning icon (index 3)
        int warnY = startY + 3 * totalH + 12;
        if (mouseY >= warnY && mouseY <= warnY + WandscapePanelOverlay.SIDEBAR_ICON_S) return 3;

        return -1;
    }

    // ── Tab click → sub-mode switch ──

    private static void handleTabClick(int tabIndex) {
        // Switching away from the ROAD tab while the spline editor is open leaves it.
        if (com.wsteam.wandscape.road.client.SplineEditorClientState.isEditing()) {
            com.wsteam.wandscape.road.client.SplineEditorClientState.exitEditMode();
        }
        WandscapePanelState.SubMode targetMode = switch (tabIndex) {
            case 0 -> WandscapePanelState.SubMode.BUILD_PROJECTION;
            case 1 -> WandscapePanelState.SubMode.ROAD_PROJECTION;
            case 2 -> WandscapePanelState.SubMode.STATS;
            default -> null;
        };

        if (targetMode == null) return;

        WandscapePanelState.SubMode current = WandscapePanelState.getActiveSubMode();
        if (current == targetMode) {
            // Clicking the active tab: deactivate (exit sub-mode, stay in panel)
            WandscapePanelState.exitCurrentSubMode();
            // If exitCurrentSubMode returned to overview, don't override with NONE
            if (WandscapePanelState.getActiveSubMode() != WandscapePanelState.SubMode.OVERVIEW) {
                WandscapePanelState.setSubMode(WandscapePanelState.SubMode.NONE);
            }
            return;
        }

        WandscapePanelState.enterSubMode(targetMode);
        Log.info(TAG, "[Panel] Tab {} clicked → SubMode {}", tabIndex, targetMode);
    }

    // ── Building selection bar handlers ──

    private static void handleCategoryClick(int catIdx) {
        var cats = BuildingSelectionOverlay.getCategories();
        if (catIdx >= 0 && catIdx < cats.size()) {
            WandscapePanelState.setBuildingBarCategory(cats.get(catIdx));
            WandscapePanelState.setBuildingBarSelectedIndex(-1);
        }
    }

    private static void handleBuildingSlotClick(int slotIndex) {
        // Safety: ignore clicks on locked buildings
        var slots = ProjectionClientState.getBuildingSlots();
        if (slotIndex >= 0 && slotIndex < slots.size()) {
            BuildingConfig config = BuildingConfigLoader.getInstance().get(slots.get(slotIndex).id());
            if (config == null || !BuildingUnlockChecker.isUnlocked(WandscapePanelState.getColonyId(), config)) return;
        }
        boolean doubleClicked = WandscapePanelState.handleBuildingSlotClick(slotIndex);
        if (doubleClicked) {
            // Select building, close bar, enter PLACING phase (cursor in game, ghost visible)
            ProjectionClientState.setSelectedSlotIndex(slotIndex);
            WandscapePanelState.enterPlacingPhase();
            String name = (slotIndex >= 0 && slotIndex < slots.size())
                    ? slots.get(slotIndex).displayName() : "???";
            Log.debug(TAG, "[Build] Selected: {} (ESC to reselect)", name);
        }
    }

    // ── Keyboard handler (search bar input) ──

    static void onKey(InputEvent.Key event) {
        if (event.getAction() != GLFW.GLFW_PRESS) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) return;

        int key = event.getKey();

        // B key: toggle interaction area overlay (works whenever panel is open)
        if (key == GLFW.GLFW_KEY_B && WandscapePanelState.isPanelOpen()) {
            WandscapePanelState.toggleBuildingAreas();
            Log.debug(TAG, "[Panel] Building areas overlay: {}", WandscapePanelState.isShowBuildingAreas() ? "ON" : "OFF");
            return;
        }

        // G key: toggle overview mode ↔ ground mode (only when panel is open)
        if (key == GLFW.GLFW_KEY_G && WandscapePanelState.isPanelOpen()) {
            handleGKeyToggle();
            return;
        }

        // Guide key: open guide document (only when panel is open)
        if (com.wsteam.wandscape.WandscapeClient.GUIDE_TOGGLE.matches(key, event.getScanCode())
                && WandscapePanelState.isPanelOpen()) {
            openPanelHelpDocument();
            return;
        }

        if (!BuildingSelectionOverlay.isActive()) return;
        int mods = event.getModifiers();
        boolean shift = (mods & GLFW.GLFW_MOD_SHIFT) != 0;

        if (key == GLFW.GLFW_KEY_BACKSPACE) {
            String current = WandscapePanelState.getBuildingBarSearch();
            if (!current.isEmpty()) {
                WandscapePanelState.setBuildingBarSearch(current.substring(0, current.length() - 1));
            }
            return;
        }

        String ch = keyToChar(key, shift);
        if (ch != null) {
            String current = WandscapePanelState.getBuildingBarSearch();
            if (current.length() < 32) {
                WandscapePanelState.setBuildingBarSearch(current + ch);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Overview mode toggle ──
    // ═══════════════════════════════════════════════════════════════

    /**
     * G key: toggle between overview camera and ground (build projection) mode.
     * Only effective when the panel is open.
     */
    private static void handleGKeyToggle() {
        // Check if overview camera is currently active (pure overview OR overview+build/road)
        if (com.wsteam.wandscape.overview.client.OverviewClientState.isActive()) {
            // Overview → Ground mode: save current submode to restore after exit
            WandscapePanelState.SubMode prevSubMode = WandscapePanelState.getActiveSubMode();
            WandscapePanelState.exitCurrentSubMode();
            com.wsteam.wandscape.overview.client.OverviewFlightController.exit();
            // Restore the submode that was active (ROAD, or default BUILD for everything else)
            if (prevSubMode == WandscapePanelState.SubMode.ROAD_PROJECTION) {
                WandscapePanelState.enterSubMode(WandscapePanelState.SubMode.ROAD_PROJECTION);
            } else {
                WandscapePanelState.enterSubMode(WandscapePanelState.SubMode.BUILD_PROJECTION);
            }
            Log.debug(TAG, "[Panel] Ground mode (G for overview)");
        } else {
            // Ground → Overview mode
            WandscapePanelState.SubMode current = WandscapePanelState.getActiveSubMode();
            if (current == WandscapePanelState.SubMode.BUILD_PROJECTION || current == WandscapePanelState.SubMode.NONE
                    || current == WandscapePanelState.SubMode.STATS || current == WandscapePanelState.SubMode.ROAD_PROJECTION) {
                WandscapePanelState.exitCurrentSubMode();
            }
            WandscapePanelState.enterSubMode(WandscapePanelState.SubMode.OVERVIEW);
            Log.debug(TAG, "[Panel] Overview mode (WASD move, Scroll zoom, G for ground)");
        }
    }

    // ── Mouse scroll (building selection bar) ──

    static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        if (!BuildingSelectionOverlay.isActive()) return;

        int maxScroll = BuildingSelectionOverlay.getMaxScrollOffset();
        if (maxScroll <= 0) return;

        int current = WandscapePanelState.getBuildingBarScrollOffset();
        double delta = event.getScrollDeltaY();
        if (delta > 0) {
            current = Math.max(0, current - 1);
        } else {
            current = Math.min(maxScroll, current + 1);
        }
        WandscapePanelState.setBuildingBarScrollOffset(current);
        event.setCanceled(true);
    }

    /** Compute number of building grid columns for the current screen width. */
    private static int getCols(int screenW) {
        return Math.max(1, (screenW - BuildingSelectionOverlay.GRID_PAD_X * 2 - BuildingSelectionOverlay.SCROLLBAR_W) / BuildingSelectionOverlay.CELL_W);
    }

    private static String keyToChar(int key, boolean shift) {
        if (key >= GLFW.GLFW_KEY_A && key <= GLFW.GLFW_KEY_Z) {
            char c = (char) ('a' + (key - GLFW.GLFW_KEY_A));
            return shift ? String.valueOf(Character.toUpperCase(c)) : String.valueOf(c);
        }
        if (key >= GLFW.GLFW_KEY_0 && key <= GLFW.GLFW_KEY_9) {
            if (shift) {
                return switch (key - GLFW.GLFW_KEY_0) {
                    case 0 -> ")"; case 1 -> "!"; case 2 -> "@"; case 3 -> "#";
                    case 4 -> "$"; case 5 -> "%"; case 6 -> "^"; case 7 -> "&";
                    case 8 -> "*"; case 9 -> "(";
                    default -> null;
                };
            }
            return String.valueOf((char) ('0' + (key - GLFW.GLFW_KEY_0)));
        }
        if (key == GLFW.GLFW_KEY_SPACE) return " ";
        if (key == GLFW.GLFW_KEY_MINUS) return shift ? "_" : "-";
        if (key == GLFW.GLFW_KEY_PERIOD) return shift ? ">" : ".";
        if (key == GLFW.GLFW_KEY_SLASH) return shift ? "?" : "/";
        if (key == GLFW.GLFW_KEY_APOSTROPHE) return shift ? "\"" : "'";
        return null;
    }

    public static void openPanelHelpDocument() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            String docPath = "overview_guide";
            if (WandscapePanelState.getActiveSubMode() == WandscapePanelState.SubMode.ROAD_PROJECTION) {
                docPath = "road_guide";
            }
            String content = com.wsteam.wandscape.shared.ui.markdown.navigation.DocumentLoader.loadMarkdown(docPath);
            mc.setScreen(new com.wsteam.wandscape.shared.ui.guide.GuideTestScreen(null, content, docPath));
        }
    }
}
