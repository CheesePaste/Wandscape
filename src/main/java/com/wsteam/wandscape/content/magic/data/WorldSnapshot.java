package com.wsteam.wandscape.content.magic.data;

import java.util.Set;

/**
 * CastBrain 的决策输入快照（纯数据 record，零 MC 依赖，可单测）。
 *
 * <p>由调用方（守卫/自防御战斗循环）每轮构造喂给 {@code CastBrain.select}，
 * 对照 {@link SpellConditions} 判定"此刻该魔法条件是否满足"。语义见
 * {@code docs/spell-casting.md} 5.3：
 * <ul>
 *   <li>{@code enemyCount}：目标周围敌人数（类别敌数门控用：单发 ≤ 3 / 群发 ≥ 3）</li>
 *   <li>{@code selfHpRatio}：自身血量比例 [0,1]（self_hp_max 用）</li>
 *   <li>{@code allyLowestHpRatio}：友方最低血量比例 [0,1]；无友方 = 1（ally_hp_max 用）</li>
 *   <li>{@code activeEffects}：自身已有状态 id（no_effect 用）</li>
 * </ul>
 */
public record WorldSnapshot(
        int enemyCount,
        float selfHpRatio,
        float allyLowestHpRatio,
        Set<String> activeEffects
) {

    /** 空快照：无敌人、满血、无状态。调用方无法构造时兜底。 */
    public static final WorldSnapshot EMPTY = new WorldSnapshot(0, 1f, 1f, Set.of());

    public WorldSnapshot {
        enemyCount = Math.max(0, enemyCount);
        selfHpRatio = Math.clamp(selfHpRatio, 0f, 1f);
        allyLowestHpRatio = Math.clamp(allyLowestHpRatio, 0f, 1f);
        activeEffects = activeEffects == null ? Set.of() : Set.copyOf(activeEffects);
    }

    /** HOSTILE 系目标规则：半径内有敌对才可施。 */
    public boolean hasHostileTarget() {
        return enemyCount > 0;
    }

    /** ALLY 系目标规则：有受伤友方（最低血 &lt; 1）才可施。 */
    public boolean hasInjuredAlly() {
        return allyLowestHpRatio < 1f;
    }
}
