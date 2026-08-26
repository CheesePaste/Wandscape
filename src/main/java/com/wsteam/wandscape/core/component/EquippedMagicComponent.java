package com.wsteam.wandscape.core.component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import javax.annotation.Nullable;

/**
 * NPC 已装备魔法容器（替代 {@code SpellbookComponent}）：按施法分类分 4 桶，每桶 ≤
 * {@link #MAX_PER_CATEGORY}，桶内顺序 = 类内施法优先级（槽位序，先装在前）。纯 Java 零 MC，
 * 由 {@code WandscapeNpc} 持有并 NBT 持久。
 *
 * <p>支持记录原生无等级法术（level=1）与第三方模组（如 Iron's Spells）具有等级（Lv.1~10）
 * 的法术卷轴条目（{@link SpellEntry}）。
 *
 * <p>桶键用分类名小写字符串（core 不依赖 magic 包；分类合法性由服务端校验）。
 * UTILITY 魔法（teleport/revive）不存此容器——导航回退/祭坛属系统固有，不进装备、不占槽位。
 */
public class EquippedMagicComponent {

    /**
     * 单个已装备魔法条目（法术 ID + 等级 + 可选附加数据）。
     */
    public record SpellEntry(String id, int level, @Nullable String customData) {
        public SpellEntry {
            Objects.requireNonNull(id, "magic id cannot be null");
            level = Math.max(1, level);
        }

        public SpellEntry(String id, int level) {
            this(id, level, null);
        }

        public SpellEntry(String id) {
            this(id, 1, null);
        }

        /** 格式化为用于网络包/调试的紧凑字符串（如 "beam" 或 "irons_spellbooks:firebolt@5"）。 */
        public String toFlatString() {
            return level > 1 ? (id + "@" + level) : id;
        }

        /** 从紧凑字符串解析（如 "irons_spellbooks:firebolt@5" -> id="irons_spellbooks:firebolt", level=5）。 */
        public static SpellEntry parse(String flat) {
            if (flat == null || flat.isBlank()) return new SpellEntry("", 1, null);
            int atIdx = flat.lastIndexOf('@');
            if (atIdx > 0 && atIdx < flat.length() - 1) {
                try {
                    int lvl = Integer.parseInt(flat.substring(atIdx + 1));
                    return new SpellEntry(flat.substring(0, atIdx), lvl, null);
                } catch (NumberFormatException ignored) {}
            }
            return new SpellEntry(flat, 1, null);
        }
    }

    /** 每类上限。 */
    public static final int MAX_PER_CATEGORY = 3;

    /** 可装备分类（= {@code MagicDef.Category} 除 UTILITY 的 4 个，小写名，固定顺序）。 */
    public static final List<String> CATEGORIES =
            List.of("single_target", "aoe", "defense", "support");

    /** 新 NPC / 旧存档无字段时的默认装备（beam+heal）。分类由 {@code MagicDef} 数据决定。 */
    public static final List<String> DEFAULT_EQUIP = List.of("beam", "heal");

    private final Map<String, List<SpellEntry>> byCategory = new LinkedHashMap<>();

    public EquippedMagicComponent() {
        for (String cat : CATEGORIES) {
            byCategory.put(cat, new ArrayList<>(MAX_PER_CATEGORY));
        }
    }

    // ── 读 ──

    /** 某分类的已装备魔法 ID 列表（防御性拷贝）；未知分类返回空列表。 */
    public List<String> list(String category) {
        List<SpellEntry> bucket = byCategory.get(category);
        if (bucket == null) return List.of();
        List<String> out = new ArrayList<>(bucket.size());
        for (SpellEntry e : bucket) {
            out.add(e.id());
        }
        return List.copyOf(out);
    }

    /** 某分类的已装备魔法完整条目列表（防御性拷贝）；未知分类返回空列表。 */
    public List<SpellEntry> listEntries(String category) {
        List<SpellEntry> bucket = byCategory.get(category);
        return bucket == null ? List.of() : List.copyOf(bucket);
    }

    /** 全部已装备魔法 ID，按分类固定顺序 + 桶内槽位序展平。 */
    public List<String> flattened() {
        List<String> out = new ArrayList<>();
        for (String cat : CATEGORIES) {
            for (SpellEntry e : byCategory.getOrDefault(cat, List.of())) {
                out.add(e.id());
            }
        }
        return out;
    }

    /** 全部已装备魔法完整条目，按分类固定顺序 + 桶内槽位序展平。 */
    public List<SpellEntry> flattenedEntries() {
        List<SpellEntry> out = new ArrayList<>();
        for (String cat : CATEGORIES) {
            out.addAll(byCategory.getOrDefault(cat, List.of()));
        }
        return out;
    }

    /** 是否已装备该魔法（跨桶查询）。 */
    public boolean knows(String magicId) {
        if (magicId == null) return false;
        for (String cat : CATEGORIES) {
            for (SpellEntry e : byCategory.getOrDefault(cat, List.of())) {
                if (e.id().equals(magicId)) return true;
            }
        }
        return false;
    }

    /** 获取已装备法术的完整条目；未装备返回 null。 */
    @Nullable
    public SpellEntry getEntry(String magicId) {
        if (magicId == null) return null;
        for (String cat : CATEGORIES) {
            for (SpellEntry e : byCategory.getOrDefault(cat, List.of())) {
                if (e.id().equals(magicId)) return e;
            }
        }
        return null;
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

    /** 装备进某分类桶尾（level=1）。非法分类 / 已装 / 桶满返回 false。 */
    public boolean equip(String category, String magicId) {
        if (magicId == null || magicId.isBlank()) return false;
        return equip(category, new SpellEntry(magicId, 1, null));
    }

    /** 装备进某分类桶尾（带等级）。非法分类 / 已装 / 桶满返回 false。 */
    public boolean equip(String category, String magicId, int level) {
        if (magicId == null || magicId.isBlank()) return false;
        return equip(category, new SpellEntry(magicId, level, null));
    }

    /** 装备进某分类桶尾。非法分类 / 已装 / 桶满返回 false。 */
    public boolean equip(String category, SpellEntry entry) {
        if (entry == null || entry.id().isBlank()) return false;
        List<SpellEntry> bucket = byCategory.get(category);
        if (bucket == null) return false;
        if (knows(entry.id())) return false;
        if (bucket.size() >= MAX_PER_CATEGORY) return false;
        bucket.add(entry);
        return true;
    }

    /** 从某分类桶卸载。未装返回 false。 */
    public boolean unequip(String category, String magicId) {
        if (magicId == null) return false;
        List<SpellEntry> bucket = byCategory.get(category);
        if (bucket == null) return false;
        return bucket.removeIf(e -> e.id().equals(magicId));
    }

    /** 桶内上移一位（类内优先级提高）。未装或已在首位返回 false。 */
    public boolean moveUp(String category, String magicId) {
        List<SpellEntry> bucket = byCategory.get(category);
        if (bucket == null) return false;
        int i = indexOf(bucket, magicId);
        if (i <= 0) return false;
        java.util.Collections.swap(bucket, i, i - 1);
        return true;
    }

    /** 桶内下移一位（类内优先级降低）。未装或已在末位返回 false。 */
    public boolean moveDown(String category, String magicId) {
        List<SpellEntry> bucket = byCategory.get(category);
        if (bucket == null) return false;
        int i = indexOf(bucket, magicId);
        if (i < 0 || i >= bucket.size() - 1) return false;
        java.util.Collections.swap(bucket, i, i + 1);
        return true;
    }

    private static int indexOf(List<SpellEntry> list, String magicId) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(magicId)) return i;
        }
        return -1;
    }

    /** 清空所有桶。 */
    public void clear() {
        for (List<SpellEntry> bucket : byCategory.values()) {
            bucket.clear();
        }
    }

    /** 全量替换为另一容器的内容（服务端权威状态落地到实体组件）。 */
    public void replaceWith(EquippedMagicComponent other) {
        clear();
        if (other == null) return;
        for (String cat : CATEGORIES) {
            List<SpellEntry> bucket = other.byCategory.getOrDefault(cat, List.of());
            for (SpellEntry entry : bucket) {
                byCategory.get(cat).add(entry);
            }
        }
    }

    /** 服务端权威重算：从扁平 id 列表（支持带 @level 格式）按分类装桶（未知丢、非战斗丢、每类≤3、去重）。 */
    public static EquippedMagicComponent fromFlat(@Nullable List<String> flat,
                                                  @Nullable Function<String, String> categoryOf) {
        EquippedMagicComponent out = new EquippedMagicComponent();
        if (flat == null || categoryOf == null) return out;
        for (String s : flat) {
            if (s == null) continue;
            SpellEntry entry = SpellEntry.parse(s);
            if (entry.id().isBlank()) continue;
            String cat = categoryOf.apply(entry.id());
            if (cat == null) continue;
            out.equip(cat, entry);
        }
        return out;
    }

    /** 服务端权威重算：从扁平 SpellEntry 列表按分类装桶。 */
    public static EquippedMagicComponent fromFlatEntries(@Nullable List<SpellEntry> flat,
                                                         @Nullable Function<String, String> categoryOf) {
        EquippedMagicComponent out = new EquippedMagicComponent();
        if (flat == null || categoryOf == null) return out;
        for (SpellEntry entry : flat) {
            if (entry == null || entry.id().isBlank()) continue;
            String cat = categoryOf.apply(entry.id());
            if (cat == null) continue;
            out.equip(cat, entry);
        }
        return out;
    }
}