package com.wsteam.wandscape.content.npc.data;

import com.wsteam.wandscape.content.npc.attributes.NpcAttributes.AttributeType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * NPC 只读数据契约（addon 查询面）。
 *
 * <p>⚠️ 重设计阶段：现有字段多以实现为准；{@link #getLevel()} {@link #getMana()} ...
 * 尚未由 {@code NpcDataImpl} 映射，已声明为 {@literal default} 桩（{@link com.wsteam.wandscape.api.Unimplemented}），
 * 实现落地前调用会抛 {@link UnsupportedOperationException}。
 */
public interface NpcData {
    UUID getNpcId();
    String getName();
    int getMaxHealth();
    int getCurrentHealth();
    float getSpellPower();
    float getWorkSpeed();
    float getSpellSpeed();
    float getArmorValue();
    boolean isIdle();
    UUID getCurrentTaskId();
    boolean isDead();

    // ── 未实现（重设计阶段声明，见 @Unimplemented）──

    /** NPC 等级。 */
    @com.wsteam.wandscape.api.Unimplemented("NpcData 未映射——NpcDataImpl 待补 level")
    default int getLevel() {
        throw new UnsupportedOperationException("NpcData.getLevel not yet implemented");
    }

    /** 当前魔力。 */
    @com.wsteam.wandscape.api.Unimplemented("NpcData 未映射——NpcDataImpl 待补 mana")
    default float getMana() {
        throw new UnsupportedOperationException("NpcData.getMana not yet implemented");
    }

    /** 最大魔力。 */
    @com.wsteam.wandscape.api.Unimplemented("NpcData 未映射——NpcDataImpl 待补 maxMana")
    default float getMaxMana() {
        throw new UnsupportedOperationException("NpcData.getMaxMana not yet implemented");
    }

    /** 已装备魔法 id 列表。 */
    @com.wsteam.wandscape.api.Unimplemented("NpcData 未映射——NpcDataImpl 待补 spells")
    default List<String> getSpells() {
        throw new UnsupportedOperationException("NpcData.getSpells not yet implemented");
    }

    /** 全属性基础值快照（{@code Map<AttributeType,Float>}，含隐藏属性）。 */
    @com.wsteam.wandscape.api.Unimplemented("NpcData 未映射——NpcDataImpl 待补 attributes")
    default Map<AttributeType, Float> getAttributes() {
        throw new UnsupportedOperationException("NpcData.getAttributes not yet implemented");
    }
}
