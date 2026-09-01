package com.wsteam.wandscape.api;
import com.wsteam.wandscape.content.tourist.event.TouristArrivedEvent;
import com.wsteam.wandscape.content.tourist.event.TouristDepartedEvent;

import com.wsteam.wandscape.content.tourist.data.BarRatio;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.UUID;
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

    /** Register a tourist arrival, firing {@code TouristArrivedEvent}. */
    void registerArrival(UUID touristId, UUID colonyId);

    /** Register a tourist departure, firing {@code TouristDepartedEvent} with the tourist's final bar fill. */
    void registerDeparture(UUID touristId, UUID colonyId, BarRatio fill);

    /** Number of tourists who stayed overnight (checked into hotel) in a colony. */
    int getOvernightStayerCount(UUID colonyId);
}
