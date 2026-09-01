package com.wsteam.wandscape.content.npc.attributes;

import com.wsteam.wandscape.content.element.data.ElementType;
import com.wsteam.wandscape.content.npc.data.RecruitmentCandidate;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一个概念唯一命名类：NPC 属性的全套规则。
 *
 * <p>六段（种类 / 上下界 / 默认值 / roll / 升级 / 招募）全部收敛在本文件——改任何属性规则
 * 只动这一个类。纯 Java，零 MC 运行时依赖：{@link AttributeType} 仅作枚举键。
 *
 * <p>属性分两类：
 * <ul>
 *   <li>7 个可见属性（{@link #ORDER}）：有上下界/每级加成/训练步进，可招募掷点、可训练、
 *       可随等级提升，在法师小屋等面板显示。</li>
 *   <li>2 个隐藏属性（HEALTH_REGEN / MANA_REGEN）：不显示、不可训练、无 SPECS，base 恒为
 *       默认 1.0，只被装备/外部模组的修饰符改动（回血/回蓝倍率）。</li>
 * </ul>
 *
 * <p>各规则表是「默认值 + 覆盖层」结构：整合包/附属模组经 {@code WandscapeApis.getNpcAttributesApi()}
 * 在 mod 初始化时覆盖（见 {@link #overrideSpec}/{@link #overrideDefault}/{@link #overrideCosts}），
 * 覆盖在后续招募掷点/训练/升级/复活重算中生效；已生成实体的 base 由 vanilla AttributeMap 持有，
 * 不受覆盖回写影响。
 */
public final class NpcAttributes {
    private NpcAttributes() {}

    // ============================================================
    // 种类：9 项，7 可见 + 2 隐藏
    // ============================================================

    public enum AttributeType {
        MAX_HP,
        MOVE_SPEED,
        SPELL_POWER,
        WORK_SPEED,
        SPELL_SPEED,
        ARMOR_VALUE,
        MAX_MANA,
        HEALTH_REGEN,
        MANA_REGEN;

        /** 是否在面板显示/可训练/可升级。隐藏属性仅被装备或外部修饰符改动。 */
        public boolean isVisible() {
            return NpcAttributes.ORDER.contains(this);
        }
    }

    /** 可见属性（面板显示顺序）＝ 可训练/可升级/有 SPECS 的属性集。隐藏属性不在此表。 */
    public static final List<AttributeType> ORDER = List.of(
            AttributeType.MAX_HP,
            AttributeType.MOVE_SPEED,
            AttributeType.SPELL_POWER,
            AttributeType.WORK_SPEED,
            AttributeType.SPELL_SPEED,
            AttributeType.ARMOR_VALUE,
            AttributeType.MAX_MANA);

    // ============================================================
    // 上下界 / 每级加成 / 训练步进（SPECS，仅 7 个可见属性）
    // ============================================================

    private static final float EPS = 1e-4f;

    /** Per-attribute curve: base range, per-level bonus, per-train step. */
    public record AttrSpec(float lower, float upper, float perLevel, float trainStep) {}

    /** 默认曲线。MOVE_SPEED / ARMOR_VALUE 的每级加成是废案（perLevel=0），不应用。 */
    private static final Map<AttributeType, AttrSpec> BASE_SPECS = Map.of(
            AttributeType.MAX_HP,       new AttrSpec(20f, 40f,  2f,    1f),
            AttributeType.MOVE_SPEED,   new AttrSpec(0.2f, 0.4f, 0f, 0.01f),
            AttributeType.SPELL_POWER,  new AttrSpec(0.5f, 1.5f, 0.05f, 0.05f),
            AttributeType.WORK_SPEED,   new AttrSpec(0.5f, 1.5f, 0.05f, 0.05f),
            AttributeType.SPELL_SPEED,  new AttrSpec(0.5f, 1.5f, 0.05f, 0.05f),
            AttributeType.ARMOR_VALUE,  new AttrSpec(0f,   10f,  0f,  0.5f),
            AttributeType.MAX_MANA,     new AttrSpec(150f, 250f, 15f,   5f));

    /** API/整合包覆盖层（mod 初始化时写入，运行期只读）。 */
    private static final Map<AttributeType, AttrSpec> SPEC_OVERRIDES = new ConcurrentHashMap<>();

    /** 有效曲线 = 覆盖优先，未覆盖用默认。隐藏属性无 SPECS，返回 null。 */
    public static AttrSpec spec(AttributeType type) {
        AttrSpec override = SPEC_OVERRIDES.get(type);
        return override != null ? override : BASE_SPECS.get(type);
    }

    public static float lower(AttributeType type) { return spec(type).lower(); }
    public static float upper(AttributeType type) { return spec(type).upper(); }
    public static float perLevel(AttributeType type) { return spec(type).perLevel(); }
    public static float trainStep(AttributeType type) { return spec(type).trainStep(); }

    /** 覆盖一个属性的上下界/每级加成/训练步进（mod 初始化时调用）。 */
    public static void overrideSpec(AttributeType type, float lower, float upper, float perLevel, float trainStep) {
        SPEC_OVERRIDES.put(type, new AttrSpec(lower, upper, perLevel, trainStep));
    }

    /** 撤销单属性覆盖，恢复默认曲线。 */
    public static void resetSpec(AttributeType type) {
        SPEC_OVERRIDES.remove(type);
    }

    // ============================================================
    // 默认值（实体 base 未招募/未覆盖时用）
    // ============================================================

    /** 默认 base。隐藏属性恒为 1.0（无招募掷点、无训练）。ARMOR 默认 5。 */
    private static final Map<AttributeType, Float> BASE_DEFAULTS = Map.of(
            AttributeType.MAX_HP,       30f,
            AttributeType.MOVE_SPEED,   0.3f,
            AttributeType.SPELL_POWER,  1f,
            AttributeType.WORK_SPEED,   1f,
            AttributeType.SPELL_SPEED,  1f,
            AttributeType.ARMOR_VALUE,  5f,
            AttributeType.MAX_MANA,     200f,
            AttributeType.HEALTH_REGEN, 1f,
            AttributeType.MANA_REGEN,   1f);

    private static final Map<AttributeType, Float> DEFAULT_OVERRIDES = new ConcurrentHashMap<>();

    /** 每属性默认 base（覆盖优先）。 */
    public static float defaultFor(AttributeType type) {
        Float override = DEFAULT_OVERRIDES.get(type);
        return override != null ? override : BASE_DEFAULTS.get(type);
    }

    /** 覆盖单属性默认 base（mod 初始化时调用）。 */
    public static void overrideDefault(AttributeType type, float value) {
        DEFAULT_OVERRIDES.put(type, value);
    }

    /** 撤销单属性默认覆盖。 */
    public static void resetDefault(AttributeType type) {
        DEFAULT_OVERRIDES.remove(type);
    }

    // ============================================================
    // 招募 roll（游客与酒馆招募共用同一公式）
    // ============================================================

    /**
     * Roll a mage candidate at the given level. Each attribute draws an
     * independent random factor — skew (r²) for most, uniform for move speed —
     * within its SPECS range; the level adds {@code perLevel × (level−1)}.
     * MOVE_SPEED / ARMOR_VALUE 的每级加成是废案（perLevel=0），不加。
     */
    public static RecruitmentCandidate roll(int level, Random random) {
        int safeLevel = Math.max(1, level);
        int lvl = safeLevel - 1;
        float maxHp = (float) Math.round(20 + 20 * skew(random)) + lvl * 2f;        // 20–40 + 2/级
        float maxMana = (float) Math.round(150 + 100 * skew(random)) + lvl * 15f;   // 150–250 + 15/级
        float moveSpeed = 0.2f + random.nextFloat() * 0.2f;                         // 0.2–0.4，每级 0
        float spellPower = round2(0.5f + (float) skew(random) + lvl * 0.05f);       // 0.5–1.5 + 0.05/级
        float workSpeed = round2(0.5f + (float) skew(random) + lvl * 0.05f);
        float spellSpeed = round2(0.5f + (float) skew(random) + lvl * 0.05f);
        float armorValue = (float) Math.round(10 * skew(random));                   // 0–10，每级 0
        return new RecruitmentCandidate(safeLevel, maxHp, moveSpeed, spellPower,
                workSpeed, spellSpeed, armorValue, maxMana, List.of());
    }

    /** 偏斜随机因子：random×random ∈ [0,1)，多数偏向低值、偶发接近 1。 */
    private static double skew(Random random) {
        double r = random.nextDouble();
        return r * r;
    }

    /** 保留两位小数。 */
    private static float round2(float v) {
        return Math.round(v * 100f) / 100f;
    }

    // ============================================================
    // 升级 / 训练成本与 effective 公式
    // ============================================================

    /** 默认成本曲线：首步单价、步进指数、升级基数、每属性训练步数。 */
    private static final double BASE_TRAIN_BASE = 500.0;
    private static final double BASE_TRAIN_GROWTH = 1.28;
    private static final long BASE_UPGRADE_BASE = 150;
    private static final int BASE_MAX_TRAIN_STEPS = 20;

    private static volatile double trainBase = BASE_TRAIN_BASE;
    private static volatile double trainGrowth = BASE_TRAIN_GROWTH;
    private static volatile long upgradeBase = BASE_UPGRADE_BASE;
    private static volatile int maxTrainSteps = BASE_MAX_TRAIN_STEPS;

    /** 覆盖训练/升级成本曲线（mod 初始化时调用）。 */
    public static void overrideCosts(double trainBase, double trainGrowth, long upgradeBase, int maxTrainSteps) {
        NpcAttributes.trainBase = trainBase;
        NpcAttributes.trainGrowth = trainGrowth;
        NpcAttributes.upgradeBase = upgradeBase;
        NpcAttributes.maxTrainSteps = Math.max(1, maxTrainSteps);
    }

    /** 撤销成本覆盖，恢复默认。 */
    public static void resetCosts() {
        trainBase = BASE_TRAIN_BASE;
        trainGrowth = BASE_TRAIN_GROWTH;
        upgradeBase = BASE_UPGRADE_BASE;
        maxTrainSteps = BASE_MAX_TRAIN_STEPS;
    }

    /** Per-attribute training element set (2 distinct elements each; all 7 elements appear twice). */
    private static final Map<AttributeType, List<ElementType>> TRAIN_ELEMENTS = Map.of(
            AttributeType.MAX_HP,      List.of(ElementType.EARTH, ElementType.METAL),
            AttributeType.MOVE_SPEED,  List.of(ElementType.WOOD, ElementType.WIND),
            AttributeType.SPELL_POWER, List.of(ElementType.FIRE, ElementType.DARK),
            AttributeType.WORK_SPEED,  List.of(ElementType.EARTH, ElementType.WOOD),
            AttributeType.SPELL_SPEED, List.of(ElementType.WIND, ElementType.WATER),
            AttributeType.ARMOR_VALUE, List.of(ElementType.METAL, ElementType.DARK),
            AttributeType.MAX_MANA,    List.of(ElementType.FIRE, ElementType.WATER));

    /** Elements consumed by one training step of the given attribute. */
    public static List<ElementType> trainElements(AttributeType type) {
        return TRAIN_ELEMENTS.get(type);
    }

    /** Elements consumed by a mage level-up: all seven, evenly. */
    public static List<ElementType> upgradeElements() {
        return List.of(ElementType.values());
    }

    /**
     * 0-based training step index for a base value, clamped to
     * {@code [0, maxTrainSteps-1]}. Step 0 (base at lower) is the cheapest,
     * step {@code maxTrainSteps-1} (base near upper) the most expensive.
     */
    public static int trainStepIndex(AttributeType type, float base) {
        int idx = Math.round((base - lower(type)) / trainStep(type));
        return Math.max(0, Math.min(maxTrainSteps - 1, idx));
    }

    /** Per-element cost of the next training step from the given base. */
    public static long trainCostPerElement(AttributeType type, float base) {
        return Math.round(trainBase * Math.pow(trainGrowth, trainStepIndex(type, base)));
    }

    /** Per-element cost of leveling from {@code level} to {@code level + 1}. */
    public static long upgradeCostPerElement(int level) {
        return upgradeBase * (Math.max(0, level) + 1L);
    }

    /**
     * Effective attribute = base + perLevel × (level−1) + equipBonus.
     * {@code base} is the current (possibly trained) base; it is not clamped here
     * because an older mage whose rolled value already exceeded {@code upper}
     * (historical baked level bonus) must keep its full value.
     */
    public static float computeEffective(AttributeType type, float base, int level, float equipBonus) {
        return base + perLevel(type) * Math.max(0, level - 1) + equipBonus;
    }

    /**
     * Whether the base can still be trained one step (strictly below the upper
     * bound). Uses an epsilon to avoid float-equality dead zones.
     */
    public static boolean canTrain(AttributeType type, float base) {
        return base < upper(type) - EPS;
    }

    /** The next trained base (capped at the upper bound). */
    public static float trainedValue(AttributeType type, float base) {
        return Math.min(base + trainStep(type), upper(type));
    }

    /**
     * Reconstruct the level-1 base from a flat rolled value that already embeds
     * {@code perLevel × (level − 1)}. The result is not clamped so that history
     * (baked bonus from a high-level spawn) is preserved.
     */
    public static float baseFromFlat(AttributeType type, float flat, int level) {
        return flat - perLevel(type) * Math.max(0, level - 1);
    }

    /**
     * A mage may level up until its level reaches {@code colonyLevel + 1} —
     * a level-30 colony can therefore promote mages up to level 31 (where a
     * maxed base HP hits exactly 100: 40 + 2 × 30).
     */
    public static boolean canLevelUp(int level, int colonyLevel) {
        return level <= colonyLevel;
    }
}
