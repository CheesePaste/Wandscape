package com.wsteam.wandscape.overview.client;

import java.util.UUID;

import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import com.wsteam.wandscape.engine.service.SoundService;
import com.wsteam.wandscape.engine.sound.WandscapeSounds;
import com.wsteam.wandscape.overview.network.OverviewEntityInteractPacket;
import com.wsteam.wandscape.overview.network.OverviewInteractPacket;
import com.wsteam.wandscape.projection.client.ProjectionClientState;
import com.wsteam.wandscape.projection.client.ProjectionFlightController;
import com.wsteam.wandscape.road.client.RoadPlacementState;
import com.wsteam.wandscape.shared.network.BuildingAreaSyncPacket;
import com.wsteam.wandscape.shared.log.Log;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.network.chat.Component;
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
 * mouse look, raycasting, and right-click building interaction.
 * Camera position/rotation is overridden by {@code MixinOverviewCamera}'s TAIL inject into
 * {@link Camera#setup}.</p>
 */
public final class OverviewFlightController {

    private static final String TAG = "OverviewFlightController";
    private static final double REACH = 64.0;
    private static double flyingSpeed = 10.0;     // blocks per second
    private static final double SCROLL_SPEED = 4.0;
    private static final float MOUSE_SENSITIVITY = 0.15f;

    private static boolean registered = false;

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
        // 采样血量作受伤检测基线（玩家模型渲染由 MixinOverviewCamera 强制 detached 处理）
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

        // Only rotate when no screen open and cursor not lifted to panel
        if (mc.screen == null) {
            boolean cursorLifted = com.wsteam.wandscape.shared.ui.panel.WandscapePanelState.isPanelOpen()
                    && com.wsteam.wandscape.shared.ui.panel.WandscapePanelState.isCursorLifted();
            boolean grabbed = !cursorLifted;

            // Detect cursor transition: free → grabbed. The cursor is "free"
            // whenever a Screen is open or the panel cursor is lifted.
            // grabMouse() re-centers the cursor on re-grab; without resetting
            // the baseline the delta from the old free position to center
            // causes a sudden camera rotation.
            if (!wasGrabbed && grabbed) {
                OverviewClientState.lastMouseX = mx[0];
                OverviewClientState.lastMouseY = my[0];
                dx = 0;
                dy = 0;
            }
            wasGrabbed = grabbed;

            if (grabbed) {
                OverviewClientState.addCamRotation((float) dx * MOUSE_SENSITIVITY, (float) dy * MOUSE_SENSITIVITY);
            }
        } else {
            // Screen is open → cursor is free
            wasGrabbed = false;
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
            Vec3 fwd = Vec3.directionFromRotation(0, OverviewClientState.getCamYaw());
            Vec3 right = fwd.cross(new Vec3(0, 1, 0)).normalize();
            double move = flyingSpeed * elapsed;
            double moveX = (fwd.x * forward + right.x * strafe) * move;
            double moveZ = (fwd.z * forward + right.z * strafe) * move;
            double moveY = vertical * move;
            OverviewClientState.setCamPosition(
                    OverviewClientState.getCamX() + moveX,
                    OverviewClientState.getCamY() + moveY,
                    OverviewClientState.getCamZ() + moveZ);
        }

        // 冻结玩家旋转到进入快照（AFTER_SKY 早于实体渲染，时机正好）：
        // 抵消 MouseHandler.turnPlayer 污染 + 稳定第三人称玩家模型朝向
        freezePlayerRotation(mc.player);
    }

    // ═══════════════════════════════════════════════════════════════════
    // ── Client Tick: raycast + right-click + input drain ──
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

            // Pinned ghost: left-click cancels (back to hand-following), right-click opens the construction screen
            if (ProjectionClientState.isPinned()) {
                if (leftClicked) {
                    ProjectionClientState.setPinned(false);
                } else if (rightClicked) {
                    ProjectionFlightController.openConstructionScreen(mc);
                }
            } else {
                // Left-click: rotate building 90° CCW (only when build mode is active)
                if (leftClicked && ProjectionClientState.isProjecting()) {
                    ProjectionClientState.rotate();
                    int steps = ProjectionClientState.getRotationSteps();
                    String direction = switch (steps) {
                        case 1 -> "§e90°";
                        case 2 -> "§e180°";
                        case 3 -> "§e270°";
                        default -> "§70°";
                    };
                    Log.info(TAG, "[Overview] Rotation: {}", direction);
                }

                if (rightClicked) {
                    handleRightClick();
                }
            }
        }

        // ── Drain all vanilla actions ──
        drainVanillaInput(mc);
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

        long window = mc.getWindow().getWindow();
        boolean ctrlDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;

        if (ctrlDown) {
            float factor = (float) Math.pow(1.3, delta);
            flyingSpeed = Math.max(1.0, Math.min(100.0, flyingSpeed * factor));
            Log.info(TAG, "[Overview] Speed: %.1f", flyingSpeed);
        } else {
            Vec3 dir = Vec3.directionFromRotation(
                    OverviewClientState.getCamPitch(), OverviewClientState.getCamYaw());
            double move = delta * SCROLL_SPEED;
            OverviewClientState.setCamPosition(
                    OverviewClientState.getCamX() + dir.x * move,
                    OverviewClientState.getCamY() + dir.y * move,
                    OverviewClientState.getCamZ() + dir.z * move);
        }
    }

    /** Intercept mouse buttons — but only when no screen is open. */
    static void onMouseButtonPre(InputEvent.MouseButton.Pre event) {
        if (!OverviewClientState.isActive()) return;
        Minecraft mc = Minecraft.getInstance();
        // Don't block mouse clicks when a screen is open (allow UI interaction)
        if (mc.screen != null) return;
        // Cancel all mouse buttons in overview mode
        // Right-click is handled in ClientTickEvent.Post instead
        event.setCanceled(true);
    }

    // ═══════════════════════════════════════════════════════════════════
    // ── Raycast ──
    // ═══════════════════════════════════════════════════════════════════

    private static void performRaycast(Minecraft mc) {
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 origin = camera.getPosition();
        Vector3f look = camera.getLookVector();
        Vec3 lookVec = new Vec3(look.x(), look.y(), look.z());
        Vec3 end = origin.add(lookVec.scale(REACH));

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

            // When build mode is projecting in overview, sync ghost from the same raycast
            if (ProjectionClientState.isProjecting()) {
                if (ProjectionClientState.isPinned()) {
                    // Ghost stays fixed — only re-check overlap against the fixed position
                    BlockPos fixed = ProjectionClientState.getGhostPos();
                    if (fixed != null) {
                        var api = com.wsteam.wandscape.shared.registry.WandscapeApis.getBuildingApi();
                        ProjectionClientState.setOverlapDetected(api != null && api.getBuildingAt(fixed) != null);
                    }
                } else {
                    BlockPos placePos = hitPos.relative(blockHit.getDirection());
                    ProjectionClientState.setGhostPos(placePos);
                    var api = com.wsteam.wandscape.shared.registry.WandscapeApis.getBuildingApi();
                    boolean overlap = api != null && api.getBuildingAt(placePos) != null;
                    ProjectionClientState.setOverlapDetected(overlap);
                }
            }
        } else {
            OverviewClientState.clearTarget();
            OverviewClientState.clearTargetEntity();
            if (ProjectionClientState.isProjecting() && !ProjectionClientState.isPinned()) {
                ProjectionClientState.setGhostPos(null);
                ProjectionClientState.setOverlapDetected(false);
            }
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
    // ── Right-click handling ──
    // ═══════════════════════════════════════════════════════════════════

    private static void handleRightClick() {
        // Branch 1: Building selected via Build bar → pin the ghost preview
        if (ProjectionClientState.isProjecting()) {
            pinGhost();
            return;
        }

        // Branch 2: Entity under crosshair → interact (NPC / tourist)
        int entityId = OverviewClientState.getTargetEntityId();
        if (entityId >= 0) {
            PacketDistributor.sendToServer(new OverviewEntityInteractPacket(entityId));
            Log.info(TAG, "[Overview] Interacting with entity id={}", entityId);
            return;
        }

        // Branch 3: No building selected, ray hits building → interact
        BlockPos target = OverviewClientState.getTargetBlockPos();
        if (target != null && OverviewClientState.getTargetBuildingId() != null) {
            PacketDistributor.sendToServer(new OverviewInteractPacket(target));
            Log.info(TAG, "[Overview] Interacting with building at {}", target);
        }
    }

    /** Pin the selected building's ghost at the current overview raycast position
     *  and open the construction screen. */
    private static void pinGhost() {
        Minecraft mc = Minecraft.getInstance();
        BlockPos ghostPos = ProjectionClientState.getGhostPos();
        if (ghostPos == null) {
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        Component.literal("[Overview] §c").append(
                                com.wsteam.wandscape.shared.ui.I18n.name(
                                        "message.wandscape.overview.cannot_pin",
                                        "无法固定 — 准星没有对准方块")), true);
            }
            return;
        }
        ProjectionClientState.setPinned(true);
        ProjectionFlightController.openConstructionScreen(mc);
        Log.info(TAG, "[Overview] Ghost pinned at {}", ghostPos);
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
