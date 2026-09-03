package com.wsteam.wandscape.content.magic.internal;
import com.wsteam.wandscape.content.npc.component.MagicState;

import com.wsteam.wandscape.content.npc.component.CastStrategyComponent;
import com.wsteam.wandscape.content.npc.component.EquippedMagicComponent;
import com.wsteam.wandscape.content.magic.data.MagicDef;
import com.wsteam.wandscape.content.magic.data.SpellRef;
import com.wsteam.wandscape.content.magic.data.WorldSnapshot;
import com.wsteam.wandscape.foundation.registry.WandscapeConstants;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 统一施法决策脑：给定「已知魔法 + 可施放判定 + 世界快照」，按策略解析出的优先级顺序选第一个
 * 可施放、目标规则命中且条件满足的魔法；全部不满足返回 null（调用方走兜底：基础攻击/走位/待命）。
 *
 * <p>L1 优先级扫描（docs/spell-casting.md 三层决策）。L0 硬性覆盖（血量危机/LOS/互斥锁）
 * 由调用方（守卫/自防御战斗循环）在调用前处理。已知魔法来自 NPC 的
 * {@code EquippedMagicComponent}（已装备载荷，分 4 策略组、每组 ≤3，组内=组内优先级），
 * 每个法术以 {@link SpellRef} 携带所在策略组——**敌数门控与预设排序都按策略组判**，
 * 非法术自身 {@code MagicDef.category()}（后者只表达 normal/special/altar 性质）。
 * 与玩家策略（{@link #resolvePriority}），替代硬编码 {@code [beam]}。SPECIAL 的 heal 经装备入列后
 * 可进 L1 自动决策；teleport（导航回退）与 ALTAR（revive，祭坛）仍在装备边界被拒、不经此决策。
 */
public final class CastBrain {

    private CastBrain() {}

    /** 预设 → 策略组级默认排序（高→低）。SPECIAL/ALTAR 不进预设表（L0 硬性路径/祭坛管）。 */
    private static final Map<CastStrategyComponent.Preset, List<String>> PRESET_ORDER = Map.of(
            CastStrategyComponent.Preset.OFFENSIVE, List.of("single_target", "aoe", "defense", "support"),
            CastStrategyComponent.Preset.BALANCED, List.of("aoe", "single_target", "support", "defense"),
            CastStrategyComponent.Preset.SUPPORT, List.of("support", "defense", "aoe", "single_target"),
            CastStrategyComponent.Preset.DEFENSIVE, List.of("defense", "support", "aoe", "single_target"));

    /**
     * 把 NPC 的 EquippedMagicComponent 容器解析为带策略组的法术引用列表（支持原生魔法与铁魔法
     * 动态合成）；{@code group} = 法术实际所在的桶名，驱动敌数门控与预设排序。
     */
    public static List<SpellRef> knownSpells(EquippedMagicComponent equipped) {
        return knownSpells(equipped, null);
    }

    /**
     * 把 NPC 的 EquippedMagicComponent 容器与 Curios 魔法书槽中的铭刻法术解析为带策略组的法术引用列表
     * （支持原生魔法与铁魔法动态合成）；{@code group} = 法术实际所在的桶名，驱动敌数门控与预设排序。
     */
    public static List<SpellRef> knownSpells(EquippedMagicComponent equipped, @Nullable com.wsteam.wandscape.content.npc.entity.WandscapeNpc npc) {
        List<SpellRef> out = new ArrayList<>();
        if (equipped != null) {
            for (String group : EquippedMagicComponent.CATEGORIES) {
                for (EquippedMagicComponent.SpellEntry entry : equipped.listEntries(group)) {
                    MagicDef def = SpellbookLoader.getSpec(entry.id());
                    if (def != null) {
                        out.add(new SpellRef(def, group));
                    } else if (com.wsteam.wandscape.compat.ironspellbooks.IronSpellsCompat.isLoaded()
                            && com.wsteam.wandscape.compat.ironspellbooks.IronSpellsHelper.isValidSpell(entry.id())) {
                        MagicDef syn = com.wsteam.wandscape.compat.ironspellbooks.IronSpellsHelper
                                .getSyntheticDef(entry.id(), entry.level(), group);
                        if (syn != null) {
                            out.add(new SpellRef(syn, group));
                        }
                    } else if (com.wsteam.wandscape.compat.goety.GoetyCompat.isLoaded()
                            && com.wsteam.wandscape.compat.goety.GoetyHelper.isValidSpell(entry.id())) {
                        MagicDef syn = com.wsteam.wandscape.compat.goety.GoetyHelper
                                .getSyntheticDef(entry.id(), group, entry.customData());
                        if (syn != null) {
                            out.add(new SpellRef(syn, group));
                        }
                    }
                }
            }
        }
        if (npc != null && com.wsteam.wandscape.compat.curios.CuriosCompat.isLoaded()
                && com.wsteam.wandscape.compat.ironspellbooks.IronSpellsCompat.isLoaded()) {
            net.minecraft.world.item.ItemStack spellbook = com.wsteam.wandscape.compat.curios.CuriosCompat.getEquippedSpellbook(npc);
            if (!spellbook.isEmpty()) {
                var entries = com.wsteam.wandscape.compat.ironspellbooks.IronSpellsHelper.getSpellsFromSpellbook(spellbook);
                java.util.Set<String> alreadyKnown = new java.util.HashSet<>();
                for (SpellRef ref : out) {
                    alreadyKnown.add(ref.def().id());
                }
                for (var entry : entries) {
                    if (alreadyKnown.add(entry.id())) {
                        String group = com.wsteam.wandscape.compat.ironspellbooks.IronSpellsHelper.inferCategory(entry.id());
                        MagicDef syn = com.wsteam.wandscape.compat.ironspellbooks.IronSpellsHelper
                                .getSyntheticDef(entry.id(), entry.level(), group);
                        if (syn != null) {
                            out.add(new SpellRef(syn, group));
                        }
                    }
                }
            }
        }
        return out;
    }

    /**
     * 把魔法 id 列表解析为带策略组的法术引用（SpellbookLoader 查表，缺失跳过；组取
     * {@code equippableCategoryOf} 兜底）。当前无调用方，保留兼容旧入口。
     */
    public static List<SpellRef> knownSpells(List<String> ids) {
        List<SpellRef> out = new ArrayList<>();
        if (ids != null) {
            for (String s : ids) {
                if (s == null || s.isBlank()) continue;
                EquippedMagicComponent.SpellEntry entry = EquippedMagicComponent.SpellEntry.parse(s);
                MagicDef def = SpellbookLoader.getSpec(entry.id());
                if (def != null) {
                    out.add(new SpellRef(def, SpellbookLoader.equippableCategoryOf(entry.id())));
                } else if (com.wsteam.wandscape.compat.ironspellbooks.IronSpellsCompat.isLoaded()
                        && com.wsteam.wandscape.compat.ironspellbooks.IronSpellsHelper.isValidSpell(entry.id())) {
                    MagicDef syn = com.wsteam.wandscape.compat.ironspellbooks.IronSpellsHelper
                            .getSyntheticDef(entry.id(), entry.level(), "single_target");
                    if (syn != null) {
                        out.add(new SpellRef(syn, "single_target"));
                    }
                } else if (com.wsteam.wandscape.compat.goety.GoetyCompat.isLoaded()
                        && com.wsteam.wandscape.compat.goety.GoetyHelper.isValidSpell(entry.id())) {
                    MagicDef syn = com.wsteam.wandscape.compat.goety.GoetyHelper
                            .getSyntheticDef(entry.id(), "single_target", entry.customData());
                    if (syn != null) {
                        out.add(new SpellRef(syn, "single_target"));
                    }
                }
            }
        }
        return out;
    }

    /**
     * 按玩家策略解析出有效施法优先级（法术级顺序，SpellRef 携带策略组）：
     * <ul>
     *   <li>已配置（{@code CastStrategyComponent.configured()}）：用 {@code customPriority}
     *       显式顺序过滤到 known 返回；空列表 = 全部停用（不兜底，NPC 走基础攻击）。</li>
     *   <li>未配置：按 {@link #PRESET_ORDER} 预设策略组顺序，组内按 {@code known} 顺序，
     *       SPECIAL/ALTAR 类不进列表。</li>
     * </ul>
     * CUSTOM 预设保留仅为旧存档兼容：已配置时走显式列表，未配置时回退 balanced 推导。
     */
    public static List<SpellRef> resolvePriority(@Nullable CastStrategyComponent strategy, List<SpellRef> known) {
        CastStrategyComponent s = strategy != null ? strategy : new CastStrategyComponent();
        if (s.configured()) {
            return filterToKnown(s.customPriority(), known);
        }
        return resolvePreset(s.preset(), known);
    }

    /** 把 magicId 顺序表过滤到 known（按显式顺序、跳过未知），返回零个到多个法术引用。 */
    private static List<SpellRef> filterToKnown(List<String> ids, List<SpellRef> known) {
        List<SpellRef> out = new ArrayList<>();
        for (String id : ids) {
            for (SpellRef ref : known) {
                if (ref.def().id().equals(id)) {
                    out.add(ref);
                    break;
                }
            }
        }
        return out;
    }

    private static List<SpellRef> resolvePreset(CastStrategyComponent.Preset preset, List<SpellRef> known) {
        List<String> order = PRESET_ORDER.getOrDefault(preset,
                PRESET_ORDER.get(CastStrategyComponent.Preset.BALANCED));
        List<SpellRef> out = new ArrayList<>();
        for (String group : order) {
            for (SpellRef ref : known) {
                if (group.equals(ref.group())) out.add(ref);
            }
        }
        return out;
    }

    /**
     * 按 {@code known} 顺序返回第一个门控通过（{@code castable}）、目标规则命中且条件满足的法术；
     * 全部不满足返回 null。调用方拿到结果后自行执行（门控在 {@code MagicState.tryCast} 原子复验，
     * 此处只做选择、不扣资源）。
     *
     * <p><b>敌数门控是优先级降级，不是硬性禁用</b>：敌数与策略组不匹配的法术（如敌数 &gt; 3 只剩
     * 单体攻击组、敌数 &lt; 3 只剩群体攻击组）不直接跳过，而是记作最低优先级候选——仅在没有任何
     * 敌数匹配的法术可用时才选中它，避免「只剩单体攻击却一个也不放」的僵局。
     *
     * <p>{@code altarOnly} 魔法（如复活）只允许祭坛施放，NPC 直接施法永不选中。SPECIAL 的 heal 经
     * 装备进入优先级列表后允许被选中（L0 紧急奶/脱战自奶仍走独立硬性路径兜底）；teleport 在装备
     * 边界被拒，不会出现在这里。
     *
     * @param castable  MagicDef → 是否满足施法门控（互斥锁 + 该魔法 CD + 蓝够）
     * @param snapshot  世界快照（敌数/自血/友方最低血/状态），驱动目标规则与 {@code conditions}
     */
    @Nullable
    public static SpellRef select(List<SpellRef> known, Predicate<MagicDef> castable, WorldSnapshot snapshot) {
        WorldSnapshot s = snapshot != null ? snapshot : WorldSnapshot.EMPTY;
        SpellRef demoted = null; // 敌数与策略组不匹配的最高优先级候选（见 enemyCountGate）
        for (SpellRef ref : known) {
            MagicDef def = ref.def();
            if (def.altarOnly()) continue;
            if (!castable.test(def)) continue;
            if (!targetAvailable(def, s)) continue;
            if (!def.conditions().matches(s)) continue;
            if (enemyCountGate(ref, s)) {
                return ref;
            }
            if (demoted == null) demoted = ref; // 敌数不匹配：不硬禁用，降级为最低优先级兜底
        }
        return demoted;
    }

    /** 敌数是否与所在**策略组**匹配（docs/spell-casting.md）：单体攻击组敌数 ≤ 阈值、群体攻击组
     *  敌数 ≥ 阈值、防御/支援组恒匹配。组由玩家放置决定，非法术自身 category。不匹配 = 最低优先级，
     *  由 {@link #select} 降级兜底，非硬性禁用。 */
    static boolean enemyCountGate(SpellRef ref, WorldSnapshot s) {
        return switch (ref.group() == null ? "" : ref.group()) {
            case "single_target" -> s.enemyCount() <= com.wsteam.wandscape.foundation.util.BalanceValues.castSingleTargetMaxEnemies();
            case "aoe" -> s.enemyCount() >= com.wsteam.wandscape.foundation.util.BalanceValues.castAoeMinEnemies();
            default -> true;
        };
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
