package com.wsteam.wandscape.npc.data;

import java.util.UUID;

import javax.annotation.Nullable;

import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.shared.data.AbilitySet;
import com.wsteam.wandscape.shared.data.NpcData;
/**
 * Implementation of {@link NpcData} wrapping a {@link WandscapeNpc}.
 */
public class NpcDataImpl implements NpcData {

    private final UUID npcId;
    private final String name;
    private final int maxHealth;
    private final int currentHealth;
    private final int maxMana;
    private final int currentMana;
    private final int spellPower;
    private final int manaRegenRate;
    private final AbilitySet abilities;
    private final boolean isIdle;
    @Nullable private final UUID assignedHouseId;
    @Nullable private final UUID currentTaskId;
    private final boolean isDead;
    @Nullable private final UUID graveBlockEntityId;

    NpcDataImpl(UUID npcId, String name, int maxHealth, int currentHealth,
                int maxMana, int currentMana, int spellPower, int manaRegenRate,
                AbilitySet abilities, boolean isIdle,
                @Nullable UUID assignedHouseId, @Nullable UUID currentTaskId,
                boolean isDead, @Nullable UUID graveBlockEntityId) {
        this.npcId = npcId;
        this.name = name;
        this.maxHealth = maxHealth;
        this.currentHealth = currentHealth;
        this.maxMana = maxMana;
        this.currentMana = currentMana;
        this.spellPower = spellPower;
        this.manaRegenRate = manaRegenRate;
        this.abilities = abilities;
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
                npc.maxMana,
                npc.currentMana,
                npc.spellPower,
                npc.manaRegenRate,
                AbilitySet.EMPTY, // stage 3+: compute from inventory wands
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
    @Override public int getMaxMana() { return maxMana; }
    @Override public int getCurrentMana() { return currentMana; }
    @Override public int getSpellPower() { return spellPower; }
    @Override public int getManaRegenRate() { return manaRegenRate; }
    @Override public AbilitySet getAbilities() { return abilities; }
    @Override public boolean isIdle() { return isIdle; }
    @Override @Nullable public UUID getAssignedHouseId() { return assignedHouseId; }
    @Override @Nullable public UUID getCurrentTaskId() { return currentTaskId; }
    @Override public boolean isDead() { return isDead; }
    @Override @Nullable public UUID getGraveBlockEntityId() { return graveBlockEntityId; }
}
