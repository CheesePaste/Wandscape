package com.wsteam.wandscape.api;

import com.wsteam.wandscape.content.npc.attributes.NpcAttributes.AttributeType;
import com.wsteam.wandscape.content.npc.data.NpcData;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Map;
import java.util.UUID;
public interface NpcApi {
    List<NpcData> getColonyNpcs(UUID colonyId);
    List<NpcData> getIdleNpcs(UUID colonyId);

    /** Number of NPCs in a colony (convenience, avoids fetching full list). */
    default int getNpcCount(UUID colonyId) {
        return getColonyNpcs(colonyId).size();
    }

    /** Number of idle NPCs in a colony (convenience). */
    default int getIdleNpcCount(UUID colonyId) {
        return getIdleNpcs(colonyId).size();
    }

    NpcData getNpc(UUID npcId);
    boolean assignHouse(UUID npcId, UUID houseId);

    // ── 可调平衡值（委托 BalanceValues；运行时生效，不追溯已生成实体）──

    int getGuardRange();
    void setGuardRange(int v);
    int getGuardReleaseRange();
    void setGuardReleaseRange(int v);
    int getGuardSelfDefenseRange();
    void setGuardSelfDefenseRange(int v);
    int getGuardHateRange();
    void setGuardHateRange(int v);
    int getGuardHateDurationTicks();
    void setGuardHateDurationTicks(int v);
    int getGuardFollowAttackDurationTicks();
    void setGuardFollowAttackDurationTicks(int v);
    double getGuardKiteStartDist();
    void setGuardKiteStartDist(double v);
    double getGuardKiteStandoff();
    void setGuardKiteStandoff(double v);
    double getGuardEngageStandoff();
    void setGuardEngageStandoff(double v);
    double getGuardFleeHpThreshold();
    void setGuardFleeHpThreshold(double v);
    double getGuardFleeStartDist();
    void setGuardFleeStartDist(double v);
    double getGuardFleeStandoff();
    void setGuardFleeStandoff(double v);
    int getNpcRegenGraceTicks();
    void setNpcRegenGraceTicks(int v);
    int getNpcRegenIntervalTicks();
    void setNpcRegenIntervalTicks(int v);
    int getNpcManaRegenTicks();
    void setNpcManaRegenTicks(int v);
    double getNpcManaRegenFraction();
    void setNpcManaRegenFraction(double v);
    int getReviveNearBuildingRange();
    void setReviveNearBuildingRange(int v);
    double getScepterHostileRange();
    void setScepterHostileRange(double v);
    int getMageHutRestTicks();
    void setMageHutRestTicks(int v);

    // ── 未实现（重设计阶段声明，见 @Unimplemented）──

    /**
     * 在指定位置生成一名属于指定殖民地的 NPC（等级/fixEcsAfterSpawn 由实现方处理）。
     *
     * @return 新 NPC 的 UUID；位置无效或生成失败返回 null
     */
    @Unimplemented("重设计阶段——待接入 WandscapeNpc.spawn + fixEcsAfterSpawn")
    default UUID spawnNpc(UUID colonyId, BlockPos spawnPos) {
        throw new UnsupportedOperationException("NpcApi.spawnNpc not yet implemented");
    }

    /** 把一名 NPC 移出殖民地（掉落装备、不留死亡记录）。 */
    @Unimplemented("重设计阶段——待接入 WandscapeNpc.dismissFromColony")
    default boolean removeNpc(UUID npcId) {
        throw new UnsupportedOperationException("NpcApi.removeNpc not yet implemented");
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
}
