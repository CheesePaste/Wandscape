package com.wsteam.wandscape.magic.data;

import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * 魔法定义（{@code data/wandscape/magic_spells/*.json} 的纯数据镜像）。
 * 决策层与效果分发靠它取蓝耗/冷却/射程/目标规则；视觉参数（法阵/颜色）放 {@code effect}
 * 供 BeamOp 等执行器消费。与 {@link MagicCircleSpec} 同模式：record 风格 + fromJson 套默认值。
 *
 * <p>门控执行（施法互斥锁 + 每魔法独立 CD + 魔力）仍在 {@code MagicState}，这里只定义"是什么"。
 * 数据契约见 {@code docs/spell-casting.md}。
 */
public record MagicDef(
        String id,
        Category category,
        int manaCost,
        int baseCooldown,
        double range,
        TargetMode targetMode,
        @Nullable String effectCircleId,
        @Nullable Integer effectColor,
        boolean altarOnly,
        int altarCooldown,
        int altarDuration
) {

    public MagicDef {
        manaCost = Math.max(0, manaCost);
        baseCooldown = Math.max(0, baseCooldown);
        range = Math.max(0, range);
        altarCooldown = Math.max(0, altarCooldown);
        altarDuration = Math.max(0, altarDuration);
    }

    /**
     * 施法分类：决定策略预设的默认排序与 UI 分组，不承载触发逻辑
     * （触发逻辑 = target_mode + conditions，见 docs/spell-casting.md）。
     * BUFF 与 HEAL 合并为 SUPPORT。
     */
    public enum Category { SINGLE_TARGET, AOE, DEFENSE, SUPPORT, UTILITY }

    /** 目标规则：决定"何时算有有效目标"。 */
    public enum TargetMode { HOSTILE_NEAREST, HOSTILE_LOWEST_HP, ALLY_LOWEST_HP, SELF, NONE, DEAD_ALLY }

    public static MagicDef fromJson(String id, JsonElement json) {
        JsonObject obj = json.getAsJsonObject();
        String defId = getString(obj, "id", id);
        Category category = parseEnum(getString(obj, "category", "single_target"), Category.SINGLE_TARGET);
        int manaCost = (int) Math.round(getDouble(obj, "mana_cost", 0));
        int baseCooldown = (int) Math.round(getDouble(obj, "base_cooldown", 0));
        double range = getDouble(obj, "range", 0);
        TargetMode targetMode = parseEnum(getString(obj, "target_mode", "none"), TargetMode.NONE);

        String circleId = null;
        Integer color = null;
        if (obj.has("effect") && obj.get("effect").isJsonObject()) {
            JsonObject effect = obj.getAsJsonObject("effect");
            circleId = getString(effect, "circle_id", null);
            color = parseColorHex(getString(effect, "color", null));
        }
        boolean altarOnly = getBool(obj, "altar_only", false);
        int altarCooldown = (int) Math.round(getDouble(obj, "altar_cooldown", 0));
        int altarDuration = (int) Math.round(getDouble(obj, "altar_duration", 0));
        return new MagicDef(defId, category, manaCost, baseCooldown, range, targetMode,
                circleId, color, altarOnly, altarCooldown, altarDuration);
    }

    /** "#A8E0FF" → 0xFFA8E0FF；非法/缺失返回 null。 */
    @Nullable
    public static Integer parseColorHex(@Nullable String hex) {
        if (hex != null && hex.length() == 7 && hex.charAt(0) == '#') {
            try {
                return 0xFF000000 | Integer.parseInt(hex.substring(1), 16);
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private static <E extends Enum<E>> E parseEnum(String s, E fallback) {
        if (s == null) return fallback;
        try {
            return Enum.valueOf(fallback.getDeclaringClass(), s.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }

    private static String getString(JsonObject o, String key, @Nullable String def) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : def;
    }

    private static double getDouble(JsonObject o, String key, double def) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsDouble() : def;
    }

    private static boolean getBool(JsonObject o, String key, boolean def) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsBoolean() : def;
    }
}
