package com.wsteam.wandscape.shared.data;

import java.util.UUID;
public interface NpcData {
    UUID getNpcId();
    String getName();
    int getMaxHealth();
    int getCurrentHealth();
    int getMaxMana();
    int getCurrentMana();
    int getSpellPower();
    int getManaRegenRate();
    boolean isIdle();
    UUID getAssignedHouseId();
    UUID getCurrentTaskId();
    boolean isDead();
    UUID getGraveBlockEntityId();
}
