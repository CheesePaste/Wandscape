package com.wsteam.wandscape.overview.client;

import java.util.UUID;

import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import com.wsteam.wandscape.engine.service.SoundService;
import com.wsteam.wandscape.engine.sound.WandscapeSounds;
import com.wsteam.wandscape.overview.network.OverviewEntityInteractPacket;
import com.wsteam.wandscape.overview.network.OverviewInteractPacket;
import com.wsteam.wandscape.projection.client.ProjectionClientState;
import com.wsteam.wandscape.road.client.RoadPlacementState;
import com.wsteam.wandscape.shared.network.BuildingAreaSyncPacket;
import com.wsteam.wandscape.shared.log.Log;

import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Per-frame controller for overview (bird's eye) mode.
 *
 * <p>Handles WASD movement (in render event for smooth frame-rate-independent motion),
 * mouse look, raycasting, and left-click NPC/building interaction.
 * Camera position/rotation is overridden by {@code MixinOverviewCamera}'s TAIL inject into
 * {@link Camera#setup}.</p>
 */
public final class OverviewFlightController {

    private static final String TAG = "OverviewFlightController";
    private static final double REACH = 64.0;
    /** 滚轮缩放步长（格/格），飞行速度见 Config.panel.flySpeed。 */
    private static final double SCROLL_SPEED = 4.0;
    private static final float MOUSE_SENSITIVITY = 0.15f;
    /**
     * 单帧鼠标位移阈值（像素）。超过即视为光标被 OS / 对账器强制 warp
     * （grabMouse 把光标甩到窗口中心、release 后 setCursorPos 回位）。
     * 疯狂右键连点时 tick 与渲染帧先后顺序随机：若按下后的首帧先于 tick，
     * 边沿检测的 skipFrames 被提前消费，warp 的跳变 delta 会在下一帧被当成
     * 正常旋转 → 镜头猛转。阈值兜底：正常甩动单帧不可能超过，warp 必然超过，
     * 命中即丢弃该帧 delta 并重置基线。
     */
    private static final double MOUSE_JUMP_THRESHOLD = 100.0;

    private static boolean registered = false;

    /** 进入前玩家的相机类型，退出时恢复（渲染玩家实体用第三人称）。 */
    private static CameraType prevCameraType = null;
    /** 受伤检测的血量基线：进入时采样，下降沿触发自动退出。 */
    private static float lastHealth = 0f;

    // ── Input edge detection ──
    private static boolean wasLeftDown = false;
    private static boolean wasRightDown = false;
    /** True while a {@code Screen} (e.g. the Construction UI) is open — used to baseline
     *  button edge-detection when it closes so a UI click isn't re-read as a world click. */
    private static boolean wasScreenOpen = false;
    /**
     * Tracks whether the cursor was in "grabbed" state last frame.
     * Cursor is "free" when a Screen is open or the panel cursor is lifted.
     * When transitioning from free → grabbed, grabMouse() re-centers the
     * cursor, so the mouse baseline must be reset to prevent a camera jump.
     */
    private static boolean wasGrabbed = false;
    private static int skipFrames = 0;
    private static double rmbDragDistance = 0.0;

    // ── Frame-time tracking for smooth movement ──
    private static long lastFrameNanos = 0;

    private OverviewFlightController() {}

    // ── Registration ──

    public static void register() {
        if (registered) return;
        registered = true;

        var bus = NeoForge.EVENT_BUS;
        bus.addListener(RenderLevelStageEvent.class, OverviewFlightController::onRenderLevelStage);
        bus.addListener(ClientTickEvent.Post.class, OverviewFlightController::onClientTickPost);
        bus.addListener(MovementInputUpdateEvent.class, OverviewFlightController::onMovementInputUpdate);
        bus.addListener(InputEvent.MouseScrollingEvent.class, OverviewFlightController::onMouseScroll);
        bus.addListener(InputEvent.MouseButton.Pre.class, OverviewFlightController::onMouseButtonPre);
        Log.info(TAG, "Overview flight controller registered");
    }

    // ═══════════════════════════════════════════════════════════════════
    // ── Activation / Deactivation ──
    // ═══════════════════════════════════════════════════════════════════

    public static void enter() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        OverviewClientState.enterOverview(
                mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                mc.player.getYRot(), mc.player.getXRot());
        // 切第三人称以渲染玩家实体；存原相机类型供 exit 恢复；采样血量作受伤检测基线
        prevCameraType = mc.options.getCameraType();
        mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        lastHealth = mc.player.getHealth();
        SoundService.playUI(WandscapeSounds.OVERVIEW_ENTER, 1.0f);
        // Initialize last mouse position to current cursor
        long window = mc.getWindow().getWindow();
        double[] mx = new double[1], my = new double[1];
        GLFW.glfwGetCursorPos(window, mx, my);
        OverviewClientState.lastMouseX = mx[0];
        OverviewClientState.lastMouseY = my[0];
        lastFrameNanos = System.nanoTime();
        wasGrabbed = false;
    }

    public static void exit() {
        Minecraft mc = Minecraft.getInstance();
        // 恢复原相机类型
        if (prevCameraType != null) {
            mc.options.setCameraType(prevCameraType);
            prevCameraType = null;
        }
        // 显式落定玩家旋转到进入快照：每帧冻结只在 active 时跑，退出瞬间 active 已落，
        // 残留的鼠标漂移会让视角「甩头」，故在此强制写回快照值
        if (mc.player != null) {
            freezePlayerRotation(mc.player);
        }
        OverviewClientState.exitOverview();
        lastFrameNanos = 0;
        wasGrabbed = false;
    }

    /**
     * 把玩家旋转强制冻回进入空中视角时的快照。抵消原版 {@code MouseHandler.turnPlayer}
     * 对玩家真实旋转的每帧污染（光标 grabbed 时原版仍 turn 玩家）。必须同步 yBodyRot/yHeadRot——
     * LivingEntityRenderer 用 yBodyRot 画身体，而 yBodyRot 在 tickHeadTurn 以 30%/tick 跟随 yRot，
     * 只冻 yRot 会让第三人称模型随鼠标抽搐。
     */
    private static void freezePlayerRotation(LivingEntity player) {
        float yaw = OverviewClientState.getPrevYaw();
        float pitch = OverviewClientState.getPrevPitch();
        player.setYRot(yaw);
        player.yRotO = yaw;
        player.yBodyRot = yaw;
        player.yBodyRotO = yaw;
        player.yHeadRot = yaw;
        player.yHeadRotO = yaw;
        player.setXRot(pitch);
        player.xRotO = pitch;
    }

    // ═══════════════════════════════════════════════════════════════════
    // ── Render-Level Stage: camera movement + mouse look ──
    // ═══════════════════════════════════════════════════════════════════

    static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (!OverviewClientState.isActive()) return;
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        // 每帧把相机类型拉回第三人称：F5 在 handleKeybinds（早于 ClientTickEvent.Post）就已
        // consume 并 cycle，drain 无效，必须用 reconcile 才能稳住「渲染玩家实体」
        if (mc.options.getCameraType() != CameraType.THIRD_PERSON_BACK) {
            mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        }

        long window = mc.getWindow().getWindow();

        // ── Frame-time delta (capped to prevent jumps when tabbing back) ──
        long now = System.nanoTime();
        double elapsed = (now - lastFrameNanos) / 1_000_000_000.0;
        lastFrameNanos = now;
        if (elapsed > 0.05) elapsed = 0.05;

        // ── Mouse look ──
        double[] mx = new double[1], my = new double[1];
        GLFW.glfwGetCursorPos(window, mx, my);
        double dx = mx[0] - OverviewClientState.lastMouseX;
        double dy = my[0] - OverviewClientState.lastMouseY;

        // Only rotate when no screen open and cursor not lifted to panel (or holding RMB)
        if (mc.screen == null) {
            boolean cursorLifted = com.wsteam.wandscape.shared.ui.panel.WandscapePanelState.isPanelOpen()
                    && com.wsteam.wandscape.shared.ui.panel.WandscapePanelState.isCursorLifted();
            boolean rightDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
            boolean grabbed = !cursorLifted || rightDown;

            if (rightDown) {
                rmbDragDistance += Math.abs(dx) + Math.abs(dy);
            }

            // Detect cursor transition: free → grabbed. The cursor is "free"
            // whenever a Screen is open or the panel cursor is lifted.
            // grabMouse() re-centers the cursor on re-grab; without resetting
            // the baseline the delta from the old free position to center
            // causes a sudden camera rotation.
            if (!wasGrabbed && grabbed) {
                skipFrames = 2;
                OverviewClientState.lastMouseX = mx[0];
                OverviewClientState.lastMouseY = my[0];
                dx = 0;
                dy = 0;
            } else if (skipFrames > 0) {
                skipFrames--;
                OverviewClientState.lastMouseX = mx[0];
                OverviewClientState.lastMouseY = my[0];
                dx = 0;
                dy = 0;
            }
            wasGrabbed = grabbed;

            if (grabbed && skipFrames == 0) {
                // 兜底：单帧位移超阈值 = 光标被强制 warp（grab/release 过渡的时序竞态），
                // 丢弃该帧 delta 并重置基线，下一帧即恢复。正常旋转不会触发。
                if (Math.abs(dx) > MOUSE_JUMP_THRESHOLD || Math.abs(dy) > MOUSE_JUMP_THRESHOLD) {
                    OverviewClientState.lastMouseX = mx[0];
                    OverviewClientState.lastMouseY = my[0];
                } else {
                    OverviewClientState.addCamRotation((float) dx * MOUSE_SENSITIVITY, (float) dy * MOUSE_SENSITIVITY);
                }
            }
        } else {
            // Screen is open → cursor is free
            wasGrabbed = false;
            skipFrames = 0;
        }

        OverviewClientState.lastMouseX = mx[0];
        OverviewClientState.lastMouseY = my[0];

        // ── WASD + Shift/Space movement (frame-rate independent, render-smooth) ──
        float forward = 0, strafe = 0, vertical = 0;
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_W) == GLFW.GLFW_PRESS) forward += 1;
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_S) == GLFW.GLFW_PRESS) forward -= 1;
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_A) == GLFW.GLFW_PRESS) strafe -= 1;
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_D) == GLFW.GLFW_PRESS) strafe += 1;
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_SPACE) == GLFW.GLFW_PRESS) vertical += 1;
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS) vertical -= 1;

        if (forward != 0 || strafe != 0 || vertical != 0) {
            // Full 3D look direction (pitch + yaw) so W flies upward when looking up
            Vec3 fwd = Vec3.directionFromRotation(OverviewClientState.getCamPitch(), OverviewClientState.getCamYaw());
            Vec3 right = fwd.cross(new Vec3(0, 1, 0)).normalize();
            Vec3 up = right.cross(fwd).normalize();
            double move = com.wsteam.wandscape.Config.FLY_SPEED.get() * elapsed;
            double moveX = (fwd.x * forward + right.x * strafe + up.x * vertical) * move;
            double moveY = (fwd.y * forward + right.y * strafe + up.y * vertical) * move;
            double moveZ = (fwd.z * forward + right.z * strafe + up.z * vertical) * move;
            OverviewClientState.setCamPosition(
                    OverviewClientState.getCamX() + moveX,
                    OverviewClientState.getCamY() + moveY,
                    OverviewClientState.getCamZ() + moveZ);
        }

        // ── Update building ghost position every render frame (not just 20Hz tick) ──
        updateGhostPositionPerFrame(mc);

        // 冻结玩家旋转到进入快照（AFTER_SKY 早于实体渲染，时机正好）：
        // 抵消 MouseHandler.turnPlayer 污染 + 稳定第三人称玩家模型朝向
        freezePlayerRotation(mc.player);
    }

    /**
     * Per-frame ghost position update using camera center raycast.
     * Runs at render FPS (60+) instead of tick rate (20Hz) for responsive tracking.
     */
    private static void updateGhostPositionPerFrame(Minecraft mc) {
        if (!ProjectionClientState.isProjecting()) return;
        if (ProjectionClientState.isPinned()) return;

        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 origin = camera.getPosition();
        Vector3f jLook = camera.getLookVector();
        Vec3 centerLookVec = new Vec3(jLook.x(), jLook.y(), jLook.z());
        Vec3 centerEnd = origin.add(centerLookVec.scale(REACH));
        ClipContext centerCtx = new ClipContext(origin, centerEnd, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player);
        BlockHitResult centerHit = mc.level.clip(centerCtx);

        long window = mc.getWindow().getWindow();
        boolean rightDown = window != 0L && GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;

        if (centerHit.getType() == HitResult.Type.BLOCK) {
            BlockPos centerPlacePos = centerHit.getBlockPos().relative(centerHit.getDirection());
            if (rightDown || ProjectionClientState.getGhostPos() == null) {
                ProjectionClientState.setGhostPos(ProjectionClientState.centerAnchor(centerPlacePos));
            }
        }

        BlockPos curGhost = ProjectionClientState.getGhostPos();
        if (curGhost != null) {
            var api = com.wsteam.wandscape.shared.registry.WandscapeApis.getBuildingApi();
            boolean overlap = api != null && api.getBuildingAt(curGhost) != null;
            ProjectionClientState.setOverlapDetected(overlap);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // ── Client Tick: raycast + click handling + input drain ──
    // ═══════════════════════════════════════════════════════════════════

    static void onClientTickPost(ClientTickEvent.Post event) {
        if (!OverviewClientState.isActive()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        // 受伤/死亡 → 完全退出控制面板（保留空中相机缓存），回原版第一人称夺回操控
        if (mc.player.isDeadOrDying()) {
            com.wsteam.wandscape.shared.ui.panel.WandscapePanelState.closePanel();
            return;
        }
        float health = mc.player.getHealth();
        if (health < lastHealth) {
            Log.info(TAG, "[Overview] Player took damage ({} → {}), exiting control panel", lastHealth, health);
            com.wsteam.wandscape.shared.ui.panel.WandscapePanelState.closePanel();
            lastHealth = health;
            return;
        }
        lastHealth = health;

        long window = mc.getWindow().getWindow();

        // Same screen-close baseline as ProjectionFlightController: a left-click that
        // closes the Construction UI must not re-appear as a fresh world left-click.
        boolean screenOpen = mc.screen != null;
        if (wasScreenOpen && !screenOpen) {
            wasLeftDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
            wasRightDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
        }
        wasScreenOpen = screenOpen;
        if (screenOpen) return;

        // ── Raycast from camera (also syncs ghost position when build is projecting) ──
        performRaycast(mc);

        // ── Click handling (skip when road mode is active — road controller handles it) ──
        if (!RoadPlacementState.isProjecting()) {
            boolean leftDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
            boolean rightDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;

            boolean leftClicked = leftDown && !wasLeftDown;
            boolean rightClicked = rightDown && !wasRightDown;
            wasLeftDown = leftDown;
            wasRightDown = rightDown;

            if (rightClicked) {
                rmbDragDistance = 0.0;
            }

            // Build submode (projecting): left-click rotates the building — works pinned or not.
            // Skip when aiming at a gizmo axis (drag) or clicking any panel/bar/sidebar UI region,
            // so a UI click doesn't accidentally rotate the ghost.
            if (ProjectionClientState.isProjecting()) {
                if (leftClicked && !isOverGizmo() && !isOverBuildUi(mc)) {
                    ProjectionClientState.rotate();
                }
            } else {
                // 常态（OVERVIEW/NONE，无子模式）+ 游戏层抓取：准心右键交互建筑/NPC。
                // Build/Road/Stats 子模式内不做建筑/NPC 交互（目标是建建筑不是交互）。
                var sub = com.wsteam.wandscape.shared.ui.panel.WandscapePanelState.getActiveSubMode();
                boolean normalState = sub == com.wsteam.wandscape.shared.ui.panel.WandscapePanelState.SubMode.OVERVIEW
                        || sub == com.wsteam.wandscape.shared.ui.panel.WandscapePanelState.SubMode.NONE;
                boolean grabbed = !com.wsteam.wandscape.shared.ui.panel.WandscapePanelState.isCursorLifted();
                if (normalState && grabbed && rightClicked) {
                    handleTargetInteraction();
                }
            }
        }

        // ── Drain all vanilla actions ──
        drainVanillaInput(mc);
    }

    /** Whether the cursor is hovering or dragging a gizmo axis — that click belongs to the gizmo. */
    private static boolean isOverGizmo() {
        com.wsteam.wandscape.projection.client.BuildGizmoController.AxisDrag hovered =
                com.wsteam.wandscape.projection.client.BuildGizmoController.getHoveredAxis();
        com.wsteam.wandscape.projection.client.BuildGizmoController.AxisDrag dragging =
                com.wsteam.wandscape.projection.client.BuildGizmoController.getDraggingAxis();
        return hovered != com.wsteam.wandscape.projection.client.BuildGizmoController.AxisDrag.NONE
                || dragging != com.wsteam.wandscape.projection.client.BuildGizmoController.AxisDrag.NONE;
    }

    /**
     * Whether the mouse cursor is over a BUILD-mode UI region (right pop panel, building bar,
     * sidebar, or top bar). Clicks there must not rotate the ghost.
     */
    private static boolean isOverBuildUi(Minecraft mc) {
        double guiScale = mc.getWindow().getGuiScale();
        double mouseX = mc.mouseHandler.xpos() / guiScale;
        double mouseY = mc.mouseHandler.ypos() / guiScale;
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();

        if (com.wsteam.wandscape.projection.client.BuildPopPanelOverlay.isOverPanel(mouseX, mouseY, screenW)) return true;
        if (com.wsteam.wandscape.shared.ui.panel.BuildingSelectionOverlay.isActive()) {
            int barY = com.wsteam.wandscape.shared.ui.panel.BuildingSelectionOverlay.getBarY(screenH);
            if (mouseY >= barY && mouseY <= barY + com.wsteam.wandscape.shared.ui.panel.BuildingSelectionOverlay.BAR_HEIGHT) {
                return true;
            }
        }
        return com.wsteam.wandscape.shared.ui.panel.WandscapePanelController.isInTopBar(mouseY, screenH)
                || com.wsteam.wandscape.shared.ui.panel.WandscapePanelController.isInSidebar(mouseX, mouseY, screenH);
    }

    // ═══════════════════════════════════════════════════════════════════
    // ── Input arbitration ──
    // ═══════════════════════════════════════════════════════════════════

    /** Prevent player movement — camera is independent. */
    static void onMovementInputUpdate(MovementInputUpdateEvent event) {
        if (!OverviewClientState.isActive()) return;
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

    /** Scroll → move camera along look direction (or let road mode handle width change). */
    static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        if (!OverviewClientState.isActive()) return;
        Minecraft mc = Minecraft.getInstance();
        // Don't block scroll when a screen is open (allow UI scrolling)
        if (mc.screen != null) return;
        
        event.setCanceled(true);
        double delta = event.getScrollDeltaY();
        if (delta == 0) return;

        // 滚轮沿视线方向移动（缩放）。飞行速度固定走 Config.panel.flySpeed，不在此调速。
        Vec3 dir = Vec3.directionFromRotation(
                OverviewClientState.getCamPitch(), OverviewClientState.getCamYaw());
        double move = delta * SCROLL_SPEED;
        OverviewClientState.setCamPosition(
                OverviewClientState.getCamX() + dir.x * move,
                OverviewClientState.getCamY() + dir.y * move,
                OverviewClientState.getCamZ() + dir.z * move);
    }

    private static Vec3 getMouseWorldRay(Minecraft mc) {
        long window = mc.getWindow().getWindow();
        double[] mx = new double[1], my = new double[1];
        GLFW.glfwGetCursorPos(window, mx, my);
        int w = mc.getWindow().getWidth();
        int h = mc.getWindow().getHeight();

        float ndcX = (float) (2.0 * mx[0] / w - 1.0);
        float ndcY = (float) (1.0 - 2.0 * my[0] / h);

        Camera cam = mc.gameRenderer.getMainCamera();
        float fov = (float) mc.options.fov().get();
        float fovRad = (float) Math.toRadians(fov);
        float aspect = (float) w / Math.max(h, 1);
        float tanHalfFov = (float) Math.tan(fovRad * 0.5f);

        Vector3f jLook = cam.getLookVector();
        Vector3f jUp   = cam.getUpVector();
        Vector3f jLeft = cam.getLeftVector();

        Vec3 forward = new Vec3(jLook.x, jLook.y, jLook.z);
        Vec3 up      = new Vec3(jUp.x,   jUp.y,   jUp.z);
        Vec3 right   = new Vec3(jLeft.x, jLeft.y, jLeft.z).scale(-1.0);

        return forward
                .add(right.scale(ndcX * tanHalfFov * aspect))
                .add(up.scale(ndcY * tanHalfFov))
                .normalize();
    }

    /** Intercept mouse buttons — but only when no screen or UI panel is taking input. */
    static void onMouseButtonPre(InputEvent.MouseButton.Pre event) {
        if (!OverviewClientState.isActive()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) return;

        boolean imguiWantsMouse = com.wsteam.wandscape.imgui.ImGuiManager.isInitialized()
                && imgui.ImGui.getIO().getWantCaptureMouse();
        if (imguiWantsMouse) return;

        double guiScale = mc.getWindow().getGuiScale();
        double mouseX = mc.mouseHandler.xpos() / guiScale;
        double mouseY = mc.mouseHandler.ypos() / guiScale;
        int screenH = mc.getWindow().getGuiScaledHeight();
        if (com.wsteam.wandscape.shared.ui.panel.WandscapePanelState.isPanelOpen()
                && (com.wsteam.wandscape.shared.ui.panel.WandscapePanelController.isInTopBar(mouseY, screenH)
                || com.wsteam.wandscape.shared.ui.panel.WandscapePanelController.isInSidebar(mouseX, mouseY, screenH))) {
            return;
        }

        event.setCanceled(true);
    }

    // ═══════════════════════════════════════════════════════════════════
    // ── Raycast ──
    // ═══════════════════════════════════════════════════════════════════

    private static void performRaycast(Minecraft mc) {
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 origin = camera.getPosition();
        // 常态（抓取）用准心（相机中心线）；自由光标（子模式/Tab 抬起）用鼠标射线。
        boolean cursorLifted = com.wsteam.wandscape.shared.ui.panel.WandscapePanelState.isPanelOpen()
                && com.wsteam.wandscape.shared.ui.panel.WandscapePanelState.isCursorLifted();
        Vec3 rayDir = cursorLifted
                ? getMouseWorldRay(mc)
                : new Vec3(camera.getLookVector().x(), camera.getLookVector().y(), camera.getLookVector().z());
        Vec3 end = origin.add(rayDir.scale(REACH));

        // ── Block raycast ──
        ClipContext ctx = new ClipContext(
                origin, end,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player);
        BlockHitResult blockHit = mc.level.clip(ctx);
        double blockDist = blockHit.getType() == HitResult.Type.BLOCK
                ? blockHit.getLocation().distanceToSqr(origin) : Double.MAX_VALUE;

        // ── Entity raycast (NPC: mage + tourist) ──
        AABB searchBox = new AABB(origin, end).inflate(2.0);
        java.util.List<net.minecraft.world.entity.Entity> entities = mc.level.getEntities(
                mc.player, searchBox,
                e -> (e instanceof com.wsteam.wandscape.npc.entity.WandscapeNpc
                        || e instanceof com.wsteam.wandscape.tourist.entity.TouristEntity)
                        && e.isAlive());

        double entityDist = Double.MAX_VALUE;
        int hitEntityId = -1;
        for (net.minecraft.world.entity.Entity entity : entities) {
            AABB bb = entity.getBoundingBox().inflate(0.3);
            java.util.Optional<Vec3> hitPos = bb.clip(origin, end);
            if (hitPos.isPresent()) {
                double dist = hitPos.get().distanceToSqr(origin);
                if (dist < entityDist) {
                    entityDist = dist;
                    hitEntityId = entity.getId();
                }
            }
        }

        // ── Pick closer target ──
        if (entityDist < blockDist && hitEntityId >= 0) {
            OverviewClientState.setTargetEntity(hitEntityId);
            if (ProjectionClientState.isProjecting() && !ProjectionClientState.isPinned()) {
                ProjectionClientState.setGhostPos(null);
                ProjectionClientState.setOverlapDetected(false);
            }
        } else if (blockHit.getType() == HitResult.Type.BLOCK) {
            BlockPos hitPos = blockHit.getBlockPos();
            UUID buildingId = findBuildingAt(hitPos);
            OverviewClientState.setTarget(hitPos, buildingId);

            // When build mode is projecting in overview, check overlap for pinned ghost
            // (Ghost position update is handled per-frame in updateGhostPositionPerFrame)
            if (ProjectionClientState.isProjecting() && ProjectionClientState.isPinned()) {
                BlockPos fixed = ProjectionClientState.getGhostPos();
                if (fixed != null) {
                    var api = com.wsteam.wandscape.shared.registry.WandscapeApis.getBuildingApi();
                    ProjectionClientState.setOverlapDetected(api != null && api.getBuildingAt(fixed) != null);
                }
            }
        } else {
            OverviewClientState.clearTarget();
            OverviewClientState.clearTargetEntity();
        }
    }

    /**
     * Find which building (if any) contains the given block position.
     * Uses the shared boundary lookup over cached building area data.
     */
    private static UUID findBuildingAt(BlockPos pos) {
        return BuildingAreaSyncPacket.findBuildingIdAt(pos);
    }

    // ═══════════════════════════════════════════════════════════════════
    // ── Target interaction ──
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 与当前准心下的实体/建筑交互（发送交互包，服务端打开对应 GUI）。
     * 仅常态（OVERVIEW/NONE + 抓取）右键触发；子模式内不做交互。
     */
    private static void handleTargetInteraction() {
        // Entity under crosshair → interact (NPC / tourist)
        int entityId = OverviewClientState.getTargetEntityId();
        if (entityId >= 0) {
            PacketDistributor.sendToServer(new OverviewEntityInteractPacket(entityId));
            Log.info(TAG, "[Overview] Interacting with entity id={}", entityId);
            return;
        }

        // Ray hits building → interact
        BlockPos target = OverviewClientState.getTargetBlockPos();
        if (target != null && OverviewClientState.getTargetBuildingId() != null) {
            PacketDistributor.sendToServer(new OverviewInteractPacket(target));
            Log.info(TAG, "[Overview] Interacting with building at {}", target);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // ── Input draining ──
    // ═══════════════════════════════════════════════════════════════════

    private static void drainVanillaInput(Minecraft mc) {
        while (mc.options.keyAttack.consumeClick()) {}
        while (mc.options.keyUse.consumeClick()) {}
        while (mc.options.keyJump.consumeClick()) {}
        while (mc.options.keyShift.consumeClick()) {}
        while (mc.options.keyInventory.consumeClick()) {}
        while (mc.options.keyDrop.consumeClick()) {}
        while (mc.options.keySprint.consumeClick()) {}
    }
}
