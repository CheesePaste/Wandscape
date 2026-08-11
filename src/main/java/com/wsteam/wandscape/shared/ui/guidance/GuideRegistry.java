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
                    "§a▶ 1. 按快捷键 §e1§a 开启【建造模式】（或点击左侧 🏛️ 图标）",
                    "§7  2. 在【市政厅】分类中选中【市政厅】卡片",
                    "§7  3. §e双击卡片§7收回光标，进入 3D 放置定位",
                    "§8  💬 视角操作：按住右键拖动移动/旋转视角，WASD 移动镜头"),
            List.of(
                    "§7✓ 1. 已开启建造模式",
                    "§a▶ 2. 在下方建造栏§e单击【市政厅】§7选中，§e双击§7进入放置",
                    "§a▶ 3. 提示：可按 §eTab§7 随时收起/展开下方的建造列表",
                    "§8  💬 视角操作：按住右键拖动移动/旋转视角，WASD 移动镜头"),
            List.of(
                    "§7✓ 1. 建筑虚影正跟随准心移动（瞄准定位中）",
                    "§a▶ 2. 移动视角瞄准地面；§e左键点击§7（或按 R）旋转朝向",
                    "§a▶ 3. 按 §eEnter§7 或右侧【📌 锁定】固定位置（也可直接点【✓ 提交施工】）",
                    "§8  💬 视角操作：按住右键拖动旋转视角，WASD/Shift/Space 飞行"),
            List.of(
                    "§7✓ 1. 建筑虚影坐标已锁定",
                    "§a▶ 2. 鼠标拖拽 3D 轴线 (Gizmo) 精确平移 X/Y/Z",
                    "§a▶ 3. 双击画面空地或右侧【✓ 提交施工】，按 §eSubmit§7 派发建造",
                    "§8  💬 取消锁定：按 Esc 回到瞄准状态，再按 Esc 退出放置"),
            "💡 提示：按 1/2/3/4 可快速切换 建造/道路/统计/异常 页签");

    private static final GuideStep WAREHOUSE = new GuideStep(
            "warehouse",
            "🚩 新手引导 (2/9)：建造仓库",
            List.of(
                    "§a▶ 1. 按快捷键 §e1§a 开启【建造模式】",
                    "§7  2. 在【仓库/存储】分类中找到【仓库】",
                    "§7  3. 单击选中卡片，双击收回光标进入放置",
                    "§8  💬 视角操作：按住右键拖动移动/旋转视角，WASD 移动镜头"),
            List.of(
                    "§7✓ 1. 已开启建造模式",
                    "§a▶ 2. 切换至【仓库/存储】分类，§e单击【仓库】§7选中",
                    "§a▶ 3. §e双击卡片§7收回光标进入 3D 放置",
                    "§8  💬 视角操作：按住右键拖动移动/旋转视角，WASD 移动镜头"),
            List.of(
                    "§7✓ 1. 仓库蓝图虚影正跟随准心移动",
                    "§a▶ 2. §e左键点击§7旋转朝向，选好合适平地",
                    "§a▶ 3. 按 §eEnter§7 / 点击【📌 锁定】固定坐标（或点【✓ 提交施工】）",
                    "§8  💬 视角操作：按住右键拖动旋转视角，WASD/Shift/Space 飞行"),
            List.of(
                    "§7✓ 1. 仓库位置已锁定",
                    "§a▶ 2. 拖拽 3D 轴线微调，点击右侧【✓ 提交施工】",
                    "§a▶ 3. 在弹出的施工界面确认材料，点击 §eSubmit§7",
                    "§8  💬 仓库作用：居民建造、合成与采集的物资均存放于此"),
            "💡 提示：仓库用于安全存放居民采掘与合成的元素及建材");

    private static final GuideStep NODE = new GuideStep(
            "node",
            "🚩 新手引导 (3/9)：建造元素节点",
            List.of(
                    "§a▶ 1. 按快捷键 §e1§a 开启【建造模式】",
                    "§7  2. 在【元素节点】分类中找到节点建筑",
                    "§7  3. 单击选中，双击进入世界定位",
                    "§8  💬 视角操作：按住右键拖动移动/旋转视角，WASD 移动镜头"),
            List.of(
                    "§7✓ 1. 已开启建造模式",
                    "§a▶ 2. 切换至【元素节点】分类，§e单击节点§7选中",
                    "§a▶ 3. §e双击卡片§7收回光标进入 3D 放置",
                    "§8  💬 视角操作：按住右键拖动移动/旋转视角，WASD 移动镜头"),
            List.of(
                    "§7✓ 1. 节点虚影正跟随准心移动",
                    "§a▶ 2. §e左键点击§7旋转朝向，按 §eEnter§7 锁定（或直接提交）",
                    "§8  💬 视角操作：按住右键拖动旋转视角，WASD 飞行"),
            List.of(
                    "§7✓ 1. 节点位置已锁定",
                    "§a▶ 2. 拖拽 3D 轴线微调，点击右侧【✓ 提交施工】并 Submit",
                    "§8  💬 提示：建好后法师会自动去采集节点元素"),
            "💡 提示：元素节点建成后法师会自动前往采集对应元素");

    private static final GuideStep WORKSTATION = new GuideStep(
            "workstation",
            "🚩 新手引导 (4/9)：建造生产工坊",
            List.of(
                    "§a▶ 1. 按快捷键 §e1§a 开启【建造模式】",
                    "§7  2. 在【生产工坊】分类中找到工作站",
                    "§7  3. 单击选中，双击进入世界定位",
                    "§8  💬 视角操作：按住右键拖动移动/旋转视角，WASD 移动镜头"),
            List.of(
                    "§7✓ 1. 已开启建造模式",
                    "§a▶ 2. 切换至【生产工坊】分类，§e单击工作站§7选中",
                    "§a▶ 3. §e双击卡片§7进入 3D 放置",
                    "§8  💬 视角操作：按住右键拖动移动/旋转视角，WASD 移动镜头"),
            List.of(
                    "§7✓ 1. 工作站虚影正跟随准心移动",
                    "§a▶ 2. §e左键点击§7旋转朝向，按 §eEnter§7 锁定或提交",
                    "§8  💬 视角操作：按住右键拖动旋转视角，WASD 飞行"),
            List.of(
                    "§7✓ 1. 工作站位置已锁定",
                    "§a▶ 2. 拖拽 3D 轴线微调，点击右侧【✓ 提交施工】并 Submit",
                    "§8  💬 工作站功能：分解建材为元素或用元素合成高阶方块"),
            "💡 提示：工作站可将建材分解为元素或用元素合成高阶材料");

    private static final GuideStep CRAFT_STATION = new GuideStep(
            "craft_station",
            "🚩 新手引导 (5/9)：建造合成站",
            List.of(
                    "§a▶ 1. 按快捷键 §e1§a 开启【建造模式】",
                    "§7  2. 在【生产工坊】分类中找到合成站",
                    "§7  3. 单击选中，双击进入世界定位",
                    "§8  💬 视角操作：按住右键拖动移动/旋转视角，WASD 移动镜头"),
            List.of(
                    "§7✓ 1. 已开启建造模式",
                    "§a▶ 2. 切换至【生产工坊】分类，§e单击合成站§7选中",
                    "§a▶ 3. §e双击卡片§7进入 3D 放置"),
            List.of(
                    "§7✓ 1. 合成站虚影正跟随准心移动",
                    "§a▶ 2. §e左键点击§7旋转朝向，按 §eEnter§7 锁定或提交"),
            List.of(
                    "§7✓ 1. 合成站位置已锁定",
                    "§a▶ 2. 拖拽 3D 轴线微调，点击右侧【✓ 提交施工】并 Submit"),
            "💡 提示：合成站可打造魔法师施法所需的武器装备");

    private static final GuideStep SHOP = new GuideStep(
            "shop",
            "🚩 新手引导 (6/9)：建造商店并补充货物",
            List.of(
                    "§a▶ 1. 按快捷键 §e1§a 开启【建造模式】",
                    "§7  2. 在【商业/商店】分类中找到商店",
                    "§7  3. 单击选中，双击进入世界定位",
                    "§8  💬 视角操作：按住右键拖动移动/旋转视角，WASD 移动镜头"),
            List.of(
                    "§7✓ 1. 已开启建造模式",
                    "§a▶ 2. 切换至【商业/商店】分类，§e单击商店§7选中",
                    "§a▶ 3. §e双击卡片§7进入 3D 放置"),
            List.of(
                    "§7✓ 1. 商店虚影正跟随准心移动",
                    "§a▶ 2. §e左键点击§7旋转朝向，按 §eEnter§7 锁定或提交"),
            List.of(
                    "§7✓ 1. 商店位置已锁定",
                    "§a▶ 2. 拖拽 3D 轴线微调，点击右侧【✓ 提交施工】并 Submit"),
            "💡 提示：游客在商店消费后，魔法小镇将获得元素与经济收益");

    private static final GuideStep INN = new GuideStep(
            "inn",
            "🚩 新手引导 (7/9)：建造旅馆等待游客入住",
            List.of(
                    "§a▶ 1. 按快捷键 §e1§a 开启【建造模式】",
                    "§7  2. 在【服务/旅馆】分类中找到旅馆",
                    "§7  3. 单击选中，双击进入世界定位",
                    "§8  💬 视角操作：按住右键拖动移动/旋转视角，WASD 移动镜头"),
            List.of(
                    "§7✓ 1. 已开启建造模式",
                    "§a▶ 2. 切换至【服务/旅馆】分类，§e单击旅馆§7选中",
                    "§a▶ 3. §e双击卡片§7进入 3D 放置"),
            List.of(
                    "§7✓ 1. 旅馆虚影正跟随准心移动",
                    "§a▶ 2. §e左键点击§7旋转朝向，按 §eEnter§7 锁定或提交"),
            List.of(
                    "§7✓ 1. 旅馆位置已锁定",
                    "§a▶ 2. 拖拽 3D 轴线微调，点击右侧【✓ 提交施工】并 Submit"),
            "💡 提示：旅馆提供过夜住宿，能恢复游客精力并提高满意度");

    private static final GuideStep TAVERN = new GuideStep(
            "tavern",
            "🚩 新手引导 (8/9)：建造酒馆招募法师",
            List.of(
                    "§a▶ 1. 按快捷键 §e1§a 开启【建造模式】",
                    "§7  2. 找到【冒险者酒馆】并建造",
                    "§7  3. 单击选中，双击进入世界定位",
                    "§8  💬 视角操作：按住右键拖动移动/旋转视角，WASD 移动镜头"),
            List.of(
                    "§7✓ 1. 已开启建造模式",
                    "§a▶ 2. 找到并§e单击【冒险者酒馆】§7选中",
                    "§a▶ 3. §e双击卡片§7进入 3D 放置"),
            List.of(
                    "§7✓ 1. 酒馆虚影正跟随准心移动",
                    "§a▶ 2. §e左键点击§7旋转朝向，按 §eEnter§7 锁定或提交"),
            List.of(
                    "§7✓ 1. 酒馆位置已锁定",
                    "§a▶ 2. 拖拽 3D 轴线微调，点击右侧【✓ 提交施工】并 Submit"),
            "💡 提示：招募一名法师后引导前进（满满意度的游客法师会留履历免费招募）");

    private static final GuideStep LEVEL_UP = new GuideStep(
            "level_up",
            "🚩 新手引导 (9/9)：魔法小镇升级到 2 级",
            List.of(
                    "§7✓ 主要设施与建筑已齐备",
                    "§a▶ 1. 完善配套设施提升游客满意度至 100%",
                    "§7  2. 满意游客离场后魔法小镇获得经验并自动升级",
                    "§8  💬 视角操作：按住右键拖动旋转视角，按 2 进入道路模式铺路"),
            List.of(
                    "§7✓ 主要设施与建筑已齐备",
                    "§a▶ 1. 完善配套设施提升游客满意度至 100%",
                    "§7  2. 满意游客离场后魔法小镇获得经验并自动升级",
                    "§8  💬 提示：按 2 铺设道路，按 3 可查看经营统计"),
            List.of(
                    "§7✓ 主要设施与建筑已齐备",
                    "§a▶ 1. 完善配套设施提升游客满意度至 100%",
                    "§7  2. 满意游客离场后魔法小镇获得经验并自动升级"),
            List.of(
                    "§7✓ 主要设施与建筑已齐备",
                    "§a▶ 1. 完善配套设施提升游客满意度至 100%",
                    "§7  2. 满意游客离场后魔法小镇获得经验并自动升级"),
            "💡 提示：按 2 铺设道路连通建筑，按 3 可随时查看经营统计");

    public static final List<GuideStep> STEPS = List.of(
            TOWN_HALL, WAREHOUSE, NODE, WORKSTATION, CRAFT_STATION,
            SHOP, INN, TAVERN, LEVEL_UP);

    public static GuideStep step(int index) {
        return STEPS.get(index);
    }
}
