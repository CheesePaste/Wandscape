package com.wsteam.wandscape.content.npc.data;
import com.wsteam.wandscape.content.npc.attributes.NpcAttributes.AttributeType;

import com.wsteam.wandscape.content.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.content.npc.data.NpcData;

import java.util.UUID;
/**
 * Implementation of {@link NpcData} wrapping a {@link WandscapeNpc}.
 */
public class NpcDataImpl implements NpcData {

    private final UUID npcId;
    private final String name;
    private final int maxHealth;
    private final int currentHealth;
    private final float spellPower;
    private final float workSpeed;
    private final float spellSpeed;
    private final float armorValue;
    private final boolean isIdle;
    private final UUID currentTaskId;
    private final boolean isDead;

    NpcDataImpl(UUID npcId, String name, int maxHealth, int currentHealth,
                float spellPower, float workSpeed, float spellSpeed, float armorValue,
                boolean isIdle, UUID currentTaskId, boolean isDead) {
        this.npcId = npcId;
        this.name = name;
        this.maxHealth = maxHealth;
        this.currentHealth = currentHealth;
        this.spellPower = spellPower;
        this.workSpeed = workSpeed;
        this.spellSpeed = spellSpeed;
        this.armorValue = armorValue;
        this.isIdle = isIdle;
        this.currentTaskId = currentTaskId;
        this.isDead = isDead;
    }

    /** Build from a live NPC entity. */
    public static NpcDataImpl from(WandscapeNpc npc) {
        return new NpcDataImpl(
                npc.getUUID(),
                npc.getNpcName(),
                (int) npc.getMaxHealth(),
                (int) npc.getHealth(),
                npc.getEffectiveAttribute(com.wsteam.wandscape.content.npc.attributes.NpcAttributes.AttributeType.SPELL_POWER),
                npc.getEffectiveAttribute(com.wsteam.wandscape.content.npc.attributes.NpcAttributes.AttributeType.WORK_SPEED),
                npc.getEffectiveAttribute(com.wsteam.wandscape.content.npc.attributes.NpcAttributes.AttributeType.SPELL_SPEED),
                npc.getEffectiveArmorValue(),
                npc.isEngineIdle(),
                npc.getCurrentTaskId(),
                npc.isDeadOrDying()
        );
    }

    // ---- NpcData interface accessors ----

    @Override public UUID getNpcId() { return npcId; }
    @Override public String getName() { return name; }
    @Override public int getMaxHealth() { return maxHealth; }
    @Override public int getCurrentHealth() { return currentHealth; }
    @Override public float getSpellPower() { return spellPower; }
    @Override public float getWorkSpeed() { return workSpeed; }
    @Override public float getSpellSpeed() { return spellSpeed; }
    @Override public float getArmorValue() { return armorValue; }
    @Override public boolean isIdle() { return isIdle; }
    @Override public UUID getCurrentTaskId() { return currentTaskId; }
    @Override public boolean isDead() { return isDead; }
}
