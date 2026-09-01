package com.wsteam.wandscape.api;

import java.util.List;
import java.util.UUID;

/**
 * 施法决策层对外接口（P3）：查/改 NPC 的已装备魔法载荷与施法策略。
 *
 * <p>载荷 = 已装备魔法（按分类 4 桶、每桶 ≤3，桶内 = 类内优先级），幂等全量重算由服务端
 * {@code EquippedMagicComponent} 校验（未知/ALTAR/SPECIAL 丢、超限去重）。生效的魔法级顺序由
 * {@code CastBrain.resolvePriority} 按预设分类排序解析（未配置走预设推导，已配置的
 * 自定义优先级保留作覆盖）。数据契约见 {@code docs/spell-casting.md} 5.4。
 * 实现方在 {@code magic/internal/SpellcastingApiImpl}。
 */
public interface SpellcastingApi {

    /** NPC 已装备魔法（magicId 顺序 = 分类固定序 × 桶内槽位序）。 */
    List<String> getKnownSpells(UUID npcId);

    /** 当前策略预设名（{@code CastStrategyComponent.Preset} 的大写名）。 */
    String getStrategyPreset(UUID npcId);

    /**
     * 生效的施法优先级（magicId 顺序）——已按玩家策略解析，供 UI 展示。
     * NPC 不存在或组件缺失时返回空列表。
     */
    List<String> getPriority(UUID npcId);

    /**
     * 全量重设已装备魔法载荷 + 策略预设。{@code equipped} 为扁平 magicId 列表（分类固定序 ×
     * 类内槽位序）；服务端按每个魔法真实分类装桶校验（未知丢、ALTAR/SPECIAL 丢、每类 ≤3、去重），
     * 客户端立场不获信任。预设决定跨类施法先后。
     */
    void setEquippedAndStrategy(UUID npcId, String preset, List<String> equipped);
}