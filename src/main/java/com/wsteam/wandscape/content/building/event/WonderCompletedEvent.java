package com.wsteam.wandscape.content.building.event;

import com.wsteam.wandscape.content.building.data.BuildingConfig;
import com.wsteam.wandscape.content.building.internal.BuildingState;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.Event;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Fired when a wonder-category building completes construction (or is fully repaired/reconstructed).
 * Posted on {@link net.neoforged.neoforge.common.NeoForge#EVENT_BUS}.
 */
public class WonderCompletedEvent extends Event {

    private final UUID buildingId;
    @Nullable
    private final UUID colonyId;
    private final String buildingTypeId;
    private final BuildingConfig config;
    private final BuildingState state;
    private final ServerLevel level;
    private final boolean firstCompletion;

    public WonderCompletedEvent(UUID buildingId,
                                @Nullable UUID colonyId,
                                String buildingTypeId,
                                BuildingConfig config,
                                BuildingState state,
                                ServerLevel level,
                                boolean firstCompletion) {
        this.buildingId = buildingId;
        this.colonyId = colonyId;
        this.buildingTypeId = buildingTypeId;
        this.config = config;
        this.state = state;
        this.level = level;
        this.firstCompletion = firstCompletion;
    }

    public UUID getBuildingId() {
        return buildingId;
    }

    @Nullable
    public UUID getColonyId() {
        return colonyId;
    }

    public String getBuildingTypeId() {
        return buildingTypeId;
    }

    public BuildingConfig getConfig() {
        return config;
    }

    public BuildingState getState() {
        return state;
    }

    public ServerLevel getLevel() {
        return level;
    }

    /**
     * Whether this is the first time the building completed construction (true),
     * or a reconstruction / repair from a damaged state (false).
     */
    public boolean isFirstCompletion() {
        return firstCompletion;
    }
}
