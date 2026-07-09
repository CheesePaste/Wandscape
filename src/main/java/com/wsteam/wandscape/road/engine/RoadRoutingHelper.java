package com.wsteam.wandscape.road.engine;

import com.wsteam.wandscape.road.core.PathPoint;
import com.wsteam.wandscape.road.core.RoadBlobCache;
import com.wsteam.wandscape.road.core.RoadNetwork;
import com.wsteam.wandscape.road.algorithm.RoadRouter;
import com.wsteam.wandscape.road.core.TransportRoute;
import com.wsteam.wandscape.shared.api.RoadApi;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Convenience wrapper that combines lazy blob scanning with route planning.
 *
 * <p>All callers that need road-assisted routing should use this helper
 * instead of calling {@link RoadRouter#plan} directly. It:
 * <ol>
 *   <li>Retrieves the blob cache from RoadApi</li>
 *   <li>Scans around start/end for uncached player-built roads</li>
 *   <li>Calls RoadRouter.plan() with both network and blob cache</li>
 * </ol>
 *
 * <p>Silently returns empty list on any error — callers always fall back
 * to direct transport when road routing fails.
 */
public final class RoadRoutingHelper {

    private RoadRoutingHelper() {}

    /**
     * Plan a road-assisted route with lazy blob scanning.
     *
     * @param roadApi    road API (may be null)
     * @param level      the server level (may be null, used for blob scanning)
     * @param colonyId   colony identifier
     * @param from       start position
     * @param to         destination position
     * @return ordered route segments, or empty list if no road available
     */
    public static TransportRoute planWithRoads(@Nullable RoadApi roadApi,
                                                    @Nullable Level level,
                                                    @Nullable java.util.UUID colonyId,
                                                    BlockPos from, BlockPos to) {
        if (roadApi == null) return new TransportRoute(List.of());

        try {
            RoadNetwork network = roadApi.getNetwork(colonyId);
            RoadBlobCache blobCache = roadApi.getBlobCache(colonyId);

            // Lazy blob scanning — only if we have a level and cache
            if (level != null && blobCache != null) {
                RoadBlobExplorer.scanAndCache(level, from, to, blobCache,
                        WandscapeTags.Blocks.CUSTOM_ROADS);
            }

            return RoadRouter.plan(network, blobCache,
                    new PathPoint(from.getX(), from.getY(), from.getZ()),
                    new PathPoint(to.getX(), to.getY(), to.getZ()));

        } catch (Exception e) {
            return new TransportRoute(List.of());
        }
    }

    /**
     * Plan a road-assisted NPC-walkable route with lazy blob scanning.
     *
     * @param roadApi    road API (may be null)
     * @param level      the server level (may be null, used for blob scanning)
     * @param colonyId   colony identifier
     * @param from       start position
     * @param to         destination position
     * @return ordered route segments, or empty list if no road available or NPC-incompatible
     */
    public static TransportRoute planNpcWithRoads(@Nullable RoadApi roadApi,
                                                       @Nullable Level level,
                                                       @Nullable java.util.UUID colonyId,
                                                       BlockPos from, BlockPos to) {
        if (roadApi == null) return new TransportRoute(List.of());

        try {
            RoadNetwork network = roadApi.getNetwork(colonyId);
            RoadBlobCache blobCache = roadApi.getBlobCache(colonyId);

            if (level != null && blobCache != null) {
                RoadBlobExplorer.scanAndCache(level, from, to, blobCache,
                        WandscapeTags.Blocks.CUSTOM_ROADS);
            }

            return RoadRouter.planNpc(network, blobCache,
                    new PathPoint(from.getX(), from.getY(), from.getZ()),
                    new PathPoint(to.getX(), to.getY(), to.getZ()));

        } catch (Exception e) {
            return new TransportRoute(List.of());
        }
    }
}
