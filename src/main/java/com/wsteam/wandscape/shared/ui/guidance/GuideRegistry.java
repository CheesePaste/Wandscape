package com.wsteam.wandscape.shared.ui.guidance;

import java.util.List;

import com.wsteam.wandscape.shared.registry.WandscapeConstants;

/**
 * Ordered onboarding steps (data-driven). Add a step to extend the flow; each
 * completion is a predicate over {@link GuideContext} so future steps can query
 * roads, tourists, etc. once {@link GuideContext} exposes those accessors.
 */
public final class GuideRegistry {

    private GuideRegistry() {}

    private static final GuideStep TOWN_HALL = new GuideStep(
            "townhall",
            "🚩 新手引导 (1/2)：建造市政厅",
            List.of(
                    "§a▶ 1. 点击左侧边栏 🏛️【建造】图标",
                    "§7  2. 在【市政】分类中找到【市政厅】",
                    "§7  3. 双击卡片并在世界中右键放置蓝图"),
            List.of(
                    "§7✓ 1. 已打开建造面板",
                    "§a▶ 2. 在下方列表找到并§e双击【市政厅】",
                    "§7  3. 在世界中右键点击确认放置"),
            List.of(
                    "§7✓ 1. 已选中市政厅蓝图",
                    "§7✓ 2. 建筑虚影已在世界中显现",
                    "§a▶ 3. 移动视角选择空地，§e右键点击确认建造"),
            "💡 提示：左键旋转建筑朝向",
            ctx -> ctx.hasCategory(WandscapeConstants.BUILDING_CATEGORY_GOVERNMENT));

    private static final GuideStep WAREHOUSE = new GuideStep(
            "warehouse",
            "🚩 新手引导 (2/2)：建造仓库",
            List.of(
                    "§a▶ 1. 点击左侧边栏 🏛️【建造】图标",
                    "§7  2. 在【存储】分类中找到【仓库】",
                    "§7  3. 双击卡片并在世界中右键放置蓝图"),
            List.of(
                    "§7✓ 1. 已打开建造面板",
                    "§a▶ 2. 切换至【存储】分类，§e双击【仓库】",
                    "§7  3. 在世界中右键点击确认放置"),
            List.of(
                    "§7✓ 1. 已选中仓库蓝图",
                    "§7✓ 2. 建筑虚影已在世界中显现",
                    "§a▶ 3. 移动视角选择空地，§e右键点击确认建造"),
            "💡 提示：寻找平坦空地，右键确认建造",
            ctx -> ctx.hasType("warehouse"));

    public static final List<GuideStep> STEPS = List.of(TOWN_HALL, WAREHOUSE);

    public static GuideStep step(int index) {
        return STEPS.get(index);
    }
}
