package com.wsteam.wandscape.content.npc.data;

import java.util.UUID;
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
    UUID getAssignedHouseId();
    UUID getCurrentTaskId();
    boolean isDead();
    UUID getGraveBlockEntityId();
}
