package com.wsteam.wandscape.shared.ui.panel;

import java.text.Collator;
import java.util.Locale;
import java.util.Set;

/**
 * Pure sort helpers for the build selection bar (V 面板 → Build)。
 * 排序：解锁等级 → 种类 → 显示名（中文按拼音，英文按字母）。
 * 拆成纯 Java 便于不依赖 MC 运行时的单元测试。
 */
public final class BuildingSort {

    /** 拥有独立分类标签的 category；其余（含未知/未来新增）归入基础设施标签。 */
    public static final Set<String> SPECIFIC_CATEGORIES = Set.of(
            "node", "decoration", "shop", "service", "relax", "atm"
    );

    /** 未知基建 category 的排序位：排在已知基建之后、node 之前。 */
    private static final int RANK_UNKNOWN_INFRA = 7;

    private static final Collator NAME_COLLATOR = Collator.getInstance(Locale.CHINA);

    private BuildingSort() {}

    /** 建筑 category 归属的分类标签 id。未知 category → "infrastructure"。 */
    public static String tabOf(String category) {
        if (category != null && SPECIFIC_CATEGORIES.contains(category)) {
            return category;
        }
        return "infrastructure";
    }

    /** 同解锁等级内的种类排序位：基建在前（市政厅→仓库→工作站→…），随后 node/decoration/shop/service/relax/atm。 */
    public static int categoryRank(String category) {
        if (category == null) return RANK_UNKNOWN_INFRA;
        return switch (category) {
            case "government" -> 0;        // 市政厅
            case "storage" -> 1;           // 仓库
            case "workstation" -> 2;       // 工作站
            case "crafting_station" -> 3;  // 合成站
            case "magic_station" -> 4;     // 魔法工坊
            case "tavern" -> 5;            // 酒馆
            case "altar" -> 6;             // 祭坛
            case "node" -> 8;
            case "decoration" -> 9;
            case "shop" -> 10;
            case "service" -> 11;
            case "relax" -> 12;
            case "atm" -> 13;
            default -> RANK_UNKNOWN_INFRA;
        };
    }

    /** 建筑栏三元比较：解锁等级 → 种类 → 名称。 */
    public static int compare(int levelA, String categoryA, String nameA,
                              int levelB, String categoryB, String nameB) {
        if (levelA != levelB) return Integer.compare(levelA, levelB);
        int ca = categoryRank(categoryA);
        int cb = categoryRank(categoryB);
        if (ca != cb) return Integer.compare(ca, cb);
        return NAME_COLLATOR.compare(nameA, nameB);
    }
}
