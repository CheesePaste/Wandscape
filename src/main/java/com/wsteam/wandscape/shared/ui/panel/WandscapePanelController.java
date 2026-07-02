package com.wsteam.wandscape.shared.ui.panel;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

import org.lwjgl.glfw.GLFW;
import com.wsteam.wandscape.projection.client.ProjectionClientState;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Input controller for the Wandscape comprehensive panel.
 * Handles Escape key to close the panel and mouse clicks on UI tabs
 * when the cursor is lifted (C key toggled).
 */
public final class WandscapePanelController {

    private static final String TAG = "WandscapePanelController";

    // Tab layout constants — keep in sync with WandscapePanelOverlay
    public static final int TAB_W = 80;
    public static final int TAB_GAP = 6;
    public static final int TAB_COUNT = 4;
    public static final int BOTTOM_BAR_HEIGHT = 40;
    public static final int TOP_BAR_HEIGHT = 32;

    private static boolean registered = false;

    private WandscapePanelController() {}

    public static void register() {
        if (registered) return;
        registered = true;
        var bus = NeoForge.EVENT_BUS;
        bus.addListener(MovementInputUpdateEvent.class, WandscapePanelController::onMovementInputUpdate);
        bus.addListener(ClientTickEvent.Post.class, WandscapePanelController::onClientTickPost);
        bus.addListener(InputEvent.MouseButton.Pre.class, WandscapePanelController::onMouseButtonPre);
        bus.addListener(InputEvent.Key.class, WandscapePanelController::onKey);
        bus.addListener(ScreenEvent.Opening.class, WandscapePanelController::onScreenOpen);
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
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();
        double guiScale = mc.getWindow().getGuiScale();
        double mouseX = mc.mouseHandler.xpos() / guiScale;
        double mouseY = mc.mouseHandler.ypos() / guiScale;

        // ── Building selection bar handling ──
        if (BuildingSelectionOverlay.isActive()) {
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

        // ── Bottom bar tabs ──
        if (mouseY >= screenH - BOTTOM_BAR_HEIGHT) {
            int tabIndex = getTabAt(mouseX, screenW);
            if (tabIndex >= 0) {
                handleTabClick(tabIndex);
                event.setCanceled(true);
            }
        }
    }

    // ── Hit detection ──

    public static int getTabAt(double mouseX, int screenW) {
        int totalTabsW = TAB_COUNT * TAB_W + (TAB_COUNT - 1) * TAB_GAP;
        int tabStartX = (screenW - totalTabsW) / 2;
        for (int i = 0; i < TAB_COUNT; i++) {
            int tabX = tabStartX + i * (TAB_W + TAB_GAP);
            if (mouseX >= tabX && mouseX <= tabX + TAB_W) {
                return i;
            }
        }
        return -1;
    }

    public static boolean isInTopBar(double mouseY, int screenH) {
        return mouseY < TOP_BAR_HEIGHT;
    }

    public static boolean isInBottomBar(double mouseY, int screenH) {
        return mouseY > screenH - BOTTOM_BAR_HEIGHT;
    }

    // ── Tab click → sub-mode switch ──

    private static void handleTabClick(int tabIndex) {
        WandscapePanelState.SubMode targetMode = switch (tabIndex) {
            case 0 -> WandscapePanelState.SubMode.BUILD_PROJECTION;
            case 1 -> WandscapePanelState.SubMode.ROAD_PROJECTION;
            case 2 -> WandscapePanelState.SubMode.BUILD_EDITOR;
            case 3 -> WandscapePanelState.SubMode.STATS;
            default -> null;
        };

        if (targetMode == null) return;

        WandscapePanelState.SubMode current = WandscapePanelState.getActiveSubMode();
        if (current == targetMode) {
            // Clicking the active tab: deactivate (exit sub-mode, stay in panel)
            WandscapePanelState.exitCurrentSubMode();
            WandscapePanelState.setSubMode(WandscapePanelState.SubMode.NONE);
            return;
        }

        WandscapePanelState.enterSubMode(targetMode);
        Log.info(TAG, "[Panel] Tab {} clicked → SubMode {}", tabIndex, targetMode);
    }

    // ── Building selection bar handlers ──

    private static void handleCategoryClick(int catIdx) {
        var allSlots = ProjectionClientState.getBuildingSlots();
        // Derive categories list the same way BuildingSelectionOverlay does
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        seen.add("All");
        for (var slot : allSlots) {
            seen.add(slot.category());
        }
        var cats = new java.util.ArrayList<>(seen);
        if (catIdx >= 0 && catIdx < cats.size()) {
            WandscapePanelState.setBuildingBarCategory(cats.get(catIdx));
            WandscapePanelState.setBuildingBarSelectedIndex(-1);
        }
    }

    private static void handleBuildingSlotClick(int slotIndex) {
        boolean doubleClicked = WandscapePanelState.handleBuildingSlotClick(slotIndex);
        if (doubleClicked) {
            // Select building, close bar, enter PLACING phase (cursor in game, ghost visible)
            ProjectionClientState.setSelectedSlotIndex(slotIndex);
            WandscapePanelState.enterPlacingPhase();
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                var slots = ProjectionClientState.getBuildingSlots();
                String name = (slotIndex >= 0 && slotIndex < slots.size())
                        ? slots.get(slotIndex).displayName() : "???";
                mc.player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal("[Build] §aSelected: " + name + " §7— ESC to reselect"),
                        true);
            }
        }
    }

    // ── Keyboard handler (search bar input) ──

    static void onKey(InputEvent.Key event) {
        if (!BuildingSelectionOverlay.isActive()) return;
        if (event.getAction() != GLFW.GLFW_PRESS) return;

        int key = event.getKey();
        int mods = event.getModifiers();
        boolean shift = (mods & GLFW.GLFW_MOD_SHIFT) != 0;

        // ESC handled by ScreenEvent.Opening to suppress pause menu

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

    // ── Suppress pause menu when in build mode ──

    static void onScreenOpen(ScreenEvent.Opening event) {
        if (!(event.getScreen() instanceof PauseScreen)) return;

        // BAR phase: building selection bar is open — cancel pause, exit sub-mode
        if (BuildingSelectionOverlay.isActive()) {
            event.setCanceled(true);
            WandscapePanelState.exitCurrentSubMode();
            WandscapePanelState.setSubMode(WandscapePanelState.SubMode.NONE);
            return;
        }

        // PLACING phase: ghost is visible, cursor in game — cancel pause, return to bar
        if (WandscapePanelState.isPanelOpen()
                && WandscapePanelState.getActiveSubMode() == WandscapePanelState.SubMode.BUILD_PROJECTION
                && WandscapePanelState.getBuildPhase() == WandscapePanelState.BuildPhase.PLACING) {
            event.setCanceled(true);
            ProjectionClientState.setGhostPos(null);
            WandscapePanelState.returnToBar();
            Minecraft.getInstance().player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal("[Build] §7Select another building or ESC to cancel"),
                    true);
        }
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
}
