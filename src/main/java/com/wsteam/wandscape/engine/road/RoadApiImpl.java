package com.wsteam.wandscape.engine.road;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.wsteam.wandscape.core.road.RoadEdge;
import com.wsteam.wandscape.core.road.RoadNetwork;
import com.wsteam.wandscape.road.server.RoadEditorHandler;
import com.wsteam.wandscape.shared.api.RoadApi;
/**
 * Default implementation of {@link RoadApi}.
 * Delegates to {@link RoadSavedData} and {@link RoadConfig}.
 */
public class RoadApiImpl implements RoadApi {

    @Override
    public RoadNetwork getNetwork(UUID colonyId) {
        try {
            return RoadSavedData.getOrCreate(
                    net.neoforged.neoforge.server.ServerLifecycleHooks
                            .getCurrentServer().overworld())
                    .getNetwork();
        } catch (Exception e) {
            return new RoadNetwork();
        }
    }

    @Override
    public List<RoadEdge> getEdges(UUID colonyId) {
        return new ArrayList<>(getNetwork(colonyId).getEdges().values());
    }

    @Override
    public void requestFullRebuild(UUID colonyId) {
        RoadEventListener.triggerRebuild(colonyId);
    }

    @Override
    public void requestIncrementalUpdate(UUID colonyId, UUID buildingId) {
        // V1: incremental add is handled automatically by build_complete events
        // Manual trigger for future use
    }

    @Override
    public int getBuildingThreshold() {
        return RoadConfig.getInstance().getBuildingThreshold();
    }

    @Override
    public String getRoadBlock(String tier) {
        return RoadConfig.getInstance().getDefaultBlock(tier);
    }

    @Override
    public void removeEdge(UUID colonyId, UUID edgeId) {
        var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        net.minecraft.server.level.ServerLevel level = server.overworld();
        RoadSavedData roadData = RoadSavedData.getOrCreate(level);
        RoadEditorHandler.removeEdge(level, roadData.getNetwork(), edgeId);
        roadData.markChanged();
    }
}
