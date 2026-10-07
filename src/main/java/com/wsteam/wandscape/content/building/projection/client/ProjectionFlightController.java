package com.wsteam.wandscape.content.building.projection.client;
import com.wsteam.wandscape.content.task.component.Position;
import com.wsteam.wandscape.content.task.component.NpcInventory;
import com.wsteam.wandscape.foundation.networking.Net;
import com.wsteam.wandscape.foundation.ui.panel.WandscapePanelController;

import com.wsteam.wandscape.content.building.data.BuildingConfig;
import com.wsteam.wandscape.content.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.content.colony.overview.client.OverviewClientState;
import com.wsteam.wandscape.content.building.projection.BuildPlacement;
import com.wsteam.wandscape.content.building.projection.data.BuildingSlot;
import com.wsteam.wandscape.content.building.projection.network.ProjectionExitPacket;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.content.building.network.BuildingAreaSyncPacket;
import com.wsteam.wandscape.foundation.ui.panel.WandscapePanelState;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Per-tick input handler for ground-based building placement mode.
 *
 * <p><b>瞄准阶段</b>：虚影每 tick 跟随准心，不需要按任何键。
 * <b>左键</b>旋转 90 度；<b>ALT+滚轮</b>沿「视线最近的轴」微调 1 格，并把虚影「定位」到锚点
 * （此后不再跟随准心）；<b>Enter</b> 与面板阶段按钮推进/回退阶段（瞄准 → 确认位置 → 定稿 → 重新瞄准）；
 * <b>右键</b>打开施工屏（精确坐标 / 提交）。对照 Litematica 的 placement：固定锚点 + 沿视线轴 nudge
 * + 快捷键旋转（这里用左键，避免与 JEI/EMI 的 R 配方键冲突）。
 * Movement is blocked globally by WandscapePanelController when the cursor is lifted.
 */
public final class ProjectionFlightController {

    private static final String TAG = "ProjectionFlightController";

    /** Extended reach distance in projection mode (blocks). */
    private static final double PROJECTION_REACH = 512.0;

    /** ALT+滚轮：一次微调至少要攒够的刻度（高分辨率滚轮的小 delta 先累加，凑够一格才动）。 */
    private static final double SCROLL_STEP_MIN = 0.6;
    /** ALT+滚轮：两次微调之间的最小间隔，挡住「一个刻度被驱动连发多次」导致的连跳。 */
    private static final long SCROLL_NUDGE_COOLDOWN_MS = 100L;
    /**
     * 视线判轴时「上下明显压过水平」的倍数门槛（1.15 ≈ 俯仰角 49°）。
     *
     * <p>存在的理由：空中俯瞰默认俯角 45°，此时上下与水平分量正好相等——不设门槛的话平局永远判成
     * 「向下」，面朝方向就完全用不上（实测反馈）。设了门槛后，45° 的默认视角按面朝方向微调，
     * 要上下挪就按住 ALT 把视角抬/压得更陡一点。
     */
    private static final float VERTICAL_DOMINANCE = 1.15f;
    private static double scrollAccum;
    private static long lastNudgeMs;

    // ── Input edge detection state ──
    private static boolean wasLeftDown = false;
    private static boolean wasRightDown = false;
    private static boolean wasEscapeDown = false;
    private static boolean wasScreenOpen = false;

    private static boolean registered = false;

    private ProjectionFlightController() {}

    // ── Registration ──

    public static void register() {
        if (registered) return;
        registered = true;
        var bus = net.neoforged.neoforge.common.NeoForge.EVENT_BUS;
        bus.addListener(ClientTickEvent.Post.class, ProjectionFlightController::onClientTickPost);
        bus.addListener(InputEvent.MouseScrollingEvent.class, ProjectionFlightController::onMouseScroll);
        Log.info(TAG, "[Projection] Flight controller registered");
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Client tick ──
    // ═══════════════════════════════════════════════════════════════

    static void onClientTickPost(ClientTickEvent.Post event) {
        if (!ProjectionClientState.isProjecting()) return;

        // 面板隐藏时暂停建造输入（不更新幽灵/不吞输入），恢复时继续
        if (WandscapePanelState.isPanelHidden()) return;

        // Skip when overview mode is active — OverviewFlightController handles all input
        if (OverviewClientState.isActive()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        long window = mc.getWindow().getWindow();

        // Closing a Screen with the mouse (e.g. Construction UI buttons) would otherwise
        // re-appear as a fresh left-click on the next tick and cancel the pinned ghost.
        // Baseline the button edge-detection whenever a screen just closed.
        boolean screenOpen = mc.screen != null;
        if (wasScreenOpen && !screenOpen) {
            wasLeftDown = (window != 0L && GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS)
                    || mc.mouseHandler.isLeftPressed();
            wasRightDown = (window != 0L && GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS)
                    || mc.mouseHandler.isRightPressed();
        }
        wasScreenOpen = screenOpen;
        if (screenOpen) return;

        boolean buildingBarOpen = WandscapePanelState.isBuildingBarOpen();
        boolean cursorLifted = WandscapePanelState.isPanelOpen() && WandscapePanelState.isCursorLifted();

        // ── Building bar mode: no ghost, drain all input ──
        if (buildingBarOpen) {
            drainVanillaInput(mc);
            return;
        }

        if (cursorLifted) {
            drainVanillaInput(mc);
            return;
        }

        // ── Walking mode: ghost preview, handle clicks, drain only attack/use ──
        updateGhostPosition(mc);
        handleClicks(mc, window);
        handleEscape(mc, window);
        drainAttackUse(mc);
    }

    // ── Scroll wheel ──

    /** Handle mouse scroll via NeoForge's {@link InputEvent.MouseScrollingEvent}.
     *  ALT+滚轮：沿视线最近轴把锚点挪一格（对齐 Litematica 的 nudge）。普通滚轮不消费事件。 */
    static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        if (!ProjectionClientState.isProjecting() || !Screen.hasAltDown()) {
            scrollAccum = 0;   // 不在微调上下文里，别把余量带到下一段
            return;
        }
        double deltaY = event.getScrollDeltaY();
        if (deltaY == 0) return;
        event.setCanceled(true);

        // 最小灵敏度限制：高分辨率滚轮 / 触控板一次物理刻度会连发多个小 delta，
        // 逐事件动一格会「滚一下跳好几格」。
        long now = System.currentTimeMillis();
        if (now - lastNudgeMs < SCROLL_NUDGE_COOLDOWN_MS) {
            scrollAccum = 0;   // 冷却期内的输入整段丢弃：宁可少动一格，也不连跳
            return;
        }
        scrollAccum += deltaY;
        if (Math.abs(scrollAccum) < SCROLL_STEP_MIN) {
            return;            // 还没攒够一格刻度，先记着
        }
        int amount = scrollAccum > 0 ? 1 : -1;
        scrollAccum = 0;
        lastNudgeMs = now;
        nudgeGhost(Minecraft.getInstance(), amount);
    }

    /**
     * ALT+滚轮微调：沿「视线绝对值最大的那个轴」移动锚点 {@code amount} 格。
     * 等价 Litematica 的 {@code EntityWrap.getClosestLookingDirection}——抬头低头改 Y，
     * 平视朝哪边看就改对应的 X/Z。瞄准阶段微调会自动进入「调整中」；「已定稿」后拒绝。
     */
    private static void nudgeGhost(Minecraft mc, int amount) {
        BlockPos pos = ProjectionClientState.getGhostPos();
        if (pos == null) {
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        Component.literal("[Projection] §c").append(
                                com.wsteam.wandscape.foundation.ui.I18n.name(
                                        "message.wandscape.projection.no_target",
                                        "没有可施工的位置 — 先对准地面")), true);
            }
            return;
        }
        if (ProjectionClientState.isLocked()) {
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        Component.literal("[Projection] §6").append(
                                com.wsteam.wandscape.foundation.ui.I18n.name(
                                        "message.wandscape.projection.finalized",
                                        "已定稿 — 面板可「重新瞄准」或「提交施工」")), true);
            }
            return;
        }
        BlockPos moved = pos.relative(closestLookingDirection(mc), amount);
        ProjectionClientState.setPinned(true);
        ProjectionClientState.setGhostPos(moved);
        ProjectionClientState.setOverlapDetected(ProjectionClientState.currentSelectionConflicts(moved));
    }

    /**
     * 旋转待建建筑（左键 / 面板「旋转」按钮共用的唯一入口）。
     * 「已定稿」后拒绝：定稿的含义就是几何已确认，改朝向要先「重新瞄准」。
     */
    public static void rotateFromInput() {
        if (!ProjectionClientState.isProjecting()) return;
        if (ProjectionClientState.isLocked()) return;
        ProjectionClientState.rotate();
    }

    /**
     * 视线主导轴：比较相机视线三分量的绝对值取最大者（明显抬头/低头 → Y，其余 → X 或 Z）。
     *
     * <p>**平局优先水平**：空中俯瞰的默认俯角就是 45°，而 45° 时上下分量与水平分量恰好相等——
     * 若按"相等也算上下"处理，方向判定会永远是"向下"，ALT+滚轮只能上下挪（实测反馈）。
     * 所以上下要**明显**压过水平（{@value #VERTICAL_DOMINANCE} 倍）才算，否则按面朝方向走：
     * 判定本来的意思就是「面朝哪儿就往哪儿挪」。
     */
    private static net.minecraft.core.Direction closestLookingDirection(Minecraft mc) {
        var look = mc.gameRenderer.getMainCamera().getLookVector();
        float ax = Math.abs(look.x());
        float ay = Math.abs(look.y());
        float az = Math.abs(look.z());
        float horizontal = Math.max(ax, az);
        if (ay > horizontal * VERTICAL_DOMINANCE) {
            return look.y() >= 0 ? net.minecraft.core.Direction.UP : net.minecraft.core.Direction.DOWN;
        }
        return ax >= az ? (look.x() >= 0 ? net.minecraft.core.Direction.EAST : net.minecraft.core.Direction.WEST)
                : (look.z() >= 0 ? net.minecraft.core.Direction.SOUTH : net.minecraft.core.Direction.NORTH);
    }

    // ── Ghost position ──

    private static void updateGhostPosition(Minecraft mc) {
        // 已「定位」（pinned）：锚点不再跟随准心，只重算重叠；改 xyz 走 ALT+滚轮与面板微调按钮。
        if (ProjectionClientState.isPinned()) {
            BlockPos fixed = ProjectionClientState.getGhostPos();
            if (fixed != null) {
                ProjectionClientState.setOverlapDetected(ProjectionClientState.currentSelectionConflicts(fixed));
            }
            return;
        }

        // Perform a long-range raycast from camera center
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 origin = camera.getPosition();
        Vec3 lookVec = new Vec3(camera.getLookVector().x(),
                camera.getLookVector().y(),
                camera.getLookVector().z());

        // Use the MC level's clip for accuracy
        var clipCtx = new net.minecraft.world.level.ClipContext(
                origin,
                origin.add(lookVec.scale(PROJECTION_REACH)),
                net.minecraft.world.level.ClipContext.Block.OUTLINE,
                net.minecraft.world.level.ClipContext.Fluid.NONE,
                mc.player);
        BlockHitResult hit = mc.level.clip(clipCtx);

        if (hit.getType() == HitResult.Type.BLOCK) {
            // 命中草/花/蘑菇/树叶等不能立足的方块时，向下吸附到真正的地面
            // （草方块/泥土），避免建筑被植物垫高一层。
            BlockPos placePos = BuildPlacement.resolve(mc.level, hit.getBlockPos(), hit.getDirection());
            // 瞄准阶段：虚影始终跟随准心，不需要按任何键（Litematica 对齐后的手感改善点）。
            ProjectionClientState.setGhostPos(ProjectionClientState.centerAnchor(placePos));
        }

        BlockPos curGhost = ProjectionClientState.getGhostPos();
        if (curGhost != null) {
            boolean conflict = ProjectionClientState.currentSelectionConflicts(curGhost);
            ProjectionClientState.setOverlapDetected(conflict);
        }
    }

    // ── Click handling ──

    private static void handleClicks(Minecraft mc, long window) {
        boolean leftDown = (window != 0L && GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS)
                || mc.mouseHandler.isLeftPressed();
        boolean rightDown = (window != 0L && GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS)
                || mc.mouseHandler.isRightPressed();

        boolean leftClicked = leftDown && !wasLeftDown;
        boolean rightClicked = rightDown && !wasRightDown;
        wasLeftDown = leftDown;
        wasRightDown = rightDown;
        // 左键：旋转 90 度（与面板「旋转」按钮同一入口，定稿态会被 rotateFromInput 拒绝）
        if (leftClicked) {
            rotateFromInput();
        }
        // 右键：打开施工屏（精确坐标 / 提交）
        if (rightClicked) {
            BlockPos ghostPos = ProjectionClientState.getGhostPos();
            if (ghostPos == null) {
                if (mc.player != null) {
                    mc.player.displayClientMessage(
                            Component.literal("[Projection] §c").append(
                                    com.wsteam.wandscape.foundation.ui.I18n.name(
                                            "message.wandscape.projection.no_target",
                                            "没有可施工的位置 — 准星没有对准方块")), true);
                }
                return;
            }
            openConstructionScreen(mc);

        }
    }

    /** Open the construction screen for the ghost position (also used by overview mode). */
    public static void openConstructionScreen(Minecraft mc) {
        BlockPos pos = ProjectionClientState.getGhostPos();
        if (pos == null) return;

        var slots = ProjectionClientState.getBuildingSlots();
        int index = ProjectionClientState.getSelectedSlotIndex();
        if (slots.isEmpty() || index < 0 || index >= slots.size()) return;

        BuildingSlot slot = slots.get(index);
        BuildingConfig config = BuildingConfigLoader.getInstance().get(slot.id());
        if (config == null) return;

        mc.setScreen(new ConstructionScreen(config, slot.id(), pos,
                ProjectionClientState.getRotationSteps()));
    }

    // ── Escape ──

    private static void handleEscape(Minecraft mc, long window) {
        boolean escapeDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_ESCAPE) == GLFW.GLFW_PRESS;
        boolean escapeClicked = escapeDown && !wasEscapeDown;
        wasEscapeDown = escapeDown;

        if (!escapeClicked) return;

        // Panel not open → handle projection-level ESC. While the panel is open, ESC is
        // intercepted by WandscapePanelController's exit pipeline (ScreenEvent.Opening).
        if (!WandscapePanelState.isPanelOpen()) {
            // Pinned (gizmo phase): ESC first unpins back to aiming phase; next ESC exits.
            if (ProjectionClientState.isPinned()) {
                ProjectionClientState.setPinned(false);
                Log.info(TAG, "[Projection] Esc: unpinned ghost, staying in projection");
                return;
            }
            doExit();
        }
    }

    // ── Exit ──

    private static void doExit() {
        Net.toServer(new ProjectionExitPacket());
        ProjectionClientState.exitProjection();
    }

    // ── Input draining ──

    /** Drain all vanilla input — used when bar is open or cursor is lifted. */
    private static void drainVanillaInput(Minecraft mc) {
        while (mc.options.keyAttack.consumeClick()) {}
        while (mc.options.keyUse.consumeClick()) {}
        while (mc.options.keyJump.consumeClick()) {}
        while (mc.options.keyShift.consumeClick()) {}
        while (mc.options.keyInventory.consumeClick()) {}
        while (mc.options.keyDrop.consumeClick()) {}
        while (mc.options.keySprint.consumeClick()) {}
    }

    /** Drain only attack/use/inventory — player can walk normally. */
    private static void drainAttackUse(Minecraft mc) {
        while (mc.options.keyAttack.consumeClick()) {}
        while (mc.options.keyUse.consumeClick()) {}
        while (mc.options.keyInventory.consumeClick()) {}
    }
}
