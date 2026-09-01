package com.wsteam.wandscape.content.magic.data;
import com.wsteam.wandscape.content.npc.component.EquippedMagicComponent;
import com.wsteam.wandscape.content.npc.component.MagicState;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 魔法定义（{@code data/wandscape/magic_spells/*.json} 的纯数据镜像）。
 * 决策层与效果分发靠它取蓝耗/冷却/射程/目标规则；视觉参数（法阵/颜色）放 {@code effect}
 * 供 BeamOp 等执行器消费。与 {@link MagicCircleSpec} 同模式：record 风格 + fromJson 套默认值。
 *
 * <p>门控执行（施法互斥锁 + 每魔法独立 CD + 魔力）仍在 {@code MagicState}，这里只定义"是什么"。
 * {@code category} 只表达性质（normal/special/altar）；{@code defaultGroup} 是可选的**默认策略组**
 * （single_target/aoe/defense/support 之一），normal 法术缺省放置组用，供默认装备种子与
 * {@code SpellbookLoader.equippableCategoryOf} 兜底装桶——实际组由玩家在策略页放置决定。
 * {@code description} 是可选的玩家可读介绍文本（魔法卷轴的 JEI 信息页用，缺省 null）。
 * 数据契约见 {@code docs/spell-casting.md}。
 */
public record MagicDef(
        String id,
        Category category,
        int manaCost,
        int baseCooldown,
        int castTime,
        double range,
        TargetMode targetMode,
        @Nullable String effectCircleId,
        @Nullable Integer effectColor,
        @Nullable Double effectDamage,
        boolean altarOnly,
        int altarCooldown,
        int altarDuration,
        SpellConditions conditions,
        @Nullable String defaultGroup,
        @Nullable String description
) {

    public MagicDef {
        manaCost = Math.max(0, manaCost);
        baseCooldown = Math.max(0, baseCooldown);
        castTime = Math.max(0, castTime);
        range = Math.max(0, range);
        altarCooldown = Math.max(0, altarCooldown);
        altarDuration = Math.max(0, altarDuration);
        effectDamage = effectDamage != null && effectDamage < 0 ? null : effectDamage;
        conditions = conditions != null ? conditions : SpellConditions.NONE;
        defaultGroup = defaultGroup == null || defaultGroup.isBlank() ? null : defaultGroup;
        description = description == null || description.isBlank() ? null : description;
    }

    /**
     * 系统固有特殊魔法：所有 NPC 天生固有。heal 额外可经装备槽进入 L1 自动决策（仍有 L0 紧急奶 /
     * 脱战自奶兜底），teleport 保持不进装备、仅导航回退 / 逃生传送触发。顺序即策略 UI「特殊」
     * 面板的展示顺序。
     */
    public static final List<String> SPECIAL_SPELLS = List.of("teleport", "heal");

    /**
     * 施法分类（3 类）：只表达法术**性质**，不再决定敌数门控与预设排序——那两者改由法术所在的
     * 策略组（{@code EquippedMagicComponent} 的 4 桶）驱动（见 docs/spell-casting.md）。
     * NORMAL = 普通战斗法术（放哪个组就按哪个组的敌数门槛与预设位次）；SPECIAL = 系统固有
     * （teleport/heal，heal 可装备，teleport 导航回退）；ALTAR = 祭坛专属（revive）。
     */
    public enum Category { NORMAL, SPECIAL, ALTAR }

    /** 目标规则：决定"何时算有有效目标"。 */
    public enum TargetMode { HOSTILE_NEAREST, HOSTILE_LOWEST_HP, ALLY_LOWEST_HP, SELF, NONE, DEAD_ALLY }

    public static MagicDef fromJson(String id, JsonElement json) {
        JsonObject obj = json.getAsJsonObject();
        String defId = getString(obj, "id", id);
        Category category = parseEnum(getString(obj, "category", "normal"), Category.NORMAL);
        int manaCost = (int) Math.round(getDouble(obj, "mana_cost", 0));
        int baseCooldown = (int) Math.round(getDouble(obj, "base_cooldown", 0));
        int castTime = (int) Math.round(getDouble(obj, "cast_time", 0));
        double range = getDouble(obj, "range", 0);
        TargetMode targetMode = parseEnum(getString(obj, "target_mode", "none"), TargetMode.NONE);

        String circleId = null;
        Integer color = null;
        Double damage = null;
        if (obj.has("effect") && obj.get("effect").isJsonObject()) {
            JsonObject effect = obj.getAsJsonObject("effect");
            circleId = getString(effect, "circle_id", null);
            color = parseColorHex(getString(effect, "color", null));
            damage = effect.has("damage") ? effect.get("damage").getAsDouble() : null;
        }
        boolean altarOnly = getBool(obj, "altar_only", false);
        int altarCooldown = (int) Math.round(getDouble(obj, "altar_cooldown", 0));
        int altarDuration = (int) Math.round(getDouble(obj, "altar_duration", 0));
        SpellConditions conditions = SpellConditions.fromJson(obj.get("conditions"));
        String defaultGroup = getString(obj, "default_group", null);
        String description = getString(obj, "description", null);
        return new MagicDef(defId, category, manaCost, baseCooldown, castTime, range, targetMode,
                circleId, color, damage, altarOnly, altarCooldown, altarDuration, conditions, defaultGroup,
                description);
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
