package com.wsteam.wandscape.tourist.internal;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.wsteam.wandscape.shared.data.Activity;
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
    float getMaxHp();
    float getMoveSpeed();
    float getSpellPower();
    float getWorkSpeed();
    float getSpellSpeed();
    float getArmor();
    float getMaxMana();

    long getArrivalTime();
    void setArrivalTime(long t);

    @Nullable BlockPos getCommuteTarget();
    void setCommuteTarget(@Nullable BlockPos t);

    // ── 三条需求条（fill/need）——Block 0 契约，Block 2 实现实体存储 ──

    default int getComfortSat() { return 0; }
    default void setComfortSat(int v) {}
    default int getMagicSat() { return 0; }
    default void setMagicSat(int v) {}
    default int getWonderSat() { return 0; }
    default void setWonderSat(int v) {}
    default int getComfortNeed() { return 100; }
    default void setComfortNeed(int v) {}
    default int getMagicNeed() { return 100; }
    default void setMagicNeed(int v) {}
    default int getWonderNeed() { return 100; }
    default void setWonderNeed(int v) {}

    // ── 活动状态（占位做动作）──

    default Activity getCurrentActivity() { return null; }
    default void setCurrentActivity(Activity a) {}
    default int getActivityTicks() { return 0; }
    default void setActivityTicks(int t) {}
    default int getOccupiedSpot() { return -1; }
    default void setOccupiedSpot(int i) {}

    // ── 停留 ──

    default int getNightsStayed() { return 0; }
    default void setNightsStayed(int n) {}
    default long getDepartureDeadline() { return Long.MAX_VALUE; }
    default void setDepartureDeadline(long t) {}

    // ── 满条判定：三条 ratio 全 1 ──

    default boolean isFullySatisfied() { return false; }

    // ── 总旅费（ATM 取现来源；初始 = startingWallet × TOURIST_ATM_TRAVEL_FUND_MULTIPLIER）──

    default int getTravelFund() { return 0; }
    default void setTravelFund(int v) {}
}
