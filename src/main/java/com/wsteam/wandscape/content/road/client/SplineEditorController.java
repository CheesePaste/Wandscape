package com.wsteam.wandscape.content.road.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.wsteam.wandscape.content.road.client.studio.RoadStudioOverlay;
import com.wsteam.wandscape.content.road.core.CurveSample;
import com.wsteam.wandscape.content.road.core.RoadTemplate;
import com.wsteam.wandscape.content.road.core.SplineModel;
import com.wsteam.wandscape.content.road.core.SplinePoint;
import com.wsteam.wandscape.content.road.network.SplineBuildPacket;
import com.wsteam.wandscape.content.road.core.SplineVec3;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.ui.panel.WandscapePanelState;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import org.lwjgl.glfw.GLFW;

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
    private static boolean registered = false;

    private SplineEditorController() {}

    /** True while the player is holding RMB to rotate the editor camera (cursor grabbed). */
    public static boolean isCameraActive() {
        return cameraActive;
    }

    public static void register() {
        if (registered) return;
        registered = true;
        var bus = net.neoforged.neoforge.common.NeoForge.EVENT_BUS;
        bus.addListener(ClientTickEvent.Post.class, SplineEditorController::onClientTickPost);
        bus.addListener(net.neoforged.neoforge.client.event.RenderLevelStageEvent.class, SplineEditorController::onRenderLevelStage);
        bus.addListener(net.neoforged.neoforge.client.event.InputEvent.MouseScrollingEvent.class, SplineEditorController::onMouseScroll);
        bus.addListener(InputEvent.MouseButton.Pre.class, SplineEditorController::onMouseButtonPre);
        bus.addListener(MovementInputUpdateEvent.class, SplineEditorController::onMovementInputUpdate);
        Log.info(TAG, "[SplineEditor] Controller registered");
    }

    private static double savedCursorX = 0, savedCursorY = 0;
    private static boolean hasSavedCursor = false;

    public static void resetInputState() {
        cameraActive = false;
        skipFrames = 0;
        wasEscapeDown = false;
        wasHelpDown = false;
        wasGDown = false;
        wasDeleteDown = false;
        topDownWasGrabbed = false;
        hasSavedCursor = false;
    }

    /**
     * Unity-style right-drag camera: pressing RMB over the world grabs the
     * cursor (vanilla look rotation takes over); releasing RMB restores the
     * free cursor so the player can click the studio panel or the world.
     * Left-clicking in the 3D viewport or panel NEVER auto-captures the mouse.
     */
    static void onMouseButtonPre(InputEvent.MouseButton.Pre event) {
        if (!SplineEditorClientState.isEditing() && !RoadStudioOverlay.isVisible()) return;
        if (Minecraft.getInstance().screen != null) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.mouseHandler == null) return;

        boolean uiWantsMouse = RoadEditorInputHelper.wantsMouse();
        long window = mc.getWindow().getWindow();
        int button = event.getButton();
        int action = event.getAction();

        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            if (action == GLFW.GLFW_PRESS) {
                // If over panel, don't grab camera
                if (!cameraActive && !uiWantsMouse) {
                    double[] mx = new double[1], my = new double[1];
                    GLFW.glfwGetCursorPos(window, mx, my);
                    savedCursorX = mx[0];
                    savedCursorY = my[0];
                    hasSavedCursor = true;

                    cameraActive = true;
                    mc.mouseHandler.grabMouse();
                }
            } else if (cameraActive && action == GLFW.GLFW_RELEASE) {
                cameraActive = false;
                mc.mouseHandler.releaseMouse();
                if (hasSavedCursor) {
                    GLFW.glfwSetCursorPos(window, savedCursorX, savedCursorY);
                }
            }
            event.setCanceled(true);
            return;
        }

        // Left mouse button: immediate viewport interaction
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if (!uiWantsMouse) {
                if (RoadPlacementState.getActiveTool() == RoadPlacementState.ToolMode.SPLINE) {
                    if (action == GLFW.GLFW_PRESS) {
                        SplineEditorInputHandler.onLeftPress(mc);
                    } else if (action == GLFW.GLFW_RELEASE) {
                        SplineEditorInputHandler.onLeftRelease(mc);
                    }
                } else {
                    if (action == GLFW.GLFW_PRESS) {
                        RoadPlacementController.onLeftPress(mc);
                    } else if (action == GLFW.GLFW_RELEASE) {
                        RoadPlacementController.onLeftRelease(mc);
                    }
                }
            }
            event.setCanceled(true);
            return;
        }

        // Cancel all other mouse clicks in editor mode to prevent vanilla block attack/use
        event.setCanceled(true);
    }

    static void onClientTickPost(ClientTickEvent.Post event) {
        if (!SplineEditorClientState.isEditing() && !RoadStudioOverlay.isVisible()) return;

        // 面板隐藏时暂停样条编辑器输入（不再锁移动/吞输入），恢复时继续
        if (WandscapePanelState.isPanelHidden()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        long window = mc.getWindow().getWindow();
        boolean rightDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
        boolean uiWantsKb = RoadEditorInputHelper.wantsKeyboard();
        boolean uiWantsMouse = RoadEditorInputHelper.wantsMouse();

        // Track cursor position while free so we have a reliable restore point
        if (!cameraActive && mc.screen == null && !mc.mouseHandler.isMouseGrabbed()) {
            double[] mx = new double[1], my = new double[1];
            GLFW.glfwGetCursorPos(window, mx, my);
            savedCursorX = mx[0];
            savedCursorY = my[0];
            hasSavedCursor = true;
        }

        // ── Right-click camera rotation fallback ──
        if (!cameraActive && rightDown && !uiWantsMouse && mc.screen == null) {
            double[] mx = new double[1], my = new double[1];
            GLFW.glfwGetCursorPos(window, mx, my);
            savedCursorX = mx[0];
            savedCursorY = my[0];
            hasSavedCursor = true;

            cameraActive = true;
            mc.mouseHandler.grabMouse();
        } else if (cameraActive && !rightDown) {
            cameraActive = false;
            mc.mouseHandler.releaseMouse();
            if (hasSavedCursor) {
                GLFW.glfwSetCursorPos(window, savedCursorX, savedCursorY);
            }
        }

        // Defensive: while camera is not active and no screen is open, ensure mouse cursor stays released (free)
        if (!cameraActive && mc.screen == null && mc.mouseHandler.isMouseGrabbed()) {
            mc.mouseHandler.releaseMouse();
            if (hasSavedCursor) {
                GLFW.glfwSetCursorPos(window, savedCursorX, savedCursorY);
            }
        }

        // Defensive: if dragging but LMB is not down, finish drag
        if (SplineEditorClientState.isDragging() && GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS) {
            SplineEditorInputHandler.onLeftRelease(mc);
        }
        if (RoadPlacementState.isDraggingGizmo() && GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS) {
            RoadPlacementController.onLeftRelease(mc);
        }

        if (cameraActive) {
            // Flight movement while holding right-click
            if (uiWantsKb) {
                mc.player.setDeltaMovement(Vec3.ZERO);
            }
        } else {
            // Lock movement when not rotating
            mc.player.setDeltaMovement(Vec3.ZERO);
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
        long window = mc.getWindow().getWindow();

        // Frame-time delta
        long now = System.nanoTime();
        if (lastFrameNanos == 0) lastFrameNanos = now;
        double elapsed = (now - lastFrameNanos) / 1_000_000_000.0;
        lastFrameNanos = now;
        if (elapsed > 0.05) elapsed = 0.05;

        boolean uiWantsKb = RoadEditorInputHelper.wantsKeyboard();
        boolean uiWantsMouse = RoadEditorInputHelper.wantsMouse();

        // 3D Gizmo hover & drag at full frame rate (60-144+ FPS)
        if (!cameraActive && !uiWantsMouse && mc.screen == null) {
            Vec3 rayOrigin = mc.gameRenderer.getMainCamera().getPosition();
            Vec3 rayDir = SplineEditorInputHandler.getMouseWorldRay(mc);
            if (RoadPlacementState.getActiveTool() == RoadPlacementState.ToolMode.SPLINE) {
                if (SplineEditorClientState.isDragging()) {
                    SplineEditorInputHandler.continueGizmoDrag(mc, rayOrigin, rayDir);
                } else {
                    SplineEditorInputHandler.updateGizmoHover(rayOrigin, rayDir);
                }
            } else {
                if (RoadPlacementState.isDraggingGizmo()) {
                    RoadPlacementController.continueGizmoDrag(mc, rayOrigin, rayDir);
                } else {
                    RoadPlacementController.updateGizmoHover(rayOrigin, rayDir);
                }
            }
        }

        // Top-down camera control
        if (SplineEditorClientState.isTopDown()) {
            handleTopDownCamera(mc, window, elapsed, uiWantsKb);
            return;
        }

        // 3D freecam mouse look while holding right-click
        if (cameraActive) {
            double[] mx = new double[1], my = new double[1];
            GLFW.glfwGetCursorPos(window, mx, my);
            if (!wasCameraActive) {
                SplineEditorClientState.setLastMouse(mx[0], my[0]);
                wasCameraActive = true;
                skipFrames = 2;
            }
            double dx = mx[0] - SplineEditorClientState.getLastMouseX();
            double dy = my[0] - SplineEditorClientState.getLastMouseY();

            if (skipFrames > 0) {
                skipFrames--;
                SplineEditorClientState.setLastMouse(mx[0], my[0]);
            } else {
                SplineEditorClientState.addCamRotation((float) dx * 0.15f, (float) dy * 0.15f);
                SplineEditorClientState.setLastMouse(mx[0], my[0]);
            }
        } else {
            wasCameraActive = false;
            skipFrames = 0;
        }

        if (mc.screen != null || uiWantsKb) return;

        float forward = 0, strafe = 0, vertical = 0;
        if (isKeyDown(mc.options.keyUp, window)) forward += 1;
        if (isKeyDown(mc.options.keyDown, window)) forward -= 1;
        if (isKeyDown(mc.options.keyLeft, window)) strafe -= 1;
        if (isKeyDown(mc.options.keyRight, window)) strafe += 1;
        if (isKeyDown(mc.options.keyJump, window)) vertical += 1;
        if (isKeyDown(mc.options.keyShift, window)) vertical -= 1;

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

    private static void handleTopDownCamera(Minecraft mc, long window, double elapsed, boolean uiWantsKb) {
        double[] mx = new double[1], my = new double[1];
        GLFW.glfwGetCursorPos(window, mx, my);

        boolean rightDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;

        if (!topDownWasGrabbed && rightDown) {
            SplineEditorClientState.setLastMouse(mx[0], my[0]);
        }
        topDownWasGrabbed = rightDown;

        if (rightDown) {
            double dx = mx[0] - SplineEditorClientState.getLastMouseX();
            double dy = my[0] - SplineEditorClientState.getLastMouseY();
            SplineEditorClientState.addCamRotation((float) dx * 0.15f, (float) dy * 0.15f);
        }
        SplineEditorClientState.setLastMouse(mx[0], my[0]);

        if (mc.screen != null || uiWantsKb) return;

        float forward = 0, strafe = 0, vertical = 0;
        if (isKeyDown(mc.options.keyUp, window)) forward += 1;
        if (isKeyDown(mc.options.keyDown, window)) forward -= 1;
        if (isKeyDown(mc.options.keyLeft, window)) strafe -= 1;
        if (isKeyDown(mc.options.keyRight, window)) strafe += 1;
        if (isKeyDown(mc.options.keyJump, window)) vertical += 1;
        if (isKeyDown(mc.options.keyShift, window)) vertical -= 1;

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
        if (RoadEditorInputHelper.wantsMouse()) return;

        event.setCanceled(true);
        double delta = event.getScrollDeltaY();
        if (delta == 0) return;

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
                    if (RoadStudioOverlay.isVisible()) {
                        RoadStudioOverlay.close();
                    } else {
                        SplineEditorClientState.exitEditMode();
                    }
                }
            }
        }
        wasEscapeDown = escDown;

        // H key: toggle spline guide document
        if (!uiWantsKb && !cameraActive) {
            int hKey = com.wsteam.wandscape.WandscapeClient.GUIDE_TOGGLE.getKey().getValue();
            boolean helpDown = GLFW.glfwGetKey(window, hKey) == GLFW.GLFW_PRESS;
            if (helpDown && !wasHelpDown) {
                if (isSplineGuideOpen(mc)) {
                    mc.setScreen(null);
                    Log.info(TAG, "[SplineEditor] Guide closed (H toggle)");
                } else {
                    String content = com.wsteam.wandscape.shared.ui.markdown.navigation.DocumentLoader.loadMarkdown("road_spline_guide");
                    mc.setScreen(new com.wsteam.wandscape.shared.ui.guide.GuideTestScreen(null, content, "road_spline_guide"));
                }
            }
            wasHelpDown = helpDown;
        }

        // G key: toggle top-down view
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

        // Delete / Backspace: remove selected point
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

    private static boolean isSplineGuideOpen(Minecraft mc) {
        if (mc.screen == null) return false;
        if (!(mc.screen instanceof com.wsteam.wandscape.shared.ui.guide.GuideTestScreen guide)) return false;
        return guide.isShowingDocument("road_spline_guide");
    }

    public static void doBuildArray() {
        SplineModel model = SplineEditorClientState.getModel();
        RoadTemplate activeTemplate = SplineEditorClientState.getActiveTemplate();
        double stepDistance = SplineEditorClientState.getArrayStepDistance();

        if (model.getPoints().isEmpty() || activeTemplate == null || activeTemplate.getBlocks().isEmpty()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("§cCannot build: empty model or template"), true);
            }
            return;
        }

        java.util.List<CurveSample> samples = model.tessellate(stepDistance);
        if (samples.isEmpty()) return;

        java.util.Map<net.minecraft.core.BlockPos, String> uniqueTiles = new java.util.LinkedHashMap<>();

        com.google.gson.JsonArray splineJson = new com.google.gson.JsonArray();
        for (SplinePoint pt : model.getPoints()) {
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

        for (CurveSample sample : samples) {
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

            for (RoadTemplate.RoadTemplateBlock b : activeTemplate.getBlocks()) {
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

        net.neoforged.neoforge.network.PacketDistributor.sendToServer(new SplineBuildPacket(tiles.toString(), splineJson.toString()));

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("§aSent build task with " + tiles.size() + " blocks and " + splineJson.size() + " spline points!"), true);
        }
    }

    private static boolean isKeyDown(KeyMapping mapping, long window) {
        if (mapping == null) return false;
        if (mapping.isDown()) return true;
        InputConstants.Key key = mapping.getKey();
        if (key.getType() == InputConstants.Type.KEYSYM && key.getValue() != InputConstants.UNKNOWN.getValue()) {
            return GLFW.glfwGetKey(window, key.getValue()) == GLFW.GLFW_PRESS;
        }
        if (key.getType() == InputConstants.Type.MOUSE && key.getValue() != InputConstants.UNKNOWN.getValue()) {
            return GLFW.glfwGetMouseButton(window, key.getValue()) == GLFW.GLFW_PRESS;
        }
        return false;
    }
}
