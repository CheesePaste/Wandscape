package com.wsteam.wandscape.road.client;

import org.lwjgl.glfw.GLFW;

import com.wsteam.wandscape.road.core.SplineVec3;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.ui.panel.WandscapePanelState;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;

/**
 * Per-tick controller for the Spline Road Editor.
 * Manages right-click camera rotation, WASD flight, and blocks normal gameplay.
 */
public final class SplineEditorController {
    private static final String TAG = "SplineEditorController";

    private static boolean wasEscapeDown = false;
    private static boolean wasGDown = false;
    private static boolean wasDeleteDown = false;
    private static boolean cameraActive = false;
    private static int skipFrames = 0;
    private static boolean topDownWasGrabbed = false;

    private SplineEditorController() {}

    /** True while the player is holding RMB to rotate the editor camera (cursor grabbed). */
    public static boolean isCameraActive() {
        return cameraActive;
    }

    public static void register() {
        var bus = net.neoforged.neoforge.common.NeoForge.EVENT_BUS;
        bus.addListener(ClientTickEvent.Post.class, SplineEditorController::onClientTickPost);
        bus.addListener(net.neoforged.neoforge.client.event.RenderLevelStageEvent.class, SplineEditorController::onRenderLevelStage);
        bus.addListener(InputEvent.MouseScrollingEvent.class, SplineEditorController::onMouseScroll);
        bus.addListener(MovementInputUpdateEvent.class, SplineEditorController::onMovementInputUpdate);
        Log.info(TAG, "[SplineEditor] Controller registered");
    }

    /**
     * Resets input tracking states (e.g. key press transitions) when entering or exiting edit mode.
     */
    public static void resetInputState() {
        wasEscapeDown = false;
        wasGDown = false;
        wasDeleteDown = false;
        cameraActive = false;
        skipFrames = 0;
        topDownWasGrabbed = false;
        wasCameraActive = false;
    }

    static void onClientTickPost(ClientTickEvent.Post event) {
        if (!SplineEditorClientState.isEditing()) return;

        // 面板隐藏时暂停样条编辑器输入（不再锁移动/吞输入），恢复时继续
        if (WandscapePanelState.isPanelHidden()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        long window = mc.getWindow().getWindow();
        double[] mx = new double[1], my = new double[1];
        GLFW.glfwGetCursorPos(window, mx, my);

        boolean uiWantsMouse = com.wsteam.wandscape.road.client.modernui.RoadStudioModernUI.isMouseOverUI(mx[0], my[0]);
        boolean uiWantsKb = com.wsteam.wandscape.road.client.modernui.RoadStudioModernUI.isKeyboardFocused();

        mc.player.setDeltaMovement(Vec3.ZERO);

        // World clicks belong to the editor when the cursor is over the 3D world (and not dragging camera)
        if (!cameraActive && !uiWantsMouse && !isSplineGuideOpen(mc)) {
            if (RoadPlacementState.getActiveTool() == RoadPlacementState.ToolMode.SPLINE) {
                SplineEditorInputHandler.handleClicks(mc, window);
            } else {
                RoadPlacementController.handleMouseButtons(mc, window);
            }
        }

        if (cameraActive) {
            mc.player.setDeltaMovement(Vec3.ZERO);
        } else {
            // Lock movement when not rotating
            mc.player.setDeltaMovement(Vec3.ZERO);

            // World clicks belong to the editor when the cursor is over the 3D world
            if (!uiWantsMouse && !isSplineGuideOpen(mc)) {
                if (RoadPlacementState.getActiveTool() == RoadPlacementState.ToolMode.SPLINE) {
                    SplineEditorInputHandler.handleClicks(mc, window);
                } else {
                    RoadPlacementController.handleMouseButtons(mc, window);
                }
            }
        }

        // ── Keyboard shortcuts ──
        handleKeyboard(mc, window, uiWantsKb);

        // ── Drain vanilla inputs ──
        if (!uiWantsKb) {
            drainVanillaInput(mc);
        }
    }

    private static long lastFrameNanos = 0;
    private static boolean wasCameraActive = false;

    static void onRenderLevelStage(net.neoforged.neoforge.client.event.RenderLevelStageEvent event) {
        if (!SplineEditorClientState.isEditing()) return;
        if (event.getStage() != net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_SKY) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (mc.getWindow() == null) return;
        long window = mc.getWindow().getWindow();

        // Frame-time delta
        long now = System.nanoTime();
        if (lastFrameNanos == 0) lastFrameNanos = now;
        double elapsed = (now - lastFrameNanos) / 1_000_000_000.0;
        lastFrameNanos = now;
        if (elapsed > 0.05) elapsed = 0.05;

        double[] mx = new double[1], my = new double[1];
        GLFW.glfwGetCursorPos(window, mx, my);

        boolean uiWantsMouse = com.wsteam.wandscape.road.client.modernui.RoadStudioModernUI.isMouseOverUI(mx[0], my[0]);
        boolean uiWantsKb = com.wsteam.wandscape.road.client.modernui.RoadStudioModernUI.isKeyboardFocused();
        boolean rightDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;

        // Top-down camera control
        if (SplineEditorClientState.isTopDown()) {
            handleTopDownCamera(mc, window, elapsed, mx[0], my[0], rightDown, uiWantsMouse, uiWantsKb);
            return;
        }

        // 3D freecam mouse look (Unity-style latched camera drag)
        if (!cameraActive) {
            // Latch ON: only activate when RMB is pressed down outside UI
            if (rightDown && !uiWantsMouse && !isSplineGuideOpen(mc)) {
                cameraActive = true;
                skipFrames = 2;
                SplineEditorClientState.setLastMouse(mx[0], my[0]);
                GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
            } else {
                SplineEditorClientState.setLastMouse(mx[0], my[0]);
            }
        } else {
            // Latch OFF: only deactivate when RMB is released
            if (!rightDown) {
                cameraActive = false;
                GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
                SplineEditorClientState.setLastMouse(mx[0], my[0]);
            } else {
                // While RMB remains held, mouse delta rotates camera infinitely across screen bounds!
                double dx = mx[0] - SplineEditorClientState.getLastMouseX();
                double dy = my[0] - SplineEditorClientState.getLastMouseY();

                if (skipFrames > 0) {
                    skipFrames--;
                } else {
                    SplineEditorClientState.addCamRotation((float) dx * 0.15f, (float) dy * 0.15f);
                }
                SplineEditorClientState.setLastMouse(mx[0], my[0]);
            }
        }

        // WASD flight movement
        if (!uiWantsKb) {
            float forward = 0, strafe = 0, vertical = 0;
            if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_W) == GLFW.GLFW_PRESS) forward += 1;
            if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_S) == GLFW.GLFW_PRESS) forward -= 1;
            if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_A) == GLFW.GLFW_PRESS) strafe -= 1;
            if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_D) == GLFW.GLFW_PRESS) strafe += 1;
            if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_SPACE) == GLFW.GLFW_PRESS) vertical += 1;
            if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
                    || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS) vertical -= 1;

            if (forward != 0 || strafe != 0 || vertical != 0) {
                Vec3 fwd = Vec3.directionFromRotation(SplineEditorClientState.getCamPitch(), SplineEditorClientState.getCamYaw());
                Vec3 right = Vec3.directionFromRotation(0, SplineEditorClientState.getCamYaw()).cross(new Vec3(0, 1, 0)).normalize();

                double move = com.wsteam.wandscape.Config.FLY_SPEED.get() * elapsed;
                double moveX = (fwd.x * forward + right.x * strafe) * move;
                double moveZ = (fwd.z * forward + right.z * strafe) * move;
                double moveY = (fwd.y * forward + vertical) * move;

                SplineEditorClientState.setCamPosition(
                        SplineEditorClientState.getCamX() + moveX,
                        SplineEditorClientState.getCamY() + moveY,
                        SplineEditorClientState.getCamZ() + moveZ
                );
            }
        }
    }

    /**
     * Top-down (bird's eye) camera, mirroring the V-panel overview interaction.
     */
    private static void handleTopDownCamera(Minecraft mc, long window, double elapsed, double curX, double curY, boolean rightDown, boolean uiWantsMouse, boolean uiWantsKb) {
        if (!topDownWasGrabbed) {
            if (rightDown && !uiWantsMouse) {
                topDownWasGrabbed = true;
                skipFrames = 2;
                SplineEditorClientState.setLastMouse(curX, curY);
                GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
            } else {
                SplineEditorClientState.setLastMouse(curX, curY);
            }
        } else {
            if (!rightDown) {
                topDownWasGrabbed = false;
                GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
                SplineEditorClientState.setLastMouse(curX, curY);
            } else {
                double dx = curX - SplineEditorClientState.getLastMouseX();
                double dy = curY - SplineEditorClientState.getLastMouseY();

                if (skipFrames > 0) {
                    skipFrames--;
                } else {
                    SplineEditorClientState.addCamRotation((float) dx * 0.15f, (float) dy * 0.15f);
                }
                SplineEditorClientState.setLastMouse(curX, curY);
            }
        }

        if (uiWantsKb) return;

        float forward = 0, strafe = 0, vertical = 0;
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_W) == GLFW.GLFW_PRESS) forward += 1;
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_S) == GLFW.GLFW_PRESS) forward -= 1;
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_A) == GLFW.GLFW_PRESS) strafe -= 1;
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_D) == GLFW.GLFW_PRESS) strafe += 1;
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_SPACE) == GLFW.GLFW_PRESS) vertical += 1;
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS) vertical -= 1;

        if (forward != 0 || strafe != 0 || vertical != 0) {
            Vec3 fwd = Vec3.directionFromRotation(0, SplineEditorClientState.getCamYaw());
            Vec3 right = fwd.cross(new Vec3(0, 1, 0)).normalize();
            double move = com.wsteam.wandscape.Config.FLY_SPEED.get() * elapsed;
            double moveX = (fwd.x * forward + right.x * strafe) * move;
            double moveZ = (fwd.z * forward + right.z * strafe) * move;
            double moveY = vertical * move;
            SplineEditorClientState.setCamPosition(
                    SplineEditorClientState.getCamX() + moveX,
                    SplineEditorClientState.getCamY() + moveY,
                    SplineEditorClientState.getCamZ() + moveZ);
        }
    }

    static void onMovementInputUpdate(MovementInputUpdateEvent event) {
        if (!SplineEditorClientState.isEditing()) return;
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

    static void onMouseScroll(net.neoforged.neoforge.client.event.InputEvent.MouseScrollingEvent event) {
        if (!SplineEditorClientState.isEditing()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getWindow() == null) return;
        long window = mc.getWindow().getWindow();
        double[] mx = new double[1], my = new double[1];
        GLFW.glfwGetCursorPos(window, mx, my);
        if (com.wsteam.wandscape.road.client.modernui.RoadStudioModernUI.isMouseOverUI(mx[0], my[0])) return;

        event.setCanceled(true);
        double delta = event.getScrollDeltaY();
        if (delta == 0) return;

        // 滚轮统一沿视线方向移动（俯视=垂直缩放，3D=前进/后退）。不调速——
        // 飞行速度固定与 V 面板鸟瞰一致。
        Vec3 dir = Vec3.directionFromRotation(
                SplineEditorClientState.getCamPitch(), SplineEditorClientState.getCamYaw());
        double move = delta * 4.0;
        SplineEditorClientState.setCamPosition(
                SplineEditorClientState.getCamX() + dir.x * move,
                SplineEditorClientState.getCamY() + dir.y * move,
                SplineEditorClientState.getCamZ() + dir.z * move);
    }

    private static boolean wasHelpDown = false;

    private static void handleKeyboard(Minecraft mc, long window, boolean uiWantsKb) {
        boolean escDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_ESCAPE) == GLFW.GLFW_PRESS;
        if (escDown && !wasEscapeDown) {
            if (!cameraActive) {
                // If a guide screen is open, ESC closes it first (stays in editor)
                if (isSplineGuideOpen(mc)) {
                    mc.setScreen(null);
                } else {
                    // Exit editor mode
                    SplineEditorClientState.exitEditMode();
                    com.wsteam.wandscape.road.client.modernui.RoadStudioModernUI.close();
                }
            }
        }
        wasEscapeDown = escDown;

        // H key (GUIDE_TOGGLE): toggle spline guide document
        if (!uiWantsKb && !cameraActive) {
            int hKey = com.wsteam.wandscape.WandscapeClient.GUIDE_TOGGLE.getKey().getValue();
            boolean helpDown = GLFW.glfwGetKey(window, hKey) == GLFW.GLFW_PRESS;
            if (helpDown && !wasHelpDown) {
                if (isSplineGuideOpen(mc)) {
                    // Toggle off: close the guide, stay in the editor
                    mc.setScreen(null);
                    Log.info(TAG, "[SplineEditor] Guide closed (H toggle)");
                } else {
                    String content = com.wsteam.wandscape.shared.ui.markdown.navigation.DocumentLoader.loadMarkdown("road_spline_guide");
                    mc.setScreen(new com.wsteam.wandscape.shared.ui.guide.GuideTestScreen(null, content, "road_spline_guide"));
                }
            }
            wasHelpDown = helpDown;
        }

        // G key: toggle top-down (bird's eye) view — same as the V-panel overview mode
        if (!uiWantsKb && !cameraActive) {
            boolean gDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_G) == GLFW.GLFW_PRESS;
            if (gDown && !wasGDown) {
                if (SplineEditorClientState.isTopDown()) {
                    SplineEditorClientState.exitTopDown();
                    if (mc.player != null) {
                        mc.player.displayClientMessage(
                                net.minecraft.network.chat.Component.literal("[SplineEditor] §7Top-down view off — G to re-enable"), true);
                    }
                } else {
                    SplineEditorClientState.enterTopDown();
                    if (mc.player != null) {
                        mc.player.displayClientMessage(
                                net.minecraft.network.chat.Component.literal(
                                        "[SplineEditor] §aTop-down view on — RMB drag rotate, WASD pan, Scroll zoom, G to exit"), true);
                    }
                }
            }
            wasGDown = gDown;
        }

        // Shortcut for deleting points (Only when ModernUI / ImGui is not focusing typing)
        if (!uiWantsKb && !cameraActive) {
            boolean delDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_DELETE) == GLFW.GLFW_PRESS
                    || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_BACKSPACE) == GLFW.GLFW_PRESS;
            boolean delClicked = delDown && !wasDeleteDown;
            wasDeleteDown = delDown;
            if (delClicked) {
                int selected = SplineEditorClientState.getSelectedPointIndex();
                if (selected != -1) {
                    SplineEditorClientState.getModel().removePoint(selected);
                    int after = SplineEditorClientState.getModel().getPoints().size();
                    if (after > 0) {
                        // 像栈一样：删除后自动选中上一个点；删的是最后一个则选中新的末尾
                        int nextIdx = Math.min(selected, after - 1);
                        SplineEditorClientState.setSelectedPoint(nextIdx, SplineEditorClientState.SelectionType.ANCHOR);
                    } else {
                        SplineEditorClientState.setSelectedPoint(-1, SplineEditorClientState.SelectionType.NONE);
                    }
                }
            }
        }
    }

    private static void drainVanillaInput(Minecraft mc) {
        while (mc.options.keyAttack.consumeClick()) {}
        while (mc.options.keyUse.consumeClick()) {}
        while (mc.options.keyJump.consumeClick()) {}
        while (mc.options.keyShift.consumeClick()) {}
        while (mc.options.keyInventory.consumeClick()) {}
        while (mc.options.keyDrop.consumeClick()) {}
        while (mc.options.keySprint.consumeClick()) {}
    }

    /**
     * True if a spline guide document screen is currently open (the one we
     * opened from the editor). GuideTestScreen carries its document path in a
     * history stack; expose a way to check it without touching internals.
     */
    private static boolean isSplineGuideOpen(Minecraft mc) {
        if (mc.screen == null) return false;
        if (!(mc.screen instanceof com.wsteam.wandscape.shared.ui.guide.GuideTestScreen guide)) return false;
        return guide.isShowingDocument("road_spline_guide");
    }

    public static void doBuildArray() {
        com.wsteam.wandscape.road.core.SplineModel model = SplineEditorClientState.getModel();
        com.wsteam.wandscape.road.core.RoadTemplate activeTemplate = SplineEditorClientState.getActiveTemplate();
        double stepDistance = SplineEditorClientState.getArrayStepDistance();

        if (model.getPoints().isEmpty() || activeTemplate == null || activeTemplate.getBlocks().isEmpty()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("§cCannot build: empty model or template"), true);
            }
            return;
        }

        java.util.List<com.wsteam.wandscape.road.core.CurveSample> samples = model.tessellate(stepDistance);
        if (samples.isEmpty()) return;

        java.util.Map<net.minecraft.core.BlockPos, String> uniqueTiles = new java.util.LinkedHashMap<>();

        com.google.gson.JsonArray splineJson = new com.google.gson.JsonArray();
        for (com.wsteam.wandscape.road.core.SplinePoint pt : model.getPoints()) {
            com.google.gson.JsonObject ptObj = new com.google.gson.JsonObject();
            com.google.gson.JsonArray a = new com.google.gson.JsonArray();
            a.add(pt.getAnchor().x()); a.add(pt.getAnchor().y()); a.add(pt.getAnchor().z());
            com.google.gson.JsonArray p = new com.google.gson.JsonArray();
            p.add(pt.getControlPrev().x()); p.add(pt.getControlPrev().y()); p.add(pt.getControlPrev().z());
            com.google.gson.JsonArray n = new com.google.gson.JsonArray();
            n.add(pt.getControlNext().x()); n.add(pt.getControlNext().y()); n.add(pt.getControlNext().z());
            
            ptObj.add("a", a);
            ptObj.add("p", p);
            ptObj.add("n", n);
            ptObj.addProperty("l", pt.isLocked());
            splineJson.add(ptObj);
        }

        for (com.wsteam.wandscape.road.core.CurveSample sample : samples) {
            SplineVec3 pos = sample.position();
            
            SplineVec3 tan = sample.tangent();
            org.joml.Vector3f forward = new org.joml.Vector3f((float)tan.x(), (float)tan.y(), (float)tan.z()).normalize();
            org.joml.Vector3f right = new org.joml.Vector3f(0, 1, 0).cross(forward);
            if (right.lengthSquared() < 0.0001f) {
                right.set(1, 0, 0).cross(forward);
            }
            right.normalize();
            org.joml.Vector3f up = new org.joml.Vector3f(forward).cross(right).normalize();

            org.joml.Matrix4f m = new org.joml.Matrix4f();
            m.set(
                    right.x, right.y, right.z, 0f,
                    up.x,    up.y,    up.z,    0f,
                    forward.x, forward.y, forward.z, 0f,
                    (float)pos.x(),   (float)pos.y(),   (float)pos.z(),   1f
            );

            float roll = (float) Math.toRadians(SplineEditorClientState.getArrayOffsetRoll());
            float pitch = (float) Math.toRadians(SplineEditorClientState.getArrayOffsetPitch());
            float yaw = (float) Math.toRadians(SplineEditorClientState.getArrayOffsetYaw());

            if (roll != 0) m.rotateLocalZ(roll);
            if (pitch != 0) m.rotateLocalX(pitch);
            if (yaw != 0) m.rotateLocalY(yaw);

            for (com.wsteam.wandscape.road.core.RoadTemplate.RoadTemplateBlock b : activeTemplate.getBlocks()) {
                org.joml.Vector4f localPos = new org.joml.Vector4f((float)b.x() + 0.5f, (float)b.y(), (float)b.z() + 0.5f, 1.0f);
                localPos.mul(m);

                int wx = (int) Math.floor(localPos.x);
                int wy = (int) Math.floor(localPos.y);
                int wz = (int) Math.floor(localPos.z);

                uniqueTiles.put(new net.minecraft.core.BlockPos(wx, wy, wz), b.blockState());
            }
        }

        com.google.gson.JsonArray tiles = new com.google.gson.JsonArray();
        for (java.util.Map.Entry<net.minecraft.core.BlockPos, String> entry : uniqueTiles.entrySet()) {
            net.minecraft.core.BlockPos bp = entry.getKey();
            com.google.gson.JsonObject tile = new com.google.gson.JsonObject();
            com.google.gson.JsonArray posArr = new com.google.gson.JsonArray();
            posArr.add(bp.getX());
            posArr.add(bp.getY());
            posArr.add(bp.getZ());
            tile.add("pos", posArr);
            tile.addProperty("block", entry.getValue());
            tiles.add(tile);
        }
        
        if (tiles.isEmpty()) return;

        net.neoforged.neoforge.network.PacketDistributor.sendToServer(new com.wsteam.wandscape.road.network.SplineBuildPacket(tiles.toString(), splineJson.toString()));

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("§aSent build task with " + tiles.size() + " blocks and " + splineJson.size() + " spline points!"), true);
        }
    }
}
