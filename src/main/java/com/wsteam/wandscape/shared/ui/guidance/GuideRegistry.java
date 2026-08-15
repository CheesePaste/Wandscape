package com.wsteam.wandscape.shared.ui.guidance;

import java.util.List;

/**
 * Ordered onboarding steps (pure content — every text field is an i18n key resolved at
 * render time by {@link GuideRenderer}). Completion is evaluated server-side by
 * {@code GuideProgressService.computeStep} — the ORDER here MUST match the checks in that
 * method. Building names mirror the building-bar card names.
 *
 * <p>Keys live in {@code assets/wandscape/lang/en_us.json} (English) and {@code zh_cn.json}
 * (Chinese). Content conventions (accuracy-first, from the real interactions):
 * <ul>
 *   <li>Placement (build/road sub-modes) is taught as OPERATIONS, in order, no passive
 *       filler: 按住右键拖动 = 转视角（把建筑放到想要的位置）→ 左键 = 旋转朝向
 *       → 点右侧【提交施工】→ 施工界面【提交】. The important operations come first;
 *       the hint line only carries auxiliary info (WASD/scroll/building purpose).</li>
 *   <li>Building interaction happens in the V-panel OVERVIEW sub-mode, which is a free
 *       camera: 移动鼠标转视角，WASD 移动，滚轮缩放. Right-drag rotates the view ONLY inside
 *       build/road sub-modes, so the interaction steps never claim that.</li>
 *   <li>After placing a building the building bar reopens (still build mode), so every
 *       interaction step first tells the player to exit build mode (press 1 or ESC).</li>
 *   <li>Switching tabs uses the hard-coded number keys 1/2/3/4 (建造/道路/统计/异常).</li>
 * </ul>
 *
 * <p>Readability: every character must be clearly visible — no gray/dark text (§7/§8).
 * Keys/buttons/building names use §e gold, instruction lines §b aqua, completed steps §a green.
 */
public final class GuideRegistry {

    private GuideRegistry() {}

    // ── Shared keys (reused across steps) ──

    private static final List<String> AIMING_LINES = List.of(
            "guide.wandscape.common.aim_drag_place",
            "guide.wandscape.common.aim_rotate_submit");

    private static final List<String> PINNED_LINES = List.of(
            "guide.wandscape.common.pinned_locked",
            "guide.wandscape.common.pinned_submit");

    private static final GuideStep TOWN_HALL = new GuideStep(
            "townhall",
            "guide.wandscape.townhall.title",
            List.of(
                    "guide.wandscape.common.open_build",
                    "guide.wandscape.townhall.line2",
                    "guide.wandscape.townhall.line3",
                    "guide.wandscape.townhall.line4",
                    "guide.wandscape.townhall.line5"),
            List.of(
                    "guide.wandscape.common.build_list_open",
                    "guide.wandscape.townhall.bar2"),
            AIMING_LINES,
            PINNED_LINES,
            "guide.wandscape.townhall.hint");

    private static final GuideStep WAREHOUSE = new GuideStep(
            "warehouse",
            "guide.wandscape.warehouse.title",
            List.of(
                    "guide.wandscape.common.open_build",
                    "guide.wandscape.warehouse.line2",
                    "guide.wandscape.common.drag_position_rotate",
                    "guide.wandscape.common.submit_line"),
            List.of(
                    "guide.wandscape.common.build_list_open",
                    "guide.wandscape.warehouse.bar2"),
            AIMING_LINES,
            PINNED_LINES,
            "guide.wandscape.warehouse.hint");

    private static final GuideStep DEPOSIT = new GuideStep(
            "deposit",
            "guide.wandscape.deposit.title",
            List.of(
                    "guide.wandscape.common.exit_build",
                    "guide.wandscape.deposit.line2",
                    "guide.wandscape.deposit.line3"),
            List.of(
                    "guide.wandscape.common.exit_build",
                    "guide.wandscape.deposit.line2",
                    "guide.wandscape.deposit.line3"),
            List.of(
                    "guide.wandscape.common.exit_build",
                    "guide.wandscape.deposit.line2",
                    "guide.wandscape.deposit.line3"),
            List.of(
                    "guide.wandscape.common.exit_build",
                    "guide.wandscape.deposit.line2",
                    "guide.wandscape.deposit.line3"),
            "guide.wandscape.deposit.hint");

    private static final GuideStep WORKSTATION = new GuideStep(
            "workstation",
            "guide.wandscape.workstation.title",
            List.of(
                    "guide.wandscape.common.open_build",
                    "guide.wandscape.workstation.line2",
                    "guide.wandscape.common.drag_position_rotate",
                    "guide.wandscape.common.submit_line"),
            List.of(
                    "guide.wandscape.common.build_list_open",
                    "guide.wandscape.workstation.bar2"),
            AIMING_LINES,
            PINNED_LINES,
            "guide.wandscape.workstation.hint");

    private static final GuideStep SYNTHESIZE = new GuideStep(
            "synthesize",
            "guide.wandscape.synthesize.title",
            List.of(
                    "guide.wandscape.common.exit_build",
                    "guide.wandscape.synthesize.line2",
                    "guide.wandscape.synthesize.line3",
                    "guide.wandscape.synthesize.line4"),
            List.of(
                    "guide.wandscape.common.exit_build",
                    "guide.wandscape.synthesize.line2",
                    "guide.wandscape.synthesize.line3",
                    "guide.wandscape.synthesize.line4"),
            List.of(
                    "guide.wandscape.common.exit_build",
                    "guide.wandscape.synthesize.line2",
                    "guide.wandscape.synthesize.line3",
                    "guide.wandscape.synthesize.line4"),
            List.of(
                    "guide.wandscape.common.exit_build",
                    "guide.wandscape.synthesize.line2",
                    "guide.wandscape.synthesize.line3",
                    "guide.wandscape.synthesize.line4"),
            "guide.wandscape.synthesize.hint");

    private static final GuideStep ROAD = new GuideStep(
            "road",
            "guide.wandscape.road.title",
            List.of(
                    "guide.wandscape.road.line1",
                    "guide.wandscape.road.line2",
                    "guide.wandscape.road.line3"),
            List.of(
                    "guide.wandscape.road.line1",
                    "guide.wandscape.road.line2",
                    "guide.wandscape.road.line3"),
            List.of(
                    "guide.wandscape.road.line1",
                    "guide.wandscape.road.line2",
                    "guide.wandscape.road.line3"),
            List.of(
                    "guide.wandscape.road.line1",
                    "guide.wandscape.road.line2",
                    "guide.wandscape.road.line3"),
            "guide.wandscape.road.hint");

    private static final GuideStep BAKERY = new GuideStep(
            "bakery",
            "guide.wandscape.bakery.title",
            List.of(
                    "guide.wandscape.common.open_build",
                    "guide.wandscape.bakery.line2",
                    "guide.wandscape.common.drag_position_submit",
                    "guide.wandscape.bakery.line4"),
            List.of(
                    "guide.wandscape.common.build_list_open",
                    "guide.wandscape.bakery.bar2"),
            AIMING_LINES,
            PINNED_LINES,
            "guide.wandscape.bakery.hint");

    private static final GuideStep NODE = new GuideStep(
            "node",
            "guide.wandscape.node.title",
            List.of(
                    "guide.wandscape.common.open_build",
                    "guide.wandscape.node.line2",
                    "guide.wandscape.common.drag_position_submit",
                    "guide.wandscape.node.line4"),
            List.of(
                    "guide.wandscape.common.build_list_open",
                    "guide.wandscape.node.bar2"),
            AIMING_LINES,
            PINNED_LINES,
            "guide.wandscape.node.hint");

    private static final GuideStep ALTAR = new GuideStep(
            "altar",
            "guide.wandscape.altar.title",
            List.of(
                    "guide.wandscape.common.open_build",
                    "guide.wandscape.altar.line2",
                    "guide.wandscape.common.drag_position_submit",
                    "guide.wandscape.altar.line4"),
            List.of(
                    "guide.wandscape.common.build_list_open",
                    "guide.wandscape.altar.bar2"),
            AIMING_LINES,
            PINNED_LINES,
            "guide.wandscape.altar.hint");

    private static final GuideStep INN = new GuideStep(
            "youth_hostel",
            "guide.wandscape.youth_hostel.title",
            List.of(
                    "guide.wandscape.common.open_build",
                    "guide.wandscape.youth_hostel.line2",
                    "guide.wandscape.common.drag_position_submit",
                    "guide.wandscape.youth_hostel.line4"),
            List.of(
                    "guide.wandscape.common.build_list_open",
                    "guide.wandscape.youth_hostel.bar2"),
            AIMING_LINES,
            PINNED_LINES,
            "guide.wandscape.youth_hostel.hint");

    public static final List<GuideStep> STEPS = List.of(
            TOWN_HALL, WAREHOUSE, DEPOSIT, WORKSTATION, SYNTHESIZE,
            ROAD, BAKERY, NODE, ALTAR, INN);

    public static GuideStep step(int index) {
        return STEPS.get(index);
    }
}
