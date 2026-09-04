package com.wsteam.wandscape.foundation.ui.tutorial;
import com.wsteam.wandscape.content.tutorial.service.TutorialProgressService;

import java.util.List;

/**
 * Ordered onboarding steps (pure content — every text field is an i18n key resolved at
 * render time by {@link TutorialRenderer}). Completion is evaluated server-side by
 * {@code TutorialProgressService.computeStep} — the ORDER here MUST match the checks in that
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
public final class TutorialRegistry {

    private TutorialRegistry() {}

    // ── Shared keys (reused across steps) ──

    private static final List<String> AIMING_LINES = List.of(
            "tutorial.wandscape.common.aim_drag_place",
            "tutorial.wandscape.common.aim_rotate_submit");

    private static final List<String> PINNED_LINES = List.of(
            "tutorial.wandscape.common.pinned_locked",
            "tutorial.wandscape.common.pinned_submit");

    private static final TutorialStep TOWN_HALL = new TutorialStep(
            "townhall",
            "tutorial.wandscape.townhall.title",
            List.of(
                    "tutorial.wandscape.common.open_build",
                    "tutorial.wandscape.townhall.line2",
                    "tutorial.wandscape.townhall.line3",
                    "tutorial.wandscape.townhall.line4",
                    "tutorial.wandscape.townhall.line5"),
            List.of(
                    "tutorial.wandscape.common.build_list_open",
                    "tutorial.wandscape.townhall.bar2"),
            AIMING_LINES,
            PINNED_LINES,
            "tutorial.wandscape.townhall.hint");

    private static final TutorialStep WAREHOUSE = new TutorialStep(
            "warehouse",
            "tutorial.wandscape.warehouse.title",
            List.of(
                    "tutorial.wandscape.common.open_build",
                    "tutorial.wandscape.warehouse.line2",
                    "tutorial.wandscape.common.drag_position_rotate",
                    "tutorial.wandscape.common.submit_line"),
            List.of(
                    "tutorial.wandscape.common.build_list_open",
                    "tutorial.wandscape.warehouse.bar2"),
            AIMING_LINES,
            PINNED_LINES,
            "tutorial.wandscape.warehouse.hint");

    private static final TutorialStep DEPOSIT = new TutorialStep(
            "deposit",
            "tutorial.wandscape.deposit.title",
            List.of(
                    "tutorial.wandscape.common.exit_build",
                    "tutorial.wandscape.deposit.line2",
                    "tutorial.wandscape.deposit.line3"),
            List.of(
                    "tutorial.wandscape.common.exit_build",
                    "tutorial.wandscape.deposit.line2",
                    "tutorial.wandscape.deposit.line3"),
            List.of(
                    "tutorial.wandscape.common.exit_build",
                    "tutorial.wandscape.deposit.line2",
                    "tutorial.wandscape.deposit.line3"),
            List.of(
                    "tutorial.wandscape.common.exit_build",
                    "tutorial.wandscape.deposit.line2",
                    "tutorial.wandscape.deposit.line3"),
            "tutorial.wandscape.deposit.hint");

    private static final TutorialStep WORKSTATION = new TutorialStep(
            "workstation",
            "tutorial.wandscape.workstation.title",
            List.of(
                    "tutorial.wandscape.common.open_build",
                    "tutorial.wandscape.workstation.line2",
                    "tutorial.wandscape.common.drag_position_rotate",
                    "tutorial.wandscape.common.submit_line"),
            List.of(
                    "tutorial.wandscape.common.build_list_open",
                    "tutorial.wandscape.workstation.bar2"),
            AIMING_LINES,
            PINNED_LINES,
            "tutorial.wandscape.workstation.hint");

    private static final TutorialStep SYNTHESIZE = new TutorialStep(
            "synthesize",
            "tutorial.wandscape.synthesize.title",
            List.of(
                    "tutorial.wandscape.common.exit_build",
                    "tutorial.wandscape.synthesize.line2",
                    "tutorial.wandscape.synthesize.line3",
                    "tutorial.wandscape.synthesize.line4"),
            List.of(
                    "tutorial.wandscape.common.exit_build",
                    "tutorial.wandscape.synthesize.line2",
                    "tutorial.wandscape.synthesize.line3",
                    "tutorial.wandscape.synthesize.line4"),
            List.of(
                    "tutorial.wandscape.common.exit_build",
                    "tutorial.wandscape.synthesize.line2",
                    "tutorial.wandscape.synthesize.line3",
                    "tutorial.wandscape.synthesize.line4"),
            List.of(
                    "tutorial.wandscape.common.exit_build",
                    "tutorial.wandscape.synthesize.line2",
                    "tutorial.wandscape.synthesize.line3",
                    "tutorial.wandscape.synthesize.line4"),
            "tutorial.wandscape.synthesize.hint");

    private static final TutorialStep BAKERY = new TutorialStep(
            "bakery",
            "tutorial.wandscape.bakery.title",
            List.of(
                    "tutorial.wandscape.common.open_build",
                    "tutorial.wandscape.bakery.line2",
                    "tutorial.wandscape.common.drag_position_submit",
                    "tutorial.wandscape.bakery.line4"),
            List.of(
                    "tutorial.wandscape.common.build_list_open",
                    "tutorial.wandscape.bakery.bar2"),
            AIMING_LINES,
            PINNED_LINES,
            "tutorial.wandscape.bakery.hint");

    private static final TutorialStep ALTAR = new TutorialStep(
            "altar",
            "tutorial.wandscape.altar.title",
            List.of(
                    "tutorial.wandscape.common.open_build",
                    "tutorial.wandscape.altar.line2",
                    "tutorial.wandscape.common.drag_position_submit",
                    "tutorial.wandscape.altar.line4"),
            List.of(
                    "tutorial.wandscape.common.build_list_open",
                    "tutorial.wandscape.altar.bar2"),
            AIMING_LINES,
            PINNED_LINES,
            "tutorial.wandscape.altar.hint");

    private static final TutorialStep TAVERN = new TutorialStep(
            "tavern",
            "tutorial.wandscape.tavern.title",
            List.of(
                    "tutorial.wandscape.common.open_build",
                    "tutorial.wandscape.tavern.line2",
                    "tutorial.wandscape.common.drag_position_submit",
                    "tutorial.wandscape.tavern.line4"),
            List.of(
                    "tutorial.wandscape.common.build_list_open",
                    "tutorial.wandscape.tavern.bar2"),
            AIMING_LINES,
            PINNED_LINES,
            "tutorial.wandscape.tavern.hint");

    private static final TutorialStep MAGE_HUT = new TutorialStep(
            "mage_hut",
            "tutorial.wandscape.mage_hut.title",
            List.of(
                    "tutorial.wandscape.common.open_build",
                    "tutorial.wandscape.mage_hut.line2",
                    "tutorial.wandscape.common.drag_position_submit",
                    "tutorial.wandscape.mage_hut.line4"),
            List.of(
                    "tutorial.wandscape.common.build_list_open",
                    "tutorial.wandscape.mage_hut.bar2"),
            AIMING_LINES,
            PINNED_LINES,
            "tutorial.wandscape.mage_hut.hint");

    private static final TutorialStep INN = new TutorialStep(
            "youth_hostel",
            "tutorial.wandscape.youth_hostel.title",
            List.of(
                    "tutorial.wandscape.common.open_build",
                    "tutorial.wandscape.youth_hostel.line2",
                    "tutorial.wandscape.common.drag_position_submit",
                    "tutorial.wandscape.youth_hostel.line4"),
            List.of(
                    "tutorial.wandscape.common.build_list_open",
                    "tutorial.wandscape.youth_hostel.bar2"),
            AIMING_LINES,
            PINNED_LINES,
            "tutorial.wandscape.youth_hostel.hint");

    public static final List<TutorialStep> STEPS = List.of(
            TOWN_HALL, WAREHOUSE, DEPOSIT, WORKSTATION, SYNTHESIZE,
            BAKERY, ALTAR, TAVERN, MAGE_HUT, INN);

    public static TutorialStep step(int index) {
        return STEPS.get(index);
    }
}
