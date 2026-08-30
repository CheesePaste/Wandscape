package com.wsteam.wandscape.magic.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import javax.annotation.Nullable;

/**
 * 魔法内置触发条件（{@code MagicDef.conditions} 的纯数据镜像，缺省 = 无条件）。
 *
 * <p>判定交给 {@link #matches(WorldSnapshot)}：{@code CastBrain} 对照调用方构造的世界快照
 * 判断该魔法此刻是否可施放。只承载「血线」（self_hp_max / ally_hp_max）与「效果不重放」
 * （no_effect）两类；敌数门控由 {@code CastBrain} 按类别统一判定（单发 ≤ 阈值、群发 ≥ 阈值），
 * 用 {@code min_enemies} 的按魔法配置已移除。语义见 {@code docs/spell-casting.md} 5.2。
 */
public record SpellConditions(
        @Nullable Float selfHpMax,
        @Nullable Float allyHpMax,
        @Nullable String noEffect
) {

    /** 无条件：所有阈值未设。 */
    public static final SpellConditions NONE = new SpellConditions(null, null, null);

    /**
     * 快照判定：自身血量 &lt; self_hp_max、友方最低血 &lt; ally_hp_max、
     * 自身无 no_effect 状态。未设的阈值（null）不参与判定。
     */
    public boolean matches(WorldSnapshot snapshot) {
        WorldSnapshot s = snapshot != null ? snapshot : WorldSnapshot.EMPTY;
        if (selfHpMax != null && s.selfHpRatio() >= selfHpMax) return false;
        if (allyHpMax != null && s.allyLowestHpRatio() >= allyHpMax) return false;
        return noEffect == null || !s.activeEffects().contains(noEffect);
    }

    /** 从 JSON 解析；无 {@code conditions} 对象或全空时返回 {@link #NONE}。 */
    public static SpellConditions fromJson(@Nullable JsonElement json) {
        if (json == null || !json.isJsonObject()) return NONE;
        JsonObject obj = json.getAsJsonObject();
        Float selfHpMax = obj.has("self_hp_max") ? (float) getDouble(obj, "self_hp_max", 1.0) : null;
        Float allyHpMax = obj.has("ally_hp_max") ? (float) getDouble(obj, "ally_hp_max", 1.0) : null;
        String noEffect = getString(obj, "no_effect", null);
        return new SpellConditions(selfHpMax, allyHpMax, noEffect);
    }

    private static String getString(JsonObject o, String key, @Nullable String def) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : def;
    }

    private static double getDouble(JsonObject o, String key, double def) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsDouble() : def;
    }
}
