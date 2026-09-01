package com.wsteam.wandscape.content.npc.data;
import com.wsteam.wandscape.content.npc.types.AttributeType;

import com.wsteam.wandscape.content.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.content.npc.data.NpcData;

import javax.annotation.Nullable;
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
    @Nullable private final UUID assignedHouseId;
    @Nullable private final UUID currentTaskId;
    private final boolean isDead;
    @Nullable private final UUID graveBlockEntityId;

    NpcDataImpl(UUID npcId, String name, int maxHealth, int currentHealth,
                float spellPower, float workSpeed, float spellSpeed, float armorValue,
                boolean isIdle,
                @Nullable UUID assignedHouseId, @Nullable UUID currentTaskId,
                boolean isDead, @Nullable UUID graveBlockEntityId) {
        this.npcId = npcId;
        this.name = name;
        this.maxHealth = maxHealth;
        this.currentHealth = currentHealth;
        this.spellPower = spellPower;
        this.workSpeed = workSpeed;
        this.spellSpeed = spellSpeed;
        this.armorValue = armorValue;
        this.isIdle = isIdle;
        this.assignedHouseId = assignedHouseId;
        this.currentTaskId = currentTaskId;
        this.isDead = isDead;
        this.graveBlockEntityId = graveBlockEntityId;
    }

    /** Build from a live NPC entity. */
    public static NpcDataImpl from(WandscapeNpc npc) {
        return new NpcDataImpl(
                npc.getUUID(),
                npc.getNpcName(),
                (int) npc.getMaxHealth(),
                (int) npc.getHealth(),
                npc.getEffectiveAttribute(com.wsteam.wandscape.content.npc.types.AttributeType.SPELL_POWER),
                npc.getEffectiveAttribute(com.wsteam.wandscape.content.npc.types.AttributeType.WORK_SPEED),
                npc.getEffectiveAttribute(com.wsteam.wandscape.content.npc.types.AttributeType.SPELL_SPEED),
                npc.getEffectiveArmorValue(),
                npc.isEngineIdle(),
                null,            // stage 4+: house binding
                npc.getCurrentTaskId(),
                npc.isDeadOrDying(),
                null             // stage 4+: grave BE
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
    @Override @Nullable public UUID getAssignedHouseId() { return assignedHouseId; }
    @Override @Nullable public UUID getCurrentTaskId() { return currentTaskId; }
    @Override public boolean isDead() { return isDead; }
    @Override @Nullable public UUID getGraveBlockEntityId() { return graveBlockEntityId; }
}
