package com.wsteam.wandscape.api;

import com.wsteam.wandscape.content.npc.attributes.NpcAttributes.AttributeType;

import java.util.List;

/**
 * NPC 属性规则公开契约（纯 Java、零 MC）。
 *
 * <p>供整合包/附属模组在 <b>mod 初始化时</b>读取或覆盖属性规则：每属性的上下界、每级加成、
 * 训练步进、默认 base，以及训练/升级成本曲线。覆盖在后续的招募掷点、训练、升级、复活重算中
 * 生效；已生成实体的 base 由 vanilla AttributeMap 持有，不受覆盖回写影响。
 *
 * <p>获取：{@code WandscapeApis.getNpcAttributesApi()}。
 */
public interface NpcAttributesApi {

    // ── 查询 ──

    /** 该属性是否可见（面板显示/可训练/可升级）。隐藏属性仅被装备或外部修饰符改动。 */
    boolean isVisible(AttributeType type);

    /** 可见属性（面板显示顺序）。 */
    List<AttributeType> visible();

    /** 招募掷点区间下界。 */
    float lower(AttributeType type);

    /** 训练/掷点区间上界。 */
    float upper(AttributeType type);

    /** 每级加成。 */
    float perLevel(AttributeType type);

    /** 单次训练步进。 */
    float trainStep(AttributeType type);

    /** 默认 base（实体未招募/未覆盖时用）。 */
    float defaultFor(AttributeType type);

    /** effective = base + perLevel×(level−1) + equipBonus。 */
    float effective(AttributeType type, float base, int level, float equipBonus);

    // ── 覆盖（mod 初始化时调用）──

    /** 覆盖一个属性的上下界/每级加成/训练步进。 */
    void overrideSpec(AttributeType type, float lower, float upper, float perLevel, float trainStep);

    /** 撤销单属性覆盖，恢复默认曲线。 */
    void resetSpec(AttributeType type);

    /** 覆盖单属性默认 base。 */
    void setDefault(AttributeType type, float value);

    /** 撤销单属性默认覆盖。 */
    void resetDefault(AttributeType type);

    /** 覆盖成本曲线：trainBase 首步单价、trainGrowth 步进指数、upgradeBase 升级基数、maxTrainSteps 每属性训练步数。 */
    void overrideCosts(double trainBase, double trainGrowth, long upgradeBase, int maxTrainSteps);

    /** 撤销成本覆盖，恢复默认。 */
    void resetCosts();
}
