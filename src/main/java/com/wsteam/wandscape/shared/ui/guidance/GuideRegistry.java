package com.wsteam.wandscape.shared.ui.guidance;

import java.util.List;

/**
 * Ordered onboarding steps (pure content). Completion is evaluated server-side
 * by {@code GuideProgressService.computeStep} — the ORDER here MUST match the
 * checks in that method. Building names mirror the building-bar card names.
 *
 * <p>Content conventions (accuracy-first, from the real interactions):
 * <ul>
 *   <li>Placement (build/road sub-modes) is taught as OPERATIONS, in order, no
 *       passive filler: 按住右键拖动 = 转视角（把建筑放到想要的位置）→ 左键 = 旋转朝向
 *       → 点右侧【提交施工】→ 施工界面【提交】. The important operations come first;
 *       the hint line only carries auxiliary info (WASD/scroll/building purpose).</li>
 *   <li>Building interaction happens in the V-panel OVERVIEW sub-mode, which is a
 *       free camera: 移动鼠标转视角，WASD 移动，滚轮缩放. Right-drag rotates the view
 *       ONLY inside build/road sub-modes, so the interaction steps never claim that.</li>
 *   <li>After placing a building the building bar reopens (still build mode), so every
 *       interaction step first tells the player to exit build mode (press 1 or ESC).</li>
 *   <li>Switching tabs uses the hard-coded number keys 1/2/3/4 (建造/道路/统计/异常).</li>
 * </ul>
 *
 * <p>Readability: every character must be clearly visible — no gray/dark text
 * (§7/§8). Keys/buttons/building names use §e gold, instruction lines §b aqua,
 * completed steps §a green.
 */
public final class GuideRegistry {

    private GuideRegistry() {}

    /** Placement operations shown while aiming the ghost (important operations first). */
    private static final List<String> AIMING_LINES = List.of(
            "§b▶ §e按住右键拖动§b转视角，把建筑放到你要的位置",
            "§b▶ §e左键§b旋转朝向，点右侧【§e提交施工§b】→【§e提交§b】");

    /** Shown when the ghost is pinned (optional gizmo fine-tuning). */
    private static final List<String> PINNED_LINES = List.of(
            "§a✓ 已锁定，可拖拽轴线微调",
            "§b▶ 点右侧【§e提交施工§b】→【§e提交§b】");

    private static final GuideStep TOWN_HALL = new GuideStep(
            "townhall",
            "🚩 新手引导 (1/10)：建造市政厅",
            List.of(
                    "§b① 按 §e1§b 打开【建造】页签",
                    "§b② 点【§e市政厅§b】卡片，§e双击§b进入放置",
                    "§b③ §e按住右键拖动§b转视角，把建筑放到你要的位置",
                    "§b④ §e左键§b旋转朝向，点右侧【§e提交施工§b】→【§e提交§b】",
                    "§b⑤ 自动弹出命名界面，输入名称创建魔法小镇"),
            List.of(
                    "§a✓ 建造列表已打开",
                    "§b▶ 点【§e市政厅§b】卡片，§e双击§b进入放置"),
            AIMING_LINES,
            PINNED_LINES,
            "§e💡 WASD 移动镜头，滚轮缩放");

    private static final GuideStep WAREHOUSE = new GuideStep(
            "warehouse",
            "🚩 新手引导 (2/10)：建造仓库",
            List.of(
                    "§b① 按 §e1§b 打开【建造】页签",
                    "§b② 点【§e仓库§b】卡片，§e双击§b进入放置",
                    "§b③ §e按住右键拖动§b转视角定位，§e左键§b旋转朝向",
                    "§b④ 点右侧【§e提交施工§b】→【§e提交§b】"),
            List.of(
                    "§a✓ 建造列表已打开",
                    "§b▶ 点【§e仓库§b】卡片，§e双击§b进入放置"),
            AIMING_LINES,
            PINNED_LINES,
            "§e💡 仓库存放居民采掘/合成的物资与元素，是最重要的建筑");

    private static final GuideStep DEPOSIT = new GuideStep(
            "deposit",
            "🚩 新手引导 (3/10)：右键仓库，放入一个物品",
            List.of(
                    "§b① 按 §e1§b 或 §eESC§b 退出建造模式",
                    "§b② §e移动鼠标转视角§b，把准心对准仓库，§e右键点一下§b打开界面",
                    "§b③ 切到【§e交换§b】页签，点背包一个物品存入仓库"),
            List.of(
                    "§b① 按 §e1§b 或 §eESC§b 退出建造模式",
                    "§b② §e移动鼠标转视角§b，把准心对准仓库，§e右键点一下§b打开界面",
                    "§b③ 切到【§e交换§b】页签，点背包一个物品存入仓库"),
            List.of(
                    "§b① 按 §e1§b 或 §eESC§b 退出建造模式",
                    "§b② §e移动鼠标转视角§b，把准心对准仓库，§e右键点一下§b打开界面",
                    "§b③ 切到【§e交换§b】页签，点背包一个物品存入仓库"),
            List.of(
                    "§b① 按 §e1§b 或 §eESC§b 退出建造模式",
                    "§b② §e移动鼠标转视角§b，把准心对准仓库，§e右键点一下§b打开界面",
                    "§b③ 切到【§e交换§b】页签，点背包一个物品存入仓库"),
            "§e💡 俯瞰视角：WASD 移动镜头，滚轮缩放；右键点建筑打开界面");

    private static final GuideStep WORKSTATION = new GuideStep(
            "workstation",
            "🚩 新手引导 (4/10)：建造工作站",
            List.of(
                    "§b① 按 §e1§b 打开【建造】页签",
                    "§b② 点【§e工作站§b】卡片，§e双击§b进入放置",
                    "§b③ §e按住右键拖动§b转视角定位，§e左键§b旋转朝向",
                    "§b④ 点右侧【§e提交施工§b】→【§e提交§b】"),
            List.of(
                    "§a✓ 建造列表已打开",
                    "§b▶ 点【§e工作站§b】卡片，§e双击§b进入放置"),
            AIMING_LINES,
            PINNED_LINES,
            "§e💡 工作站可把建材分解为元素，或用元素合成物品");

    private static final GuideStep SYNTHESIZE = new GuideStep(
            "synthesize",
            "🚩 新手引导 (5/10)：右键工作站，合成一样物品",
            List.of(
                    "§b① 按 §e1§b 或 §eESC§b 退出建造模式",
                    "§b② 把准心对准 §e工作站§b，§e右键点一下§b打开界面",
                    "§b③ 切到【§e合成§b】页签，选一个配方",
                    "§b④ 点【§e提交§b】发布合成任务"),
            List.of(
                    "§b① 按 §e1§b 或 §eESC§b 退出建造模式",
                    "§b② 把准心对准 §e工作站§b，§e右键点一下§b打开界面",
                    "§b③ 切到【§e合成§b】页签，选一个配方",
                    "§b④ 点【§e提交§b】发布合成任务"),
            List.of(
                    "§b① 按 §e1§b 或 §eESC§b 退出建造模式",
                    "§b② 把准心对准 §e工作站§b，§e右键点一下§b打开界面",
                    "§b③ 切到【§e合成§b】页签，选一个配方",
                    "§b④ 点【§e提交§b】发布合成任务"),
            List.of(
                    "§b① 按 §e1§b 或 §eESC§b 退出建造模式",
                    "§b② 把准心对准 §e工作站§b，§e右键点一下§b打开界面",
                    "§b③ 切到【§e合成§b】页签，选一个配方",
                    "§b④ 点【§e提交§b】发布合成任务"),
            "§e💡 合成消耗元素、产出存入仓库；NPC 会自动执行任务");

    private static final GuideStep ROAD = new GuideStep(
            "road",
            "🚩 新手引导 (6/10)：铺设一条道路",
            List.of(
                    "§b① 按 §e2§b 进入【道路】模式",
                    "§b② §e左键点一下§b设起点，按住拖动扩大选区",
                    "§b③ 按 §eEnter§b 铺设道路"),
            List.of(
                    "§b① 按 §e2§b 进入【道路】模式",
                    "§b② §e左键点一下§b设起点，按住拖动扩大选区",
                    "§b③ 按 §eEnter§b 铺设道路"),
            List.of(
                    "§b① 按 §e2§b 进入【道路】模式",
                    "§b② §e左键点一下§b设起点，按住拖动扩大选区",
                    "§b③ 按 §eEnter§b 铺设道路"),
            List.of(
                    "§b① 按 §e2§b 进入【道路】模式",
                    "§b② §e左键点一下§b设起点，按住拖动扩大选区",
                    "§b③ 按 §eEnter§b 铺设道路"),
            "§e💡 道路让游客与 NPC 沿路移动");

    private static final GuideStep BAKERY = new GuideStep(
            "bakery",
            "🚩 新手引导 (7/10)：建造面包店，补充货物",
            List.of(
                    "§b① 按 §e1§b 打开【建造】页签",
                    "§b② 点【§e面包店§b】卡片，§e双击§b进入放置",
                    "§b③ §e按住右键拖动§b转视角定位，点右侧【§e提交施工§b】→【§e提交§b】",
                    "§b④ 按 §e1§b 或 §eESC§b 退出建造，§e右键面包店§b 打开商店补货"),
            List.of(
                    "§a✓ 建造列表已打开",
                    "§b▶ 点【§e面包店§b】卡片，§e双击§b进入放置"),
            AIMING_LINES,
            PINNED_LINES,
            "§e💡 游客要到 §e第二天§b 才会来购物；补货自动从仓库取货，\n§b 仓库货物不足时自动合成；游客购物带来元素收益");

    private static final GuideStep NODE = new GuideStep(
            "node",
            "🚩 新手引导 (8/10)：建造节点，发布采集任务",
            List.of(
                    "§b① 按 §e1§b 打开【建造】页签",
                    "§b② 点一个§e元素节点§b卡片，§e双击§b进入放置",
                    "§b③ §e按住右键拖动§b转视角定位，点右侧【§e提交施工§b】→【§e提交§b】",
                    "§b④ 按 §e1§b 或 §eESC§b 退出建造，§e右键节点§b 点【§e发布采集§b】"),
            List.of(
                    "§a✓ 建造列表已打开",
                    "§b▶ 点一个§e元素节点§b卡片，§e双击§b进入放置"),
            AIMING_LINES,
            PINNED_LINES,
            "§e💡 发布后 NPC 去节点引导施法，采集元素注入仓库");

    private static final GuideStep ALTAR = new GuideStep(
            "altar",
            "🚩 新手引导 (9/10)：建造祭坛",
            List.of(
                    "§b① 按 §e1§b 打开【建造】页签",
                    "§b② 点【§e祭坛§b】卡片，§e双击§b进入放置",
                    "§b③ §e按住右键拖动§b转视角定位，点右侧【§e提交施工§b】→【§e提交§b】",
                    "§b④ 按 §e1§b 或 §eESC§b 退出建造，§e右键祭坛§b 打开施法界面"),
            List.of(
                    "§a✓ 建造列表已打开",
                    "§b▶ 点【§e祭坛§b】卡片，§e双击§b进入放置"),
            AIMING_LINES,
            PINNED_LINES,
            "§e💡 祭坛用于施放重大魔法：有 NPC 阵亡后可在此选中复活并发布");

    private static final GuideStep INN = new GuideStep(
            "inn",
            "🚩 新手引导 (10/10)：建造旅馆，接待游客",
            List.of(
                    "§b① 按 §e1§b 打开【建造】页签",
                    "§b② 点【§e旅馆§b】卡片，§e双击§b进入放置",
                    "§b③ §e按住右键拖动§b转视角定位，点右侧【§e提交施工§b】→【§e提交§b】",
                    "§b④ 游客要到 §e第二天§b 才会到访，夜晚会到旅馆过夜入住"),
            List.of(
                    "§a✓ 建造列表已打开",
                    "§b▶ 点【§e旅馆§b】卡片，§e双击§b进入放置"),
            AIMING_LINES,
            PINNED_LINES,
            "§e💡 有游客过夜即完成全部引导！继续建造建筑、提升游客满意度可升级小镇");

    public static final List<GuideStep> STEPS = List.of(
            TOWN_HALL, WAREHOUSE, DEPOSIT, WORKSTATION, SYNTHESIZE,
            ROAD, BAKERY, NODE, ALTAR, INN);

    public static GuideStep step(int index) {
        return STEPS.get(index);
    }
}
