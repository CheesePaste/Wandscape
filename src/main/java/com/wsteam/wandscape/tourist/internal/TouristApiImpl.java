package com.wsteam.wandscape.tourist.internal;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.wsteam.wandscape.shared.api.TouristApi;
import com.wsteam.wandscape.shared.event.TouristArrivedEvent;
import com.wsteam.wandscape.shared.event.TouristDepartedEvent;

import net.neoforged.neoforge.common.NeoForge;

/**
 * Implementation of {@link TouristApi}.
 * Tracks tourist presence and satisfaction per colony in memory.
 * Full spawn logic will be implemented in Phase C (TouristSpawnSystem).
 */
public class TouristApiImpl implements TouristApi {

    // colonyId → set of tourist entity UUIDs
    private final Map<UUID, Map<UUID, Integer>> colonyTourists = new ConcurrentHashMap<>();

    @Override
    public int getTouristCount(UUID colonyId) {
        Map<UUID, Integer> tourists = colonyTourists.get(colonyId);
        return tourists != null ? tourists.size() : 0;
    }

    @Override
    public List<UUID> getTouristsInColony(UUID colonyId) {
        Map<UUID, Integer> tourists = colonyTourists.get(colonyId);
        return tourists != null ? List.copyOf(tourists.keySet()) : List.of();
    }

    @Override
    public void spawnTourist(UUID colonyId, net.minecraft.core.BlockPos spawnPos) {
        // Phase C: TouristSpawnSystem will handle actual entity spawning
        // For now, this is a placeholder that the spawn system will call
    }

    @Override
    public int getAverageSatisfaction(UUID colonyId) {
        Map<UUID, Integer> tourists = colonyTourists.get(colonyId);
        if (tourists == null || tourists.isEmpty()) return 0;
        int total = 0;
        for (int sat : tourists.values()) {
            total += sat;
        }
        return total / tourists.size();
    }

    @Override
    public void registerArrival(UUID touristId, UUID colonyId) {
        colonyTourists.computeIfAbsent(colonyId, k -> new ConcurrentHashMap<>())
                .put(touristId, 0);
        NeoForge.EVENT_BUS.post(new TouristArrivedEvent(touristId, colonyId));
    }

    @Override
    public void registerDeparture(UUID touristId, UUID colonyId, int satisfaction) {
        Map<UUID, Integer> tourists = colonyTourists.get(colonyId);
        if (tourists != null) {
            tourists.remove(touristId);
            if (tourists.isEmpty()) {
                colonyTourists.remove(colonyId);
            }
        }
        NeoForge.EVENT_BUS.post(new TouristDepartedEvent(touristId, colonyId, satisfaction));
    }

    /** Update a tourist's satisfaction value (called by interaction handlers). */
    public void updateSatisfaction(UUID touristId, UUID colonyId, int satisfaction) {
        Map<UUID, Integer> tourists = colonyTourists.get(colonyId);
        if (tourists != null) {
            tourists.put(touristId, satisfaction);
        }
    }
}
