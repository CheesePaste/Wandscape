package com.wsteam.wandscape.tourist.internal;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.wsteam.wandscape.shared.api.TouristApi;
import com.wsteam.wandscape.shared.data.BarRatio;
import com.wsteam.wandscape.shared.event.TouristArrivedEvent;
import com.wsteam.wandscape.shared.event.TouristDepartedEvent;

import net.neoforged.neoforge.common.NeoForge;
/**
 * Implementation of {@link TouristApi}.
 * Tracks tourist presence per colony in memory.
 * Full spawn logic will be implemented in Phase C (TouristSpawnSystem).
 */
public class TouristApiImpl implements TouristApi {

    // colonyId → set of tourist entity UUIDs
    private final Map<UUID, Set<UUID>> colonyTourists = new ConcurrentHashMap<>();
    // colonyId → count of tourists who stayed overnight (checked into hotel)
    private final Map<UUID, Integer> colonyOvernightCounts = new ConcurrentHashMap<>();

    @Override
    public int getTouristCount(UUID colonyId) {
        Set<UUID> tourists = colonyTourists.get(colonyId);
        return tourists != null ? tourists.size() : 0;
    }

    @Override
    public List<UUID> getTouristsInColony(UUID colonyId) {
        Set<UUID> tourists = colonyTourists.get(colonyId);
        return tourists != null ? List.copyOf(tourists) : List.of();
    }

    @Override
    public void spawnTourist(UUID colonyId, net.minecraft.core.BlockPos spawnPos) {
        // Phase C: TouristSpawnSystem will handle actual entity spawning
        // For now, this is a placeholder that the spawn system will call
    }

    @Override
    public void registerArrival(UUID touristId, UUID colonyId) {
        colonyTourists.computeIfAbsent(colonyId, k -> ConcurrentHashMap.newKeySet()).add(touristId);
        NeoForge.EVENT_BUS.post(new TouristArrivedEvent(touristId, colonyId));
    }

    @Override
    public void registerDeparture(UUID touristId, UUID colonyId, BarRatio fill) {
        Set<UUID> tourists = colonyTourists.get(colonyId);
        if (tourists != null) {
            tourists.remove(touristId);
            if (tourists.isEmpty()) {
                colonyTourists.remove(colonyId);
            }
        }
        NeoForge.EVENT_BUS.post(new TouristDepartedEvent(touristId, colonyId, fill));
    }

    @Override
    public int getOvernightStayerCount(UUID colonyId) {
        return colonyOvernightCounts.getOrDefault(colonyId, 0);
    }

    /** Set the count of overnight stayers for a colony. Called by TouristSpawnSystem during morning phase. */
    public void setOvernightStayerCount(UUID colonyId, int count) {
        if (count <= 0) colonyOvernightCounts.remove(colonyId);
        else colonyOvernightCounts.put(colonyId, count);
    }
}
