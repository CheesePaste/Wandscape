package com.wsteam.wandscape.shared.api;

import java.util.List;
import java.util.UUID;

/**
 * 施法决策层对外接口（P3）：查/改 NPC 的魔法表与施法策略。
 *
 * <p>策略 = 预设（balanced/offensive/support/defensive/custom）+ 自定义优先级列表；
 * 生效的魔法级顺序由 {@code CastBrain.resolvePriority} 按预设分类排序解析。数据契约见
 * {@code docs/spell-casting.md} 5.4。实现方在 {@code magic/internal/SpellcastingApiImpl}。
 */
public interface SpellcastingApi {

    /** NPC 会哪些魔法（magicId 顺序 = spellbook 顺序）。 */
    List<String> getKnownSpells(UUID npcId);

    /** 整体替换 NPC 魔法表。 */
    void setKnownSpells(UUID npcId, List<String> spellIds);

    /** 当前策略预设名（{@code CastStrategyComponent.Preset} 的大写名）。 */
    String getStrategyPreset(UUID npcId);

    /**
     * 生效的施法优先级（magicId 顺序）——已按玩家策略解析，供 UI 展示。
     * NPC 不存在或组件缺失时返回空列表。
     */
    List<String> getPriority(UUID npcId);

    /**
     * 设置策略。{@code preset} 非 {@code CUSTOM} 时忽略 {@code priority}（按预设重算）；
     * 为 {@code CUSTOM} 时用 {@code priority} 作显式魔法级优先级。
     */
    void setStrategy(UUID npcId, String preset, List<String> priority);
}
