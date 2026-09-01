package com.wsteam.wandscape.api;

import com.wsteam.wandscape.content.npc.attributes.NpcAttributes.AttributeType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    // ── 属性整体存取（2 方法，替代逐属性 18 个）──

    /**
     * 整取一名 NPC 的**基础**属性值（{@code Map<AttributeType,Float>} 全量，含隐藏属性）。
     * 返回的是 base（不叠加等级/装备加成），修改后按 {@code NpcAttributes.effective} 公式重算生效值。
     *
     * @return 全属性 base map；NPC 不存在返回空 map
     */
    @Unimplemented("重设计阶段——待接入 WandscapeNpc base attribute 映射")
    default Map<AttributeType, Float> getNpcAttributes(UUID npcId) {
        throw new UnsupportedOperationException("NpcApi.getNpcAttributes not yet implemented");
    }

    /**
     * 整设一名 NPC 的**基础**属性值（全量覆盖；缺省 key 视为 0 或保持？——实现方按「缺省保持不动」处理，
     * 仅更新传入的 key）。建议按各属性 SPEC 上下界 clamp，避免产出非法值。
     *
     * @return 是否全量成功设置；NPC 不存在或因 clamp 全拒返回 false
     */
    @Unimplemented("重设计阶段——待接入 WandscapeNpc.setBaseAttributeValue 逐个写")
    default boolean setNpcAttributes(UUID npcId, Map<AttributeType, Float> values) {
        throw new UnsupportedOperationException("NpcApi.setNpcAttributes not yet implemented");
    }

    // ── 等级自由设置（升级/降级统一入口）──

    /** 直接设一名 NPC 等级（≥1，无上限硬约束）；降级/升级均可用，改写 base 与 exp 视实现而定。 */
    @Unimplemented("重设计阶段——待接入 WandscapeNpc.setLevel")
    default void setNpcLevel(UUID npcId, int level) {
        throw new UnsupportedOperationException("NpcApi.setNpcLevel not yet implemented");
    }
    /** 为一名 NPC 训练某属性 {@code steps} 步（消耗殖民地仓库元素）。 */
    @Unimplemented("重设计阶段——待接入 MageHutServerHandler.onTrain")
    default boolean trainNpc(UUID npcId, AttributeType attribute, int steps) {
        throw new UnsupportedOperationException("NpcApi.trainNpc not yet implemented");
    }

    /** 为一名 NPC 升一级（消耗升级资源）。 */
    @Unimplemented("重设计阶段——待接入 MageHutServerHandler.onUpgrade")
    default boolean levelUpNpc(UUID npcId) {
        throw new UnsupportedOperationException("NpcApi.levelUpNpc not yet implemented");
    }
}
