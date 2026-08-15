package com.wsteam.wandscape.shared.ui.panel;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
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
 * Handles the Escape exit pipeline (spline editor → PLACING cursor raise → sub-mode → panel),
 * mouse clicks on UI tabs when the cursor is lifted (C key toggled), and the building search box.
 */
public final class WandscapePanelController {

    private static final String TAG = "WandscapePanelController";

    // Tab layout constants — keep in sync with WandscapePanelOverlay
    public static final int TAB_W = 24;
    public static final int TAB_GAP = 4;
    public static final int TAB_COUNT = 3;
    public static final int TOP_BAR_HEIGHT = 26;

    private static boolean registered = false;

    // Cursor reconciliation state — see onClientTickPost. The reconciler keeps the
    // OS cursor aligned with `cursorLifted` every tick (recovering from vanilla
    // grabs that would otherwise leave it hidden), defers to the spline editor's
    // right-drag camera grab, and restores the cursor to its last free position on
    // release so it doesn't snap to window center after a grab.
    private static boolean lastScreenOpen = false;
    private static boolean lastDesiredLifted = false;
    private static double savedCursorX;
    private static double savedCursorY;
    private static boolean hasSavedCursor = false;

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
        bus.addListener(ScreenEvent.Opening.class, WandscapePanelController::onScreenOpening);
        Log.info(TAG, "[Panel] Controller registered");
    }

    /**
     * Global: when cursor is lifted, zero out movement input so the player cannot move.
     * Uses {@link MovementInputUpdateEvent} which fires after {@code Input.tick()} reads
     * GLFW key states but before the values are applied to player movement.
     */
    static void onMovementInputUpdate(MovementInputUpdateEvent event) {
        if (!WandscapePanelState.isPanelOpen() || WandscapePanelState.isPanelHidden()) return;
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
        if (!WandscapePanelState.isPanelOpen()) {
            // Reset edge-tracking so the next panel session reconciles cleanly.
            lastScreenOpen = false;
            lastDesiredLifted = false;
            hasSavedCursor = false;
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        // Tab 用作「折叠/展开新手引导」：仅引导显示期间按下 Tab 折叠引导并抑制原版
        // 玩家列表闪烁；引导结束后 Tab 恢复原版玩家列表功能。
        // 面板隐藏时放行，让 Tab 回到原版玩家列表。
        if (!WandscapePanelState.isPanelHidden()
                && com.wsteam.wandscape.shared.ui.guidance.GuideSession.shouldShow()
                && com.wsteam.wandscape.WandscapeClient.GUIDE_FOLD_TOGGLE.getKey().getValue() == GLFW.GLFW_KEY_TAB) {
            mc.options.keyPlayerList.setDown(false);
        }

        long window = mc.getWindow().getWindow();
        boolean screenOpen = mc.screen != null;
        boolean screenJustClosed = lastScreenOpen && !screenOpen;
        // 隐藏时视为光标落回游戏层：grab 鼠标，且不把当前（受控）位置计入「自由位置」缓存，
        // 这样恢复时回到隐藏前抬起位置。
        boolean cursorLifted = WandscapePanelState.isCursorLifted() && !WandscapePanelState.isPanelHidden();
        boolean splineCam = com.wsteam.wandscape.road.client.SplineEditorController.isCameraActive();
        // RMB 按住 = 视角旋转（V 面板 overview / 样条相机共用），此时必须把 OS 光标锁住
        // 才能拿到连续 GLFW delta；松开后恢复自由。远程对账器没有这一分支，因为那边
        // 没有「持久自由光标 + RMB 旋转」交互——合并时补回（本分支 5650adb6 的核心设计）。
        boolean rightDown = window != 0L && org.lwjgl.glfw.GLFW.glfwGetMouseButton(window, org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        lastScreenOpen = screenOpen;

        // Record the cursor's free position whenever it is genuinely visible/free:
        // while a Screen is open, or while steadily lifted. Skip the grab→free
        // transition tick, the frame a Screen just closed (vanilla re-grabbed), and
        // the spline editor's camera grab — in all those the cursor is still hidden.
        // The saved position is restored on release so the cursor doesn't jump to
        // window center after a grab.
        boolean cursorFree = screenOpen
                || (cursorLifted && !rightDown && lastDesiredLifted && !splineCam && !screenJustClosed);
        if (cursorFree) {
            double[] mx = new double[1], my = new double[1];
            GLFW.glfwGetCursorPos(window, mx, my);
            savedCursorX = mx[0];
            savedCursorY = my[0];
            hasSavedCursor = true;
        }

        // A Screen owns cursor visibility; only re-assert right after it closes
        // (vanilla grabs the mouse when a Screen closes).
        if (screenOpen) return;

        // The spline editor's right-drag camera grab owns the cursor while active —
        // reconciling here would release it and break camera rotation.
        if (splineCam) {
            lastDesiredLifted = false;   // force a restore transition when it releases
            return;
        }

        // Every-tick re-assert recovers from vanilla grabs that would otherwise
        // leave the cursor hidden when it should be visible (and vice-versa).
        // RMB 按住时视为 grabbed（视角旋转需要锁鼠标拿增量），松开后恢复 cursorLifted 意图。
        boolean desired = cursorLifted && !rightDown;
        boolean justTransitioned = (desired != lastDesiredLifted);
        if (desired) {
            mc.mouseHandler.releaseMouse();
            // On a fresh grab→free transition (or right after a Screen closed), put
            // the cursor back where it last was instead of window center.
            if ((justTransitioned || screenJustClosed) && hasSavedCursor) {
                GLFW.glfwSetCursorPos(window, savedCursorX, savedCursorY);
            }
        } else {
            mc.mouseHandler.grabMouse();
        }
        lastDesiredLifted = desired;
    }

    static void onMouseButtonPre(InputEvent.MouseButton.Pre event) {
        if (!WandscapePanelState.isPanelOpen() || WandscapePanelState.isPanelHidden()) return;
        if (!WandscapePanelState.isCursorLifted()) return;

        if (event.getAction() != GLFW.GLFW_PRESS) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) return;
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();
        double guiScale = mc.getWindow().getGuiScale();
        double mouseX = mc.mouseHandler.xpos() / guiScale;
        double mouseY = mc.mouseHandler.ypos() / guiScale;

        // ── Guidance buttons handling (close × / collapse ➖ / expand ➕) ──
        if (com.wsteam.wandscape.shared.ui.guidance.GuideSession.shouldShow()) {
            boolean buildMode = WandscapePanelState.getActiveSubMode() == WandscapePanelState.SubMode.BUILD_PROJECTION;
            boolean isPlacing = WandscapePanelState.getBuildPhase() == WandscapePanelState.BuildPhase.PLACING;
            boolean isBar = WandscapePanelState.getBuildPhase() == WandscapePanelState.BuildPhase.BAR;
            boolean isPinned = com.wsteam.wandscape.projection.client.ProjectionClientState.isPinned();
            var step = com.wsteam.wandscape.shared.ui.guidance.GuideRegistry.step(
                    com.wsteam.wandscape.shared.ui.guidance.GuideSession.currentStep());
            if (com.wsteam.wandscape.shared.ui.guidance.GuideRenderer.isCloseClicked(mc.font, mouseX, mouseY,
                    screenW, screenH, step, buildMode, isPlacing, isBar, isPinned)) {
                com.wsteam.wandscape.shared.ui.guidance.GuideSession.dismiss();
                event.setCanceled(true);
                return;
            }
            if (com.wsteam.wandscape.shared.ui.guidance.GuideRenderer.isCollapseClicked(mc.font, mouseX, mouseY,
                    screenW, screenH, step, buildMode, isPlacing, isBar, isPinned)) {
                com.wsteam.wandscape.shared.ui.guidance.GuideSession.toggleCollapsed();
                event.setCanceled(true);
                return;
            }
        }

        // ── Build mode right pop panel handling ──
        if (com.wsteam.wandscape.projection.client.BuildPopPanelOverlay.isActive()) {
            if (com.wsteam.wandscape.projection.client.BuildPopPanelOverlay.isOverLockButton(mouseX, mouseY, screenW)) {
                boolean curPinned = com.wsteam.wandscape.projection.client.ProjectionClientState.isPinned();
                com.wsteam.wandscape.projection.client.ProjectionClientState.setPinned(!curPinned);
                mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                        net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0f));
                event.setCanceled(true);
                return;
            }
            if (com.wsteam.wandscape.projection.client.BuildPopPanelOverlay.isOverSubmitButton(mouseX, mouseY, screenW)) {
                if (com.wsteam.wandscape.projection.client.ProjectionClientState.getGhostPos() == null) {
                    mc.player.displayClientMessage(
                            net.minecraft.network.chat.Component.literal("§c")
                                    .append(com.wsteam.wandscape.shared.ui.I18n.name(
                                            "message.wandscape.projection.no_target",
                                            "没有可施工的位置 — 先对准地面")), true);
                } else {
                    com.wsteam.wandscape.projection.client.ProjectionFlightController.openConstructionScreen(mc);
                }
                mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                        net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0f));
                event.setCanceled(true);
                return;
            }
            if (com.wsteam.wandscape.projection.client.BuildPopPanelOverlay.isOverPanel(mouseX, mouseY, screenW)) {
                event.setCanceled(true);
                return;
            }
        }

        // ── Building selection bar handling ──
        if (BuildingSelectionOverlay.isActive()) {
            // Search box click activates keyboard input; any other bar click deactivates it
            if (BuildingSelectionOverlay.isOverSearchBox(mouseX, mouseY, screenW, screenH)) {
                WandscapePanelState.setBuildingBarSearchFocused(true);
                event.setCanceled(true);
                return;
            }
            WandscapePanelState.setBuildingBarSearchFocused(false);

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
        WandscapePanelState.SubMode targetMode = switch (tabIndex) {
            case 0 -> WandscapePanelState.SubMode.BUILD_PROJECTION;
            case 1 -> WandscapePanelState.SubMode.ROAD_PROJECTION;
            case 2 -> WandscapePanelState.SubMode.STATS;
            default -> null;
        };

        if (targetMode == null) return;

        WandscapePanelState.SubMode current = WandscapePanelState.getActiveSubMode();
        if (current == targetMode) {
            // Clicking an already-active tab clean-exits the sub-mode (same as pressing ESC).
            WandscapePanelState.exitCurrentSubMode();
            if (!com.wsteam.wandscape.overview.client.OverviewClientState.isActive()) {
                WandscapePanelState.setSubMode(WandscapePanelState.SubMode.NONE);
            }
            Log.info(TAG, "[Panel] Tab {} clicked again → exited SubMode {}", tabIndex, targetMode);
            return;
        }

        // Switching to a different tab: exitCurrentSubMode inside enterSubMode handles cleanup.
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
        // Single click: switch the held building while keeping the bar open.
        if (slotIndex >= 0) {
            ProjectionClientState.setSelectedSlotIndex(slotIndex);
        }
        if (doubleClicked) {
            // Double click: close bar, enter PLACING phase (cursor in game, ghost visible)
            WandscapePanelState.enterPlacingPhase();
            String name = (slotIndex >= 0 && slotIndex < slots.size())
                    ? slots.get(slotIndex).displayName() : "???";
        }
    }

    // ── Keyboard handler (search bar input) ──

    static void onKey(InputEvent.Key event) {
        if (event.getAction() != GLFW.GLFW_PRESS) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) return;
        if (WandscapePanelState.isPanelHidden()) return;

        int key = event.getKey();
        int scanCode = event.getScanCode();
        int mods = event.getModifiers();

        // Focused search box: route printable/backspace keys into it FIRST, so
        // global hotkeys (H/G/B) don't hijack letters while typing a name.
        if (BuildingSelectionOverlay.isActive() && WandscapePanelState.isBuildingBarSearchFocused()) {
            if (handleSearchInput(key, mods)) {
                return;
            }
        }

        // Enter key in Build mode: toggle Lock / Pinned state (Phase 1 ↔ Phase 2)
        if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)
                && WandscapePanelState.isPanelOpen()
                && WandscapePanelState.getActiveSubMode() == WandscapePanelState.SubMode.BUILD_PROJECTION
                && com.wsteam.wandscape.projection.client.ProjectionClientState.isProjecting()) {
            boolean curPinned = com.wsteam.wandscape.projection.client.ProjectionClientState.isPinned();
            com.wsteam.wandscape.projection.client.ProjectionClientState.setPinned(!curPinned);
            mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                    net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0f));
            return;
        }

        // Building areas overlay toggle (works whenever panel is open)
        if (com.wsteam.wandscape.WandscapeClient.PANEL_AREAS_TOGGLE.matches(key, scanCode)
                && WandscapePanelState.isPanelOpen()) {
            WandscapePanelState.toggleBuildingAreas();
            return;
        }

        // Overview mode ↔ ground mode toggle (only when panel is open)
        if (com.wsteam.wandscape.WandscapeClient.OVERVIEW_TOGGLE.matches(key, scanCode)
                && WandscapePanelState.isPanelOpen()) {
            handleGKeyToggle();
            return;
        }

        // Guide key: open guide document (only when panel is open)
        if (com.wsteam.wandscape.WandscapeClient.GUIDE_TOGGLE.matches(key, scanCode)
                && WandscapePanelState.isPanelOpen()) {
            openPanelHelpDocument();
            return;
        }

        // Tab key: fold/expand the tutorial guide only (cursor raise is on C).
        // When no guide is showing, Tab does nothing to the panel.
        if (com.wsteam.wandscape.WandscapeClient.GUIDE_FOLD_TOGGLE.matches(key, scanCode)
                && WandscapePanelState.isPanelOpen()
                && com.wsteam.wandscape.shared.ui.guidance.GuideSession.shouldShow()) {
            com.wsteam.wandscape.shared.ui.guidance.GuideSession.toggleCollapsed();
            return;
        }

        // C key: dedicated cursor-raise key — toggles. First press frees the cursor
        // for UI; a second press returns it to the game layer (grabbed). Stays
        // independent of the tutorial guide hijacking Tab (fold/expand) during onboarding.
        if (com.wsteam.wandscape.WandscapeClient.RAISE_CURSOR.matches(key, scanCode)
                && WandscapePanelState.isPanelOpen()) {
            if (WandscapePanelState.isCursorLifted()) {
                WandscapePanelState.releaseCursorToGame();
            } else {
                WandscapePanelState.liftCursorForUI();
            }
            return;
        }

        // 1/2/3/4: quick-switch into Build/Road/Stats/Warning（吞掉原版快捷栏切换）。
        // 面板开着时数字键切子模式，面板关着则保持原版快捷栏。
        if (WandscapePanelState.isPanelOpen()) {
            int digit = switch (key) {
                case GLFW.GLFW_KEY_1 -> 0;
                case GLFW.GLFW_KEY_2 -> 1;
                case GLFW.GLFW_KEY_3 -> 2;
                case GLFW.GLFW_KEY_4 -> 3;
                default -> -1;
            };
            if (digit >= 0) {
                // InputEvent.Key fires after KeyMapping.click() but before the next
                // handleKeybinds(), so consuming the hotbar click here suppresses the
                // vanilla hotbar slot switch for this digit.
                mc.options.keyHotbarSlots[digit].consumeClick();
                if (digit < 3) {
                    handleTabClick(digit);
                } else {
                    mc.setScreen(new AnomalyScreen());
                }
                return;
            }
        }

        // Search bar input only accepted once the box has been clicked/activated
        if (!BuildingSelectionOverlay.isActive()) return;
        if (!WandscapePanelState.isBuildingBarSearchFocused()) return;
        handleSearchInput(key, mods);
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Escape exit pipeline (replaces the vanilla pause screen) ──
    // ═══════════════════════════════════════════════════════════════

    /**
     * While the panel is open, ESC is intercepted here instead of opening the vanilla
     * pause menu. Each press walks one step down the exit pipeline (innermost first):
     * spline editor → PLACING cursor raise → sub-mode exit → panel close.
     */
    static void onScreenOpening(ScreenEvent.Opening event) {
        if (!WandscapePanelState.isPanelOpen() || WandscapePanelState.isPanelHidden()) return;
        if (!(event.getScreen() instanceof PauseScreen)) return;
        event.setCanceled(true);
        handlePanelEscape();
    }

    private static void handlePanelEscape() {
        // 1. Spline road editor: ESC exits edit mode, stays in the ROAD selection bar
        if (com.wsteam.wandscape.road.client.SplineEditorClientState.isEditing()) {
            com.wsteam.wandscape.road.client.SplineEditorClientState.exitEditMode();
            return;
        }

        WandscapePanelState.SubMode sub = WandscapePanelState.getActiveSubMode();

        // 2.5 Build projection pinned (gizmo phase): ESC first unpins back to aiming phase.
        //     Covers both ground and overview (overview keeps the BUILD_PROJECTION sub-mode).
        if (sub == WandscapePanelState.SubMode.BUILD_PROJECTION
                && com.wsteam.wandscape.projection.client.ProjectionClientState.isPinned()) {
            com.wsteam.wandscape.projection.client.ProjectionClientState.setPinned(false);
            return;
        }

        // 3. Sub-mode active → exit it, keep the panel open
        if (sub != WandscapePanelState.SubMode.NONE && sub != WandscapePanelState.SubMode.OVERVIEW) {
            WandscapePanelState.exitCurrentSubMode();
            // Ground-mode / STATS exit leaves the sub-mode set — drop to the bare panel
            WandscapePanelState.SubMode after = WandscapePanelState.getActiveSubMode();
            if (after == WandscapePanelState.SubMode.BUILD_PROJECTION
                    || after == WandscapePanelState.SubMode.ROAD_PROJECTION
                    || after == WandscapePanelState.SubMode.STATS) {
                WandscapePanelState.setSubMode(WandscapePanelState.SubMode.NONE);
                // 地面/STATS 退出后子模式清空 → 回常态抓取
                WandscapePanelState.syncCursorToState();
            }
            return;
        }

        // 4. Bare panel (overview or no sub-mode) → close it
        WandscapePanelState.closePanel();
    }

    /** Type printable chars / backspace into the building search box. @return true if consumed. */
    private static boolean handleSearchInput(int key, int mods) {
        boolean shift = (mods & GLFW.GLFW_MOD_SHIFT) != 0;
        if (key == GLFW.GLFW_KEY_BACKSPACE) {
            String current = WandscapePanelState.getBuildingBarSearch();
            if (!current.isEmpty()) {
                WandscapePanelState.setBuildingBarSearch(current.substring(0, current.length() - 1));
            }
            return true;
        }
        String ch = keyToChar(key, shift);
        if (ch != null) {
            String current = WandscapePanelState.getBuildingBarSearch();
            if (current.length() < 32) {
                WandscapePanelState.setBuildingBarSearch(current + ch);
            }
            return true;
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Overview mode toggle ──
    // ═══════════════════════════════════════════════════════════════

    /**
     * G key: toggle between overview camera and ground (first-person) mode.
     * Only effective when the panel is open.
     */
    private static void handleGKeyToggle() {
        // Check if overview camera is currently active (pure overview OR overview+build/road)
        if (com.wsteam.wandscape.overview.client.OverviewClientState.isActive()) {
            // Overview → Ground mode: exit the overview camera and return to a clean panel
            // state. Must NOT auto-open the building panel (BUILD_PROJECTION opens the
            // building selection bar via enterSubMode's overview-branch).
            WandscapePanelState.exitCurrentSubMode();
            com.wsteam.wandscape.overview.client.OverviewFlightController.exit();
            WandscapePanelState.setSubMode(WandscapePanelState.SubMode.NONE);
        } else {
            // Ground → Overview mode
            WandscapePanelState.SubMode current = WandscapePanelState.getActiveSubMode();
            if (current == WandscapePanelState.SubMode.BUILD_PROJECTION || current == WandscapePanelState.SubMode.NONE
                    || current == WandscapePanelState.SubMode.STATS || current == WandscapePanelState.SubMode.ROAD_PROJECTION) {
                WandscapePanelState.exitCurrentSubMode();
            }
            WandscapePanelState.enterSubMode(WandscapePanelState.SubMode.OVERVIEW);
        }
    }

    // ── Mouse scroll (building selection bar) ──

    static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        if (!BuildingSelectionOverlay.isActive() || WandscapePanelState.isPanelHidden()) return;

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
