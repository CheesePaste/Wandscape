package com.wsteam.wandscape.magic.data;

import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * 魔法内置触发条件（{@code MagicDef.conditions} 的纯数据镜像，缺省 = 无条件）。
 *
 * <p>判定交给 {@link #matches(WorldSnapshot)}：{@code CastBrain} 对照调用方构造的世界快照
 * 判断该魔法此刻是否可施放。语义见 {@code docs/spell-casting.md} 5.2——防御 vs 治疗的血线竞争
 * 靠阈值错开（护盾 {@code self_hp_max} 偏高、治疗 {@code ally_hp_max} 偏低），不靠运行时互斥。
 */
public record SpellConditions(
        int minEnemies,
        @Nullable Float selfHpMax,
        @Nullable Float allyHpMax,
        @Nullable String noEffect
) {

    /** 无条件：所有阈值未设。 */
    public static final SpellConditions NONE = new SpellConditions(0, null, null, null);

    public SpellConditions {
        minEnemies = Math.max(0, minEnemies);
    }

    /**
     * 快照判定：敌数 ≥ min_enemies、自身血量 &lt; self_hp_max、友方最低血 &lt; ally_hp_max、
     * 自身无 no_effect 状态。未设的阈值（null / 0）不参与判定。
     */
    public boolean matches(WorldSnapshot snapshot) {
        WorldSnapshot s = snapshot != null ? snapshot : WorldSnapshot.EMPTY;
        if (minEnemies > 0 && s.enemyCount() < minEnemies) return false;
        if (selfHpMax != null && s.selfHpRatio() >= selfHpMax) return false;
        if (allyHpMax != null && s.allyLowestHpRatio() >= allyHpMax) return false;
        if (noEffect != null && s.activeEffects().contains(noEffect)) return false;
        return true;
    }

    /** 从 JSON 解析；无 {@code conditions} 对象或全空时返回 {@link #NONE}。 */
    public static SpellConditions fromJson(@Nullable JsonElement json) {
        if (json == null || !json.isJsonObject()) return NONE;
        JsonObject obj = json.getAsJsonObject();
        int minEnemies = (int) Math.round(getDouble(obj, "min_enemies", 0));
        Float selfHpMax = obj.has("self_hp_max") ? (float) getDouble(obj, "self_hp_max", 1.0) : null;
        Float allyHpMax = obj.has("ally_hp_max") ? (float) getDouble(obj, "ally_hp_max", 1.0) : null;
        String noEffect = getString(obj, "no_effect", null);
        return new SpellConditions(minEnemies, selfHpMax, allyHpMax, noEffect);
    }

    private static String getString(JsonObject o, String key, @Nullable String def) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : def;
    }

    private static double getDouble(JsonObject o, String key, double def) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsDouble() : def;
    }
}
