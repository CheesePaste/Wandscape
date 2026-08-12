package com.wsteam.wandscape.road.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.wsteam.wandscape.road.core.RoadBlobCache;
import com.wsteam.wandscape.road.core.RoadEdge;
import com.wsteam.wandscape.road.core.RoadNetwork;
import com.wsteam.wandscape.shared.api.RoadApi;
/**
 * Default implementation of {@link RoadApi}.
 * Delegates to {@link RoadSavedData}.
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
    public RoadBlobCache getBlobCache(UUID colonyId) {
        try {
            return RoadSavedData.getOrCreate(
                    net.neoforged.neoforge.server.ServerLifecycleHooks
                            .getCurrentServer().overworld())
                    .getBlobCache();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void removeEdge(UUID colonyId, UUID edgeId) {
        var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        RoadSavedData roadData = RoadSavedData.getOrCreate(server.overworld());
        RoadNetwork network = roadData.getNetwork();
        if (network.removeEdge(edgeId)) {
            roadData.markChanged();
        }
    }
}
