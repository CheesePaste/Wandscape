package com.wsteam.wandscape.content.building.wonder;

import com.wsteam.wandscape.content.building.data.BuildingConfig;
import com.wsteam.wandscape.content.building.internal.BuildingState;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Contextual payload passed to {@link WonderTrigger} when a wonder building completes construction.
 */
public record WonderTriggerContext(
        UUID buildingId,
        @Nullable UUID colonyId,
        String buildingTypeId,
        BuildingConfig config,
        BuildingState state,
        ServerLevel level,
        boolean firstCompletion
) {}
