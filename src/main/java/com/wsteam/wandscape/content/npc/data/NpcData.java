package com.wsteam.wandscape.content.npc.data;

import com.wsteam.wandscape.content.npc.attributes.NpcAttributes.AttributeType;
import com.wsteam.wandscape.content.npc.component.EquippedMagicComponent;
import com.wsteam.wandscape.content.npc.entity.WandscapeNpc;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * NPC 只读数据契约（addon 查询面）——纯字段快照，不做任何功能。
 *
 * <p>2026-09-02 裁定：NpcData 是 {@code NpcApi} 读方法（getNpc/getColonyNpcs/getIdleNpcs）的
 * 返回类型，是「单个实体、单个快照时刻」的投影。凡取值 → 字段（受 {@link #from(WandscapeNpc)}
 * 组装）；凡是跨实体/跨系统判定、要上下文、或改状态 → {@code NpcApi}/{@code NpcAttributesApi} 的
 * 方法。读模型不承担任何行为，因此转 record：
 *
 * <ul>
 *   <li>{@link #attributes()} 是唯一属性读面：effective 全量（含隐藏属性），增删
 *       {@link AttributeType} 时 NpcData 形状零改动——不再为每个属性开一个 getter。</li>
 *   <li>逐属性读法（getSpellPower/getWorkSpeed/getSpellSpeed/getArmorValue）与同值的
 *       getMaxHealth/getMaxMana 已删除，改经 {@link #attributes()} 按 type 读；写侧（训练/升级/
 *       设属性）归 {@code NpcAttributesApi}，读侧不承担。</li>
 * </ul>
 */
public record NpcData(
        UUID npcId,
        String name,
        int currentHealth,               // 实体当下血（资源，非属性；上限取 attributes().get(MAX_HP)）
        boolean isIdle,
        UUID currentTaskId,
        boolean isDead,
        int level,
        float mana,                      // 当前魔力（资源，非属性；上限取 attributes().get(MAX_MANA)）
        List<String> spells,             // 已装备魔法 id（按类内优先级展开）
        Map<AttributeType, Float> attributes   // effective 全量（含隐藏）——唯一属性读面
) {
    /** 防御性拷贝：外部改动不影响快照；null 落空表不崩。 */
    public NpcData {
        currentHealth = Math.max(0, currentHealth);
        level = Math.max(1, level);
        spells = spells == null ? List.of() : List.copyOf(spells);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    /** 从活体实体组装快照（NpcApiImpl 查询入口）。属性读 effective 生效值，与面板/装备一致。 */
    public static NpcData from(WandscapeNpc npc) {
        EnumMap<AttributeType, Float> attrs = new EnumMap<>(AttributeType.class);
        for (AttributeType t : AttributeType.values()) {
            attrs.put(t, npc.getEffectiveAttribute(t));
        }
        return new NpcData(
                npc.getUUID(),
                npc.getNpcName(),
                (int) npc.getHealth(),
                npc.isEngineIdle(),
                npc.getCurrentTaskId(),
                npc.isDeadOrDying(),
                npc.getLevel(),
                npc.getCurrentMana(),
                npc.equippedMagic.flattenedEntries().stream()
                        .map(EquippedMagicComponent.SpellEntry::id)
                        .toList(),
                attrs
        );
    }
}