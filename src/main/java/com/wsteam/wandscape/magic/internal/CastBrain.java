package com.wsteam.wandscape.magic.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.wsteam.wandscape.core.component.CastStrategyComponent;
import com.wsteam.wandscape.core.component.EquippedMagicComponent;
import com.wsteam.wandscape.magic.data.MagicDef;
import com.wsteam.wandscape.magic.data.WorldSnapshot;

/**
 * 统一施法决策脑：给定「已知魔法 + 可施放判定 + 世界快照」，按策略解析出的优先级顺序选第一个
 * 可施放、目标规则命中且条件满足的魔法；全部不满足返回 null（调用方走兜底：基础攻击/走位/待命）。
 *
 * <p>L1 优先级扫描（docs/spell-casting.md 三层决策）。L0 硬性覆盖（血量危机/LOS/互斥锁）
 * 由调用方（守卫/自防御战斗循环）在调用前处理。已知魔法来自 NPC 的
 * {@code EquippedMagicComponent}（已装备载荷，分 4 类、每类 ≤3，桶内=类内优先级）
 * 与玩家策略（{@link #resolvePriority}），替代硬编码 {@code [beam]}。SPECIAL 魔法
 * （teleport/heal）与 ALTAR 魔法（revive）不在装备载荷中——导航回退/祭坛/紧急奶/脱战自奶属系统固有，由 L0/独立路径处理。
 */
public final class CastBrain {

    private CastBrain() {}

    /** 预设 → 分类级默认排序（高→低）。SPECIAL/ALTAR 不进预设表（L0 硬性路径/祭坛管）。 */
    private static final Map<CastStrategyComponent.Preset, List<MagicDef.Category>> PRESET_ORDER = Map.of(
            CastStrategyComponent.Preset.OFFENSIVE, List.of(
                    MagicDef.Category.SINGLE_TARGET, MagicDef.Category.AOE,
                    MagicDef.Category.DEFENSE, MagicDef.Category.SUPPORT),
            CastStrategyComponent.Preset.BALANCED, List.of(
                    MagicDef.Category.AOE, MagicDef.Category.SINGLE_TARGET,
                    MagicDef.Category.SUPPORT, MagicDef.Category.DEFENSE),
            CastStrategyComponent.Preset.SUPPORT, List.of(
                    MagicDef.Category.SUPPORT, MagicDef.Category.DEFENSE,
                    MagicDef.Category.AOE, MagicDef.Category.SINGLE_TARGET),
            CastStrategyComponent.Preset.DEFENSIVE, List.of(
                    MagicDef.Category.DEFENSE, MagicDef.Category.SUPPORT,
                    MagicDef.Category.AOE, MagicDef.Category.SINGLE_TARGET));

    /**
     * 把 NPC 的 EquippedMagicComponent 容器解析为有效魔法定义列表（支持原生魔法与铁魔法动态合成）。
     */
    public static List<MagicDef> knownSpells(EquippedMagicComponent equipped) {
        List<MagicDef> out = new ArrayList<>();
        if (equipped == null) return out;
        for (String cat : EquippedMagicComponent.CATEGORIES) {
            for (EquippedMagicComponent.SpellEntry entry : equipped.listEntries(cat)) {
                MagicDef def = SpellbookLoader.getSpec(entry.id());
                if (def != null) {
                    out.add(def);
                } else if (com.wsteam.wandscape.compat.ironspellbooks.IronSpellsCompat.isLoaded()) {
                    MagicDef syn = com.wsteam.wandscape.compat.ironspellbooks.IronSpellsHelper
                            .getSyntheticDef(entry.id(), entry.level(), cat);
                    if (syn != null) {
                        out.add(syn);
                    }
                }
            }
        }
        return out;
    }

    /**
     * 把魔法 id 列表解析为魔法定义列表（SpellbookLoader 查表，缺失跳过）。
     */
    public static List<MagicDef> knownSpells(List<String> ids) {
        List<MagicDef> out = new ArrayList<>();
        if (ids != null) {
            for (String s : ids) {
                if (s == null || s.isBlank()) continue;
                EquippedMagicComponent.SpellEntry entry = EquippedMagicComponent.SpellEntry.parse(s);
                MagicDef def = SpellbookLoader.getSpec(entry.id());
                if (def != null) {
                    out.add(def);
                } else if (com.wsteam.wandscape.compat.ironspellbooks.IronSpellsCompat.isLoaded()) {
                    MagicDef syn = com.wsteam.wandscape.compat.ironspellbooks.IronSpellsHelper
                            .getSyntheticDef(entry.id(), entry.level(), "single_target");
                    if (syn != null) {
                        out.add(syn);
                    }
                }
            }
        }
        return out;
    }

    /**
     * 按玩家策略解析出有效施法优先级（魔法级顺序）：
     * <ul>
     *   <li>已配置（{@code CastStrategyComponent.configured()}）：用 {@code customPriority}
     *       显式顺序过滤到 known 返回；空列表 = 全部停用（不兜底，NPC 走基础攻击）。</li>
     *   <li>未配置：按 {@link #PRESET_ORDER} 预设分类顺序，类内按 {@code known} 顺序，
     *       SPECIAL/ALTAR 类不进列表。</li>
     * </ul>
     * CUSTOM 预设保留仅为旧存档兼容：已配置时走显式列表，未配置时回退 balanced 推导。
     */
    public static List<MagicDef> resolvePriority(@Nullable CastStrategyComponent strategy, List<MagicDef> known) {
        CastStrategyComponent s = strategy != null ? strategy : new CastStrategyComponent();
        if (s.configured()) {
            return filterToKnown(s.customPriority(), known);
        }
        return resolvePreset(s.preset(), known);
    }

    /** 把 magicId 顺序表过滤到 known（按显式顺序、跳过未知），返回零个到多个魔法定义。 */
    private static List<MagicDef> filterToKnown(List<String> ids, List<MagicDef> known) {
        List<MagicDef> out = new ArrayList<>();
        for (String id : ids) {
            for (MagicDef def : known) {
                if (def.id().equals(id)) {
                    out.add(def);
                    break;
                }
            }
        }
        return out;
    }

    private static List<MagicDef> resolvePreset(CastStrategyComponent.Preset preset, List<MagicDef> known) {
        List<MagicDef.Category> order = PRESET_ORDER.getOrDefault(preset,
                PRESET_ORDER.get(CastStrategyComponent.Preset.BALANCED));
        List<MagicDef> out = new ArrayList<>();
        for (MagicDef.Category category : order) {
            for (MagicDef def : known) {
                if (def.category() == category) out.add(def);
            }
        }
        return out;
    }

    /**
     * 按 {@code known} 顺序返回第一个门控通过（{@code castable}）、目标规则命中且条件满足的魔法；
     * 无则 null。调用方拿到结果后自行执行（门控在 {@code MagicState.tryCast} 原子复验，
     * 此处只做选择、不扣资源）。
     *
     * <p>{@code altarOnly} 魔法（如复活）只允许祭坛施放，NPC 直接施法永不选中；SPECIAL 魔法
     * （heal/teleport）由 L0/独立路径（紧急奶/脱战自奶/导航回退）触发，同样不进自动决策表——
     * 防御性保证其不会进守卫/自防御的 L1 优先级扫描。
     *
     * @param castable  MagicDef → 是否满足施法门控（互斥锁 + 该魔法 CD + 蓝够）
     * @param snapshot  世界快照（敌数/自血/友方最低血/状态），驱动目标规则与 {@code conditions}
     */
    @Nullable
    public static MagicDef select(List<MagicDef> known, Predicate<MagicDef> castable, WorldSnapshot snapshot) {
        WorldSnapshot s = snapshot != null ? snapshot : WorldSnapshot.EMPTY;
        for (MagicDef def : known) {
            if (def.altarOnly()) continue;
            if (def.category() == MagicDef.Category.SPECIAL) continue;
            if (!castable.test(def)) continue;
            if (!targetAvailable(def, s)) continue;
            if (!def.conditions().matches(s)) continue;
            return def;
        }
        return null;
    }

    /** 快照下目标规则是否命中（SELF/NONE 恒真，DEAD_ALLY 走祭坛永不自选）。 */
    static boolean targetAvailable(MagicDef def, WorldSnapshot s) {
        return switch (def.targetMode()) {
            case HOSTILE_NEAREST, HOSTILE_LOWEST_HP -> s.hasHostileTarget();
            case ALLY_LOWEST_HP -> s.hasInjuredAlly();
            case DEAD_ALLY -> false;
            case SELF, NONE -> true;
        };
    }

    /** 目标规则是否要求调用方先选定目标（纯模式判定）。 */
    public static boolean requiresTarget(MagicDef def) {
        return switch (def.targetMode()) {
            case HOSTILE_NEAREST, HOSTILE_LOWEST_HP, ALLY_LOWEST_HP, DEAD_ALLY -> true;
            case SELF, NONE -> false;
        };
    }
}
