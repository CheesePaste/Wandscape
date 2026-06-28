package com.wsteam.wandscape.shared.api;

import java.util.List;
import java.util.UUID;

import net.minecraft.core.BlockPos;
/**
 * Public API for the tourist simulation system.
 * Implemented by {@code tourist/internal/TouristApiImpl.java}.
 */
public interface TouristApi {

    /** Number of tourists currently present in a colony. */
    int getTouristCount(UUID colonyId);

    /** UUIDs of all tourist entities in a colony. */
    List<UUID> getTouristsInColony(UUID colonyId);

    /** Request a tourist spawn at the given position for a colony. */
    void spawnTourist(UUID colonyId, BlockPos spawnPos);

    /** Average satisfaction of all tourists in a colony, 0 if none. */
    int getAverageSatisfaction(UUID colonyId);

    /** Register a tourist arrival, firing {@code TouristArrivedEvent}. */
    void registerArrival(UUID touristId, UUID colonyId);

    /** Register a tourist departure, firing {@code TouristDepartedEvent}. */
    void registerDeparture(UUID touristId, UUID colonyId, int satisfaction);
}
