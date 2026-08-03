package com.wsteam.wandscape.shared.ui.guidance;

import java.util.List;

/**
 * Ordered onboarding steps (pure content). Completion is evaluated server-side
 * by {@code GuideProgressService.computeStep} — the ORDER here MUST match the
 * checks in that method. Category display names mirror the building bar
 * (BuildingSelectionOverlay.getCategoryDisplayName).
 */
public final class GuideRegistry {

    private GuideRegistry() {}

    private static final GuideStep TOWN_HALL = new GuideStep(
            "townhall",
            "🚩 新手引导 (1/9)：建造市政厅",
            List.of(
                    "§a▶ 1. 点击左侧边栏 🏛️【建造】图标",
                    "§7  2. 在【市政厅】分类中找到【市政厅】",
                    "§7  3. 双击卡片并在世界中右键放置蓝图"),
            List.of(
                    "§7✓ 1. 已打开建造面板",
                    "§a▶ 2. 在下方列表找到并§e双击【市政厅】",
                    "§7  3. 在世界中右键点击确认放置"),
            List.of(
                    "§7✓ 1. 已选中市政厅蓝图",
                    "§7✓ 2. 建筑虚影已在世界中显现",
                    "§a▶ 3. 移动视角选择空地，§e右键点击确认建造"),
            "💡 提示：左键旋转建筑朝向");

    private static final GuideStep WAREHOUSE = new GuideStep(
            "warehouse",
            "🚩 新手引导 (2/9)：建造仓库",
            List.of(
                    "§a▶ 1. 点击左侧边栏 🏛️【建造】图标",
                    "§7  2. 在【仓库/存储】分类中找到【仓库】",
                    "§7  3. 双击卡片并在世界中右键放置蓝图"),
            List.of(
                    "§7✓ 1. 已打开建造面板",
                    "§a▶ 2. 切换至【仓库/存储】分类，§e双击【仓库】",
                    "§7  3. 在世界中右键点击确认放置"),
            List.of(
                    "§7✓ 1. 已选中仓库蓝图",
                    "§7✓ 2. 建筑虚影已在世界中显现",
                    "§a▶ 3. 移动视角选择空地，§e右键点击确认建造"),
            "💡 提示：仓库用于安全存放居民采掘与合成的物资");

    private static final GuideStep NODE = new GuideStep(
            "node",
            "🚩 新手引导 (3/9)：建造元素节点",
            List.of(
                    "§a▶ 1. 点击左侧边栏 🏛️【建造】图标",
                    "§7  2. 在【元素节点】分类中找到节点建筑",
                    "§7  3. 建造后法师 NPC 会发布元素采集任务"),
            List.of(
                    "§7✓ 1. 已打开建造面板",
                    "§a▶ 2. 切换至【元素节点】分类，§e双击节点",
                    "§7  3. 在世界中右键点击确认放置"),
            List.of(
                    "§7✓ 1. 已选中节点蓝图",
                    "§7✓ 2. 建筑虚影已在世界中显现",
                    "§a▶ 3. 移动视角选择空地，§e右键点击确认建造"),
            "💡 提示：节点建成后自动发布采集任务");

    private static final GuideStep WORKSTATION = new GuideStep(
            "workstation",
            "🚩 新手引导 (4/9)：建造生产工坊",
            List.of(
                    "§a▶ 1. 点击左侧边栏 🏛️【建造】图标",
                    "§7  2. 在【生产工坊】分类中找到工作台",
                    "§7  3. 建造后右键打开，发布任意合成任务"),
            List.of(
                    "§7✓ 1. 已打开建造面板",
                    "§a▶ 2. 切换至【生产工坊】分类，§e双击工作台",
                    "§7  3. 在世界中右键点击确认放置"),
            List.of(
                    "§7✓ 1. 已选中工作台蓝图",
                    "§7✓ 2. 建筑虚影已在世界中显现",
                    "§a▶ 3. 移动视角选择空地，§e右键点击确认建造"),
            "💡 提示：发布合成任务后引导前进");

    private static final GuideStep CRAFT_STATION = new GuideStep(
            "craft_station",
            "🚩 新手引导 (5/9)：建造法宝合成站",
            List.of(
                    "§a▶ 1. 点击左侧边栏 🏛️【建造】图标",
                    "§7  2. 在【法宝合成】分类中找到合成站",
                    "§7  3. 建造后右键打开，发布任意法杖合成任务"),
            List.of(
                    "§7✓ 1. 已打开建造面板",
                    "§a▶ 2. 切换至【法宝合成】分类，§e双击合成站",
                    "§7  3. 在世界中右键点击确认放置"),
            List.of(
                    "§7✓ 1. 已选中合成站蓝图",
                    "§7✓ 2. 建筑虚影已在世界中显现",
                    "§a▶ 3. 移动视角选择空地，§e右键点击确认建造"),
            "💡 提示：法杖合成需要对应图纸与元素");

    private static final GuideStep SHOP = new GuideStep(
            "shop",
            "🚩 新手引导 (6/9)：建造商店并补充货物",
            List.of(
                    "§a▶ 1. 点击左侧边栏 🏛️【建造】图标",
                    "§7  2. 在【商业/商店】分类中找到商店",
                    "§7  3. 建造后右键商店补充货物，等待游客购买"),
            List.of(
                    "§7✓ 1. 已打开建造面板",
                    "§a▶ 2. 切换至【商业/商店】分类，§e双击商店",
                    "§7  3. 在世界中右键点击确认放置"),
            List.of(
                    "§7✓ 1. 已选中商店蓝图",
                    "§7✓ 2. 建筑虚影已在世界中显现",
                    "§a▶ 3. 移动视角选择空地，§e右键点击确认建造"),
            "💡 提示：游客购买商品后引导前进");

    private static final GuideStep INN = new GuideStep(
            "inn",
            "🚩 新手引导 (7/9)：建造旅店等待游客入住",
            List.of(
                    "§a▶ 1. 点击左侧边栏 🏛️【建造】图标",
                    "§7  2. 在【服务/旅店】分类中找到旅店",
                    "§7  3. 建造后等待游客入住"),
            List.of(
                    "§7✓ 1. 已打开建造面板",
                    "§a▶ 2. 切换至【服务/旅店】分类，§e双击旅店",
                    "§7  3. 在世界中右键点击确认放置"),
            List.of(
                    "§7✓ 1. 已选中旅店蓝图",
                    "§7✓ 2. 建筑虚影已在世界中显现",
                    "§a▶ 3. 移动视角选择空地，§e右键点击确认建造"),
            "💡 提示：游客入住后引导前进");

    private static final GuideStep TAVERN = new GuideStep(
            "tavern",
            "🚩 新手引导 (8/9)：建造酒馆招募法师",
            List.of(
                    "§a▶ 1. 点击左侧边栏 🏛️【建造】图标",
                    "§7  2. 找到【冒险者酒馆】并建造",
                    "§7  3. 建造后右键酒馆招募一名法师 NPC"),
            List.of(
                    "§7✓ 1. 已打开建造面板",
                    "§a▶ 2. 找到并§e双击【冒险者酒馆】",
                    "§7  3. 在世界中右键点击确认放置"),
            List.of(
                    "§7✓ 1. 已选中酒馆蓝图",
                    "§7✓ 2. 建筑虚影已在世界中显现",
                    "§a▶ 3. 移动视角选择空地，§e右键点击确认建造"),
            "💡 提示：招募一名法师后引导前进");

    private static final GuideStep LEVEL_UP = new GuideStep(
            "level_up",
            "🚩 新手引导 (9/9)：殖民地升级到 2 级",
            List.of(
                    "§7✓ 主要设施已齐备",
                    "§a▶ 1. 提升游客满意度至 100%",
                    "§7  2. 游客 100% 满意离开后殖民地获得经验并升级"),
            List.of(
                    "§7✓ 主要设施已齐备",
                    "§a▶ 1. 提升游客满意度至 100%",
                    "§7  2. 游客 100% 满意离开后殖民地获得经验并升级"),
            List.of(
                    "§7✓ 主要设施已齐备",
                    "§a▶ 1. 提升游客满意度至 100%",
                    "§7  2. 游客 100% 满意离开后殖民地获得经验并升级"),
            "💡 提示：完善服务与商店能提升游客满意度");

    public static final List<GuideStep> STEPS = List.of(
            TOWN_HALL, WAREHOUSE, NODE, WORKSTATION, CRAFT_STATION,
            SHOP, INN, TAVERN, LEVEL_UP);

    public static GuideStep step(int index) {
        return STEPS.get(index);
    }
}
