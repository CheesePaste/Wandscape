package com.wsteam.wandscape.magic.internal;

import java.util.List;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.wsteam.wandscape.magic.data.MagicDef;

/**
 * 统一施法决策脑：给定「已知魔法 + 可施放判定 + 是否有目标」，按列表顺序选第一个
 * 可施放且目标规则满足的魔法；全部不满足返回 null（调用方走兜底：基础攻击/走位/待命）。
 *
 * <p>L1 优先级扫描（docs/spell-casting.md 三层决策）。L0 硬性覆盖（血量危机/LOS/互斥锁）
 * 由调用方（守卫/自防御战斗循环）在调用前处理；P3 起"已知魔法"来自 NPC 的
 * SpellbookComponent 与玩家策略，当前阶段由调用方传入默认列表。
 */
public final class CastBrain {

    private CastBrain() {}

    /** 当前默认战斗魔法列表（[beam]）；beam 定义缺失时为空。 */
    public static List<MagicDef> defaultCombatSpells() {
        MagicDef beam = MagicCaster.beamSpec();
        return beam != null ? List.of(beam) : List.of();
    }

    /**
     * 按 {@code known} 顺序返回第一个门控通过（{@code castable}）且目标规则满足的魔法；
     * 无则 null。调用方拿到结果后自行执行（门控在 {@code MagicState.tryCast} 原子复验，
     * 此处只做选择、不扣资源）。
     *
     * @param castable  MagicDef → 是否满足施法门控（互斥锁 + 该魔法 CD + 蓝够）
     * @param hasTarget 调用方是否已选定有效目标（HOSTILE 系/ALLY 系需要目标，SELF/NONE 不需要）
     */
    @Nullable
    public static MagicDef select(List<MagicDef> known, Predicate<MagicDef> castable, boolean hasTarget) {
        for (MagicDef def : known) {
            if (!castable.test(def)) continue;
            if (requiresTarget(def) && !hasTarget) continue;
            return def;
        }
        return null;
    }

    /** 目标规则是否要求调用方先选定目标。 */
    public static boolean requiresTarget(MagicDef def) {
        return switch (def.targetMode()) {
            case HOSTILE_NEAREST, HOSTILE_LOWEST_HP, ALLY_LOWEST_HP, DEAD_ALLY -> true;
            case SELF, NONE -> false;
        };
    }
}
