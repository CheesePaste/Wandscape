package com.wsteam.wandscape.api;
import com.wsteam.wandscape.content.building.data.BuildingData;
import com.wsteam.wandscape.foundation.util.NameStyle;

import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.UUID;
public interface ColonyApi {
    /** Register a new colony at the given origin. Returns its UUID. */
    default UUID createColony(BlockPos origin) {
        return createColony(origin, null);
    }

    /** Register a new colony at the given origin, recording the founding player. */
    UUID createColony(BlockPos origin, @Nullable UUID founder);

    /** The founding player UUID of a colony, or null if unknown (legacy/console-created). */
    @Nullable
    UUID getFounder(UUID colonyId);

    /** The colony founded by the given player (one player = one colony), or null. */
    @Nullable
    UUID getColonyByFounder(UUID founder);

    /** Find the nearest colony UUID within 256 blocks of pos, or null. */
    UUID getColonyId(BlockPos pos);

    /** Remove a colony and clear its building associations. */
    void deleteColony(UUID colonyId);

    /** True if pos is a registered colony origin. */
    boolean isColonyOrigin(BlockPos pos);

    /** Returns all registered colony UUIDs. Empty if no colonies exist. */
    Collection<UUID> getAllColonyIds();

    /** Character naming rule for future tourist/NPC names (default FANTASY). */
    com.wsteam.wandscape.foundation.util.NameStyle getNamingStyle(UUID colonyId);

    /** Change the colony's character naming rule (only affects future names). */
    void setNamingStyle(UUID colonyId, com.wsteam.wandscape.foundation.util.NameStyle style);

    /** Current colony level (1..max), or 0 when no such colony exists. */
    int getColonyLevel(UUID colonyId);

    /** Current colony experience, or 0 when no such colony exists. */
    int getColonyExp(UUID colonyId);

    /** Programmatically grant experience to a colony (respects max level, may trigger level-up). */
    void grantExperience(UUID colonyId, int amount);

    // ── 未实现（重设计阶段声明，见 @Unimplemented）──

    /** 殖民地显示名（当前经内部 ColonyLevelManager；未知殖民地返回空串）。 */
    @Unimplemented("重设计阶段——待接入 ColonyLevelManager.getColonyName")
    default String getColonyName(UUID colonyId) {
        throw new UnsupportedOperationException("ColonyApi.getColonyName not yet implemented");
    }

    /** 设置殖民地显示名。 */
    @Unimplemented("重设计阶段——待接入 ColonyLevelManager.setColonyName")
    default void setColonyName(UUID colonyId, String name) {
        throw new UnsupportedOperationException("ColonyApi.setColonyName not yet implemented");
    }

    /** 殖民地等级上限（全局配置）。 */
    @Unimplemented("重设计阶段——待接入 Config.COLONY_MAX_LEVEL")
    default int getMaxLevel() {
        throw new UnsupportedOperationException("ColonyApi.getMaxLevel not yet implemented");
    }

    /** 升至下一级所需经验（未知殖民地返回 0）。 */
    @Unimplemented("重设计阶段——待接入 ColonyLevelManager.expToNextLevel")
    default int getExpToNext(UUID colonyId) {
        throw new UnsupportedOperationException("ColonyApi.getExpToNext not yet implemented");
    }

    /** 殖民地当前是否激活（创始人在线且殖民地未冻结）。 */
    @Unimplemented("重设计阶段——待接入 ColonyActivation.isColonyActive")
    default boolean isActive(UUID colonyId) {
        throw new UnsupportedOperationException("ColonyApi.isActive not yet implemented");
    }

    /** 强制冻结/解冻殖民地（覆盖默认派生规则）。 */
    @Unimplemented("重设计阶段——待接入 per-colony 冻结开关")
    default void setActive(UUID colonyId, boolean active) {
        throw new UnsupportedOperationException("ColonyApi.setActive not yet implemented");
    }

    /**
     * 自由设置殖民地等级（1..{@link #getMaxLevel()}），**升级/降级统一入口**：
     * 可把高等级殖民地直接降回低等级（如 1 级），经验同步按实现方策略重置/缩放。
     *
     * @return 设置成功返回 true；殖民地不存在或越界返回 false
     */
    @Unimplemented("重设计阶段——待接入 ColonyLevelManager.setLevel")
    default boolean setColonyLevel(UUID colonyId, int level) {
        throw new UnsupportedOperationException("ColonyApi.setColonyLevel not yet implemented");
    }
}
