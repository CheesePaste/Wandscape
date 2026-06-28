package com.wsteam.wandscape.tourist.internal;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.building.internal.BuildingSavedData;
import com.wsteam.wandscape.building.internal.BuildingState;
import com.wsteam.wandscape.shared.data.ServiceConfig;
import com.wsteam.wandscape.tourist.entity.TouristEntity;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Manages hotel/inn stays for tourists.
 *
 * <p>Hotels are service buildings with {@link ServiceConfig#maxOccupancy()} &gt; 0.
 * Tourists check in at night to recover energy and gain satisfaction.
 * Checked-in tourists are stored in a per-building occupancy set.
 *
 * <p>Heartbeat recovers energy each tick for all checked-in tourists.
 * On morning (dayTime &lt; 200), tourists are automatically checked out.
 */
public final class HotelStayHandler {
    private static final String TAG = "HotelStayHandler";

    /** buildingId → set of checked-in tourist UUIDs */
    private final Map<UUID, Set<UUID>> occupancy = new ConcurrentHashMap<>();
    /** touristId → buildingId for reverse lookup */
    private final Map<UUID, UUID> touristToHotel = new ConcurrentHashMap<>();

    private int tickCounter;

    @Nullable
    private static HotelStayHandler active;

    private HotelStayHandler() {}

    @Nullable
    public static HotelStayHandler getActive() { return active; }

    public static HotelStayHandler register() {
        var instance = new HotelStayHandler();
        active = instance;
        NeoForge.EVENT_BUS.register(instance);
        return instance;
    }

    // ── Check-in / Check-out ──

    /**
     * Attempt to check a tourist into a hotel.
     *
     * @return true if check-in succeeded
     */
    public boolean checkIn(TouristEntity tourist, UUID buildingId, UUID colonyId) {
        BuildingConfig config = getBuildingConfig(buildingId);
        if (config == null || config.service() == null) return false;

        ServiceConfig svc = config.service();
        int maxOccupancy = svc.maxOccupancy();
        if (maxOccupancy <= 0) return false;

        Set<UUID> guests = occupancy.computeIfAbsent(buildingId, k -> ConcurrentHashMap.newKeySet());
        if (guests.size() >= maxOccupancy) {
            Log.debug(TAG, "[Tourist] Check-in failed: building {} full ({}/{})",
                    shortId(buildingId), guests.size(), maxOccupancy);
            return false;
        }

        guests.add(tourist.getUUID());
        touristToHotel.put(tourist.getUUID(), buildingId);
        tourist.setCheckedInBuildingId(buildingId);
        tourist.setHotelCheckinTime(tourist.tickCount);

        Log.info(TAG, "[Tourist] {} checked into {} (occupancy {}/{})",
                tourist.getTouristName(), shortId(buildingId), guests.size(), maxOccupancy);
        return true;
    }

    /**
     * Check a tourist out of their hotel. Recovers energy and boosts satisfaction.
     */
    public void checkOut(TouristEntity tourist) {
        UUID buildingId = touristToHotel.remove(tourist.getUUID());
        if (buildingId == null) return;

        Set<UUID> guests = occupancy.get(buildingId);
        if (guests != null) {
            guests.remove(tourist.getUUID());
            if (guests.isEmpty()) occupancy.remove(buildingId);
        }

        tourist.setCheckedInBuildingId(null);
        tourist.setHotelCheckinTime(0);

        int energyRecovery = Config.HOTEL_ENERGY_PER_TICK.get()
                * Math.max(1, tourist.tickCount - tourist.getHotelCheckinTime());
        tourist.setEnergy(tourist.getEnergy() + energyRecovery);
        tourist.setSatisfaction(tourist.getSatisfaction() + Config.HOTEL_SATISFACTION_PER_NIGHT.get());

        Log.info(TAG, "[Tourist] {} checked out of {} (energy +{} satisfaction +{})",
                tourist.getTouristName(), shortId(buildingId),
                energyRecovery, Config.HOTEL_SATISFACTION_PER_NIGHT.get());
    }

    // ── Query ──

    /** Returns the number of currently checked-in tourists in a hotel. */
    public int getOccupancy(UUID buildingId) {
        Set<UUID> guests = occupancy.get(buildingId);
        return guests != null ? guests.size() : 0;
    }

    /** Returns true if the hotel has vacancy. */
    public boolean hasVacancy(UUID buildingId) {
        BuildingConfig config = getBuildingConfig(buildingId);
        if (config == null || config.service() == null) return false;
        int max = config.service().maxOccupancy();
        if (max <= 0) return false;
        return getOccupancy(buildingId) < max;
    }

    /** Returns the building ID the tourist is checked into, or null. */
    @Nullable
    public UUID getCheckedInBuilding(UUID touristId) {
        return touristToHotel.get(touristId);
    }

    /** Returns true if the tourist is currently checked into a hotel. */
    public boolean isCheckedIn(UUID touristId) {
        return touristToHotel.containsKey(touristId);
    }

    /** Returns the names of all guests currently checked into a hotel. */
    public List<String> getGuestNames(UUID buildingId, net.minecraft.world.level.Level level) {
        Set<UUID> guests = occupancy.get(buildingId);
        if (guests == null || guests.isEmpty()) return List.of();
        List<String> names = new java.util.ArrayList<>();
        if (level instanceof net.minecraft.server.level.ServerLevel sl) {
            for (UUID touristId : guests) {
                for (var entity : sl.getAllEntities()) {
                    if (entity instanceof TouristEntity t && t.getUUID().equals(touristId)) {
                        names.add(t.getTouristName());
                        break;
                    }
                }
            }
        }
        return names;
    }

    // ── Heartbeat ──

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        tickCounter++;
        if (tickCounter % 20 != 0) return; // every second

        ServerLevel level = getServerLevel();
        if (level == null) return;

        long dayTime = level.getDayTime() % 24000;
        boolean isMorning = dayTime >= 0 && dayTime < 200;

        int energyPerTick = Config.HOTEL_ENERGY_PER_TICK.get();
        int energyPerSecond = energyPerTick * 20; // per-tick config × 20 ticks/sec heartbeat

        for (var entry : touristToHotel.entrySet()) {
            UUID touristId = entry.getKey();
            TouristEntity tourist = findTourist(level, touristId);
            if (tourist == null || !tourist.isAlive()) {
                forceCheckOut(touristId);
                continue;
            }

            // Auto check-out at morning
            if (isMorning) {
                checkOut(tourist);
                continue;
            }

            // Tick energy recovery
            tourist.setEnergy(tourist.getEnergy() + energyPerSecond);
        }
    }

    // ── Internal ──

    private void forceCheckOut(UUID touristId) {
        UUID buildingId = touristToHotel.remove(touristId);
        if (buildingId != null) {
            Set<UUID> guests = occupancy.get(buildingId);
            if (guests != null) {
                guests.remove(touristId);
                if (guests.isEmpty()) occupancy.remove(buildingId);
            }
        }
    }

    @Nullable
    private BuildingConfig getBuildingConfig(UUID buildingId) {
        ServerLevel level = getServerLevel();
        if (level == null) return null;
        BuildingSavedData sd = BuildingSavedData.get(level);
        if (sd == null) return null;
        BuildingState state = sd.getBuilding(buildingId);
        if (state == null) return null;
        return BuildingConfigLoader.getInstance().get(state.getBuildingTypeId());
    }

    @Nullable
    private static TouristEntity findTourist(ServerLevel level, UUID touristId) {
        for (var entity : level.getAllEntities()) {
            if (entity instanceof TouristEntity t && t.getUUID().equals(touristId)) {
                return t;
            }
        }
        return null;
    }

    @Nullable
    private static ServerLevel getServerLevel() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server != null ? server.overworld() : null;
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }
}
