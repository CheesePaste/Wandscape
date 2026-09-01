package com.wsteam.wandscape.content.npc.types;

/**
 * Equipment slot on an NPC.
 * <p>
 * {@code WAND} 是法杖格；HEAD/CHEST/LEGS/FEET 对应四个 vanilla 盔甲格——盔甲直接存
 * vanilla 装备槽，护甲值/韧性/移速等原版属性由原版属性系统结算；这四格仅用于把盔甲上的
 * Wandscape 自有属性（铁魔法 MAX_MANA/SPELL_POWER）桥进 ECS。
 */
public enum EquipmentSlot {
    WAND,
    HEAD,
    CHEST,
    LEGS,
    FEET
}
