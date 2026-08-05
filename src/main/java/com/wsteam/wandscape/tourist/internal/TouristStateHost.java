package com.wsteam.wandscape.tourist.internal;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.wsteam.wandscape.shared.data.VisitMemory;

import net.minecraft.core.BlockPos;

/**
 * Common tourist state surface shared by the physical {@code TouristEntity}
 * (loaded path) and {@link TouristShadow} (unloaded sim path).
 *
 * <p>The shared interaction economy ({@link TouristSimulation}) operates on this
 * interface so the sim reuses exactly the same satisfaction / energy / cooldown /
 * wallet logic as the real AI — one implementation, no drift.
 *
 * <p>Cooldowns are stored as an absolute "end time" in the host's own time base:
 * {@link #timeBase()} returns {@code tickCount} for an entity and {@code simTick}
 * for a shadow. Callers compare against {@link #timeBase()} the same way
 * {@code TouristMoveGoal} compares against {@code tourist.tickCount}.
 */
public interface TouristStateHost {

    String getTouristName();

    /** Time base for cooldown comparisons (entity tickCount / shadow simTick). */
    int timeBase();

    int getEnergy();
    void setEnergy(int e);

    int getSatisfaction();
    void setSatisfaction(int s);

    int getLevel();

    int getWallet();
    void setWallet(int w);
    void spendWallet(long amount);

    int getInitialWallet();

    @Nullable UUID getColonyId();

    @Nullable UUID getTargetBuildingId();
    void setTargetBuildingId(@Nullable UUID id);

    @Nullable String getTargetBuildingCategory();
    void setTargetBuildingCategory(@Nullable String cat);

    int getTypePreference(String buildingTypeId);
    void adjustTypePreference(String buildingTypeId, int delta);

    int getServiceCooldown(UUID buildingId);
    void setServiceCooldown(UUID buildingId, int endTime);

    int getServiceCooldownEndTick();
    void setServiceCooldownEndTick(int endTime);

    boolean hasVisitedBuilding(UUID buildingId);
    void addVisitedBuilding(UUID buildingId);
    Set<UUID> getVisitedBuildings();

    void addVisitMemory(VisitMemory memory);
    List<VisitMemory> getRecentVisits();

    @Nullable UUID getCheckedInBuildingId();
    void setCheckedInBuildingId(@Nullable UUID id);
    int getHotelCheckinTime();
    void setHotelCheckinTime(int time);

    boolean isMage();
    boolean isMageResumeStored();
    void setMageResumeStored(boolean v);
    int getMaxMana();
    int getManaRegenRate();
    int getSpellPower();

    long getArrivalTime();
    void setArrivalTime(long t);

    @Nullable BlockPos getCommuteTarget();
    void setCommuteTarget(@Nullable BlockPos t);
}
