package org.magiccolony.core.types;

/**
 * Identifier for an entity effect (damage, heal, follow, etc.).
 */
public record EffectId(String id) {

    public static final EffectId DAMAGE = new EffectId("damage");
    public static final EffectId HEAL = new EffectId("heal");
    public static final EffectId FOLLOW = new EffectId("follow");
    public static final EffectId SIT = new EffectId("sit");
    public static final EffectId BUFF = new EffectId("buff");

    @Override
    public String toString() {
        return id;
    }
}
