package com.wsteam.wandscape.core.component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import javax.annotation.Nullable;

/**
 * NPC 已装备魔法容器（替代 {@code SpellbookComponent}）：按施法分类分 4 桶，每桶 ≤
 * {@link #MAX_PER_CATEGORY}，桶内顺序 = 类内施法优先级（槽位序，先装在前）。纯 Java 零 MC，
 * 由 {@code WandscapeNpc} 持有并 NBT 持久。
 *
 * <p>桶键用分类名小写字符串（core 不依赖 magic 包；分类合法性由服务端对 MagicDef 校验，
 * 参考 {@link #fromFlat} 的 category 解析器）。SPECIAL（teleport/heal）与 ALTAR（revive）
 * 魔法不存此容器——导航回退/祭坛/紧急奶/脱战自奶属系统固有，不进装备、不占槽位。
 */
public class EquippedMagicComponent {

    /** 每类上限。 */
    public static final int MAX_PER_CATEGORY = 3;

    /** 可装备分类（= {@code MagicDef.Category} 除 SPECIAL/ALTAR 的 4 个，小写名，固定顺序）。 */
    public static final List<String> CATEGORIES =
            List.of("single_target", "aoe", "defense", "support");

    /** 殖民地初始法师（3 名）默认装备（beam+meteor）；酒馆招募法师无起始战斗魔法，由招募路径清空。 */
    public static final List<String> DEFAULT_EQUIP = List.of("beam", "meteor");

    private final Map<String, List<String>> byCategory = new LinkedHashMap<>();

    public EquippedMagicComponent() {
        for (String cat : CATEGORIES) {
            byCategory.put(cat, new ArrayList<>(MAX_PER_CATEGORY));
        }
    }

    // ── 读 ──

    /** 某分类的已装备魔法（防御性拷贝）；未知分类返回空列表。 */
    public List<String> list(String category) {
        List<String> bucket = byCategory.get(category);
        return bucket == null ? List.of() : List.copyOf(bucket);
    }

    /** 全部已装备魔法，按分类固定顺序 + 桶内槽位序展平。 */
    public List<String> flattened() {
        List<String> out = new ArrayList<>();
        for (String cat : CATEGORIES) {
            out.addAll(byCategory.getOrDefault(cat, List.of()));
        }
        return out;
    }

    /** 是否已装备该魔法（跨桶查询）。 */
    public boolean knows(String magicId) {
        if (magicId == null) return false;
        for (String cat : CATEGORIES) {
            if (byCategory.getOrDefault(cat, List.of()).contains(magicId)) return true;
        }
        return false;
    }

    /** 是否一件装备都没有（空 = 待 seed 默认 / 玩家全卸）。 */
    public boolean isEmpty() {
        for (String cat : CATEGORIES) {
            if (!byCategory.getOrDefault(cat, List.of()).isEmpty()) return false;
        }
        return true;
    }

    /** 分类名是否为可装备分类。 */
    public static boolean isCategory(@Nullable String name) {
        return name != null && CATEGORIES.contains(name);
    }

    // ── 写 ──

    /** 装备进某分类桶尾（类内优先级最低）。非法分类 / 已装 / 桶满返回 false。 */
    public boolean equip(String category, String magicId) {
        if (magicId == null || magicId.isBlank()) return false;
        List<String> bucket = byCategory.get(category);
        if (bucket == null) return false;
        if (knows(magicId)) return false;
        if (bucket.size() >= MAX_PER_CATEGORY) return false;
        bucket.add(magicId);
        return true;
    }

    /** 从某分类桶卸载。未装返回 false。 */
    public boolean unequip(String category, String magicId) {
        if (magicId == null) return false;
        List<String> bucket = byCategory.get(category);
        if (bucket == null) return false;
        return bucket.remove(magicId);
    }

    /** 桶内上移一位（类内优先级提高）。未装或已在首位返回 false。 */
    public boolean moveUp(String category, String magicId) {
        List<String> bucket = byCategory.get(category);
        if (bucket == null) return false;
        int i = bucket.indexOf(magicId);
        if (i <= 0) return false;
        java.util.Collections.swap(bucket, i, i - 1);
        return true;
    }

    /** 桶内下移一位（类内优先级降低）。未装或已在末位返回 false。 */
    public boolean moveDown(String category, String magicId) {
        List<String> bucket = byCategory.get(category);
        if (bucket == null) return false;
        int i = bucket.indexOf(magicId);
        if (i < 0 || i >= bucket.size() - 1) return false;
        java.util.Collections.swap(bucket, i, i + 1);
        return true;
    }

    /** 清空所有桶。 */
    public void clear() {
        for (List<String> bucket : byCategory.values()) {
            bucket.clear();
        }
    }

    /** 全量替换为另一容器的内容（服务端权威状态落地到实体组件）。 */
    public void replaceWith(EquippedMagicComponent other) {
        clear();
        if (other == null) return;
        for (String cat : CATEGORIES) {
            List<String> bucket = other.byCategory.getOrDefault(cat, List.of());
            for (String id : bucket) {
                byCategory.get(cat).add(id);
            }
        }
    }

    /** 服务端权威重算：从扁平 id 列表按真实分类装桶（未知丢、非战斗丢、每类≤3、去重）。 */
    public static EquippedMagicComponent fromFlat(@Nullable List<String> flat,
                                                  @Nullable Function<String, String> categoryOf) {
        EquippedMagicComponent out = new EquippedMagicComponent();
        if (flat == null || categoryOf == null) return out;
        for (String id : flat) {
            if (id == null) continue;
            String cat = categoryOf.apply(id);
            if (cat == null) continue;
            out.equip(cat, id);
        }
        return out;
    }
}