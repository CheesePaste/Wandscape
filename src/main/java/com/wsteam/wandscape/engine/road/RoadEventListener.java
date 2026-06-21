package com.wsteam.wandscape.engine.road;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;

import com.google.gson.JsonArray;
import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.building.internal.BuildingSavedData;
import com.wsteam.wandscape.building.internal.BuildingState;
import com.wsteam.wandscape.core.event.CustomEvent;
import com.wsteam.wandscape.core.road.CardinalFacing;
import com.wsteam.wandscape.core.road.ConnectivityConstraint;
import com.wsteam.wandscape.core.road.NetworkDiff;
import com.wsteam.wandscape.core.road.OrganicRoadPlanner;
import com.wsteam.wandscape.core.road.PlanResult;
import com.wsteam.wandscape.core.road.RoadBuildingData;
import com.wsteam.wandscape.core.road.RoadEdge;
import com.wsteam.wandscape.core.road.RoadNetwork;
import com.wsteam.wandscape.core.road.RoadPlanner;
import com.wsteam.wandscape.core.road.RoadTemplatePool;
import com.wsteam.wandscape.core.road.TemplateMeta;
import com.wsteam.wandscape.core.road.TemplatePlacement;
import com.wsteam.wandscape.core.road.XZPoint;
import com.wsteam.wandscape.engine.WandscapeEngine;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Listens for engine {@link CustomEvent}s related to road planning.
 * Registered AFTER engine bootstrap (like {@code BuildCompleteListener}).
 *
 * <p>Handles:
 * <ul>
 *   <li>{@code build_complete} — triggers MST or incremental road planning</li>
 *   <li>{@code road_segment_complete} — updates edge build progress</li>
 * </ul>
 */
public final class RoadEventListener {

    private static final Logger LOGGER = LogUtils.getLogger();

    private RoadEventListener() {}

    /**
     * Register this listener on the engine event bus.
     * Call after engine bootstrap.
     */
    public static void register() {
        var world = WandscapeEngine.getWorld();
        if (world == null || world.eventBus == null) {
            LOGGER.warn("Cannot register RoadEventListener — engine not bootstrapped");
            return;
        }
        world.eventBus.subscribe(CustomEvent.class, RoadEventListener::onEvent);
        LOGGER.info("RoadEventListener registered on engine EventBus");
    }

    private static void onEvent(CustomEvent event) {
        switch (event.name()) {
            case "build_complete" -> onBuildComplete(event);
            case "road_segment_complete" -> onSegmentComplete(event);
            default -> { /* ignore */ }
        }
    }

    // ---- V3 template pool (hardcoded for first version) ----

    private static RoadTemplatePool corePool;
    private static RoadTemplatePlacer.RoadTemplateMetaPool enginePool;

    static {
        List<TemplateMeta> metas = List.of(
                new TemplateMeta("wandscape:road/straight", "wandscape:road/straight",
                        3, 16, 4,
                        List.of(new com.wsteam.wandscape.core.road.EntryExit(7, 0, CardinalFacing.SOUTH)),
                        List.of(new com.wsteam.wandscape.core.road.EntryExit(7, 15, CardinalFacing.SOUTH))),
                new TemplateMeta("wandscape:road/corner", "wandscape:road/corner",
                        3, 16, 2,
                        List.of(new com.wsteam.wandscape.core.road.EntryExit(7, 0, CardinalFacing.SOUTH)),
                        List.of(new com.wsteam.wandscape.core.road.EntryExit(15, 7, CardinalFacing.EAST))),
                new TemplateMeta("wandscape:road/crossroad", "wandscape:road/crossroad",
                        3, 16, 1,
                        List.of(
                                new com.wsteam.wandscape.core.road.EntryExit(7, 0, CardinalFacing.SOUTH),
                                new com.wsteam.wandscape.core.road.EntryExit(7, 15, CardinalFacing.NORTH),
                                new com.wsteam.wandscape.core.road.EntryExit(0, 7, CardinalFacing.WEST),
                                new com.wsteam.wandscape.core.road.EntryExit(15, 7, CardinalFacing.EAST)),
                        List.of(
                                new com.wsteam.wandscape.core.road.EntryExit(7, 15, CardinalFacing.NORTH),
                                new com.wsteam.wandscape.core.road.EntryExit(7, 0, CardinalFacing.SOUTH),
                                new com.wsteam.wandscape.core.road.EntryExit(15, 7, CardinalFacing.EAST),
                                new com.wsteam.wandscape.core.road.EntryExit(0, 7, CardinalFacing.WEST)))
        );
        corePool = RoadTemplatePool.of(metas);
        enginePool = new RoadTemplatePlacer.RoadTemplateMetaPool(metas);
    }

    // ---- build_complete handler ----

    private static void onBuildComplete(CustomEvent event) {
        ServerLevel level = getServerLevel();
        if (level == null) return;

        BuildingSavedData buildingData = BuildingSavedData.get(level);
        RoadSavedData roadData = RoadSavedData.getOrCreate(level);
        RoadConfig config = RoadConfig.getInstance();

        // Build the RoadBuildingData list from all intact buildings
        List<RoadBuildingData> allBuildings = new ArrayList<>();
        for (BuildingState bs : buildingData.getAllBuildings()) {
            if (!bs.isStructureIntact() || bs.isShutdown()) continue;
            allBuildings.add(new RoadBuildingData(
                    bs.getBuildingId(), bs.getAnchor().getX(),
                    bs.getAnchor().getY(), bs.getAnchor().getZ()));
        }

        int threshold = config.getBuildingThreshold();
        int buildingCount = allBuildings.size();
        roadData.setBuildingCount(buildingCount);

        if (buildingCount < threshold) {
            LOGGER.debug("[Road] Skipping — {} buildings < threshold {}",
                    buildingCount, threshold);
            roadData.markChanged();
            return;
        }

        // Compute access points from building bounds
        OrganicRoadPlanner.AccessPointFn accessFn = (bd, dir) -> {
            BuildingState bs = buildingData.getBuilding(bd.id());
            if (bs == null) return new XZPoint(bd.x(), bd.z());
            var bounds = bs.getBounds();
            int halfW = (bounds.maxX() - bounds.minX() + 1) / 2;
            int halfD = (bounds.maxZ() - bounds.minZ() + 1) / 2;
            int margin = Math.max(halfW, halfD) + 1;
            return new XZPoint(
                    bd.x() + dir.dx() * margin,
                    bd.z() + dir.dz() * margin);
        };

        // Collect obstacle positions from building bounds
        Set<XZPoint> obstacles = collectBuildingObstacles(buildingData);

        // Plan organic roads
        Random rng = new Random(level.getSeed() ^ buildingCount);
        PlanResult planResult = OrganicRoadPlanner.plan(
                allBuildings, accessFn, threshold,
                corePool, obstacles, rng);

        if (planResult.placements().isEmpty()) {
            LOGGER.debug("[Road] Organic planner produced no placements for {} buildings",
                    buildingCount);
            roadData.markChanged();
            return;
        }

        LOGGER.info("[Road] Organic plan: {} placements, {} edges, {} budget used",
                planResult.placements().size(),
                planResult.edgesCreated(),
                planResult.budgetUsed());

        // Convert placements to tiles and enqueue
        processPlacements(planResult.placements(), level, config, roadData.getNetwork(),
                allBuildings, buildingData);

        roadData.markChanged();
    }

    /** Convert template placements to tiles, split into segments, enqueue. */
    private static void processPlacements(List<TemplatePlacement> placements,
                                           ServerLevel level, RoadConfig config,
                                           RoadNetwork network,
                                           List<RoadBuildingData> allBuildings,
                                           BuildingSavedData buildingData) {
        // Collect building bounds for tile filtering
        var buildingBounds = new ArrayList<BoundingBox>();
        for (BuildingState bs : buildingData.getAllBuildings()) {
            if (bs.isStructureIntact()) {
                buildingBounds.add(bs.getBounds());
            }
        }

        // Collect existing road positions from non-PLANNED edges
        Set<XZPoint> occupiedTiles = new HashSet<>();
        for (RoadEdge e : network.getEdges().values()) {
            if (e.getStatus() != RoadEdge.EdgeStatus.PLANNED) {
                occupiedTiles.addAll(e.getPath());
            }
        }

        // Build tiles from template placements
        JsonArray allTiles = RoadTemplatePlacer.buildTiles(
                level, placements, enginePool, buildingBounds, occupiedTiles);

        if (allTiles.isEmpty()) {
            LOGGER.warn("[Road] No tiles generated from {} placements — check NBT loading and terrain",
                    placements.size());
            return;
        }

        // Create a new edge to represent this road connection
        UUID edgeId = UUID.randomUUID();

        // Extract tile positions for edge path storage
        List<XZPoint> path = new ArrayList<>();
        for (int i = 0; i < allTiles.size(); i++) {
            var tile = allTiles.get(i).getAsJsonObject();
            var posArr = tile.getAsJsonArray("pos");
            path.add(new XZPoint(posArr.get(0).getAsInt(), posArr.get(2).getAsInt()));
        }

        // Determine from/to from the first and last building in allBuildings
        UUID fromId = allBuildings.isEmpty() ? UUID.randomUUID() : allBuildings.get(0).id();
        UUID toId = allBuildings.size() > 1 ? allBuildings.get(1).id() : fromId;

        RoadEdge edge = new RoadEdge(edgeId, fromId, toId, "dirt", path);
        network.addEdge(edge);

        // Split tiles into segments
        int maxLen = config.getSegmentMaxLength();
        int tileCount = allTiles.size();
        int segCount = 0;

        for (int start = 0; start < tileCount; start += maxLen) {
            int end = Math.min(start + maxLen, tileCount);
            JsonArray segTiles = new JsonArray();
            for (int i = start; i < end; i++) {
                segTiles.add(allTiles.get(i));
            }

            if (segTiles.isEmpty()) continue;

            UUID segId = UUID.randomUUID();
            RoadTaskSource.enqueueSegment(
                    new RoadTaskSource.PendingSegment(segId, edgeId, segTiles));
            segCount++;

            LOGGER.debug("[Road] enqueued segment {} ({} tiles) for edge {}",
                    segId, end - start, edgeId.toString().substring(0, 8));
        }

        if (segCount > 0) {
            edge.setStatus(RoadEdge.EdgeStatus.BUILDING);
            LOGGER.info("[Road] edge {} ({} tiles, {} segments) → BUILDING",
                    edgeId.toString().substring(0, 8), tileCount, segCount);
        } else {
            edge.setStatus(RoadEdge.EdgeStatus.COMPLETE);
        }
    }

    /** Collect building interior XZ points as obstacles. */
    private static Set<XZPoint> collectBuildingObstacles(BuildingSavedData bd) {
        Set<XZPoint> obstacles = new HashSet<>();
        for (BuildingState bs : bd.getAllBuildings()) {
            if (!bs.isStructureIntact()) continue;
            var bounds = bs.getBounds();
            // Only add perimeter points to keep set manageable
            for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                obstacles.add(new XZPoint(x, bounds.minZ()));
                obstacles.add(new XZPoint(x, bounds.maxZ()));
            }
            for (int z = bounds.minZ() + 1; z < bounds.maxZ(); z++) {
                obstacles.add(new XZPoint(bounds.minX(), z));
                obstacles.add(new XZPoint(bounds.maxX(), z));
            }
        }
        return obstacles;
    }

    // ---- road_segment_complete handler ----

    private static void onSegmentComplete(CustomEvent event) {
        ServerLevel level = getServerLevel();
        if (level == null) return;

        String edgeIdStr = event.params().get("edge_id");
        if (edgeIdStr == null) return;

        UUID edgeId;
        try {
            edgeId = UUID.fromString(edgeIdStr);
        } catch (IllegalArgumentException e) {
            LOGGER.warn("[Road] invalid edge_id in road_segment_complete: {}", edgeIdStr);
            return;
        }

        RoadSavedData roadData = RoadSavedData.getOrCreate(level);
        RoadEdge edge = roadData.getNetwork().getEdge(edgeId);
        if (edge != null
                && edge.getStatus() == RoadEdge.EdgeStatus.BUILDING) {
            edge.setStatus(RoadEdge.EdgeStatus.COMPLETE);
            roadData.markChanged();
            LOGGER.info("[Road] edge {} marked COMPLETE", edgeId.toString().substring(0, 8));
        }
    }

    // ---- Rebuild (called from RoadApiImpl) ----

    /** Trigger a full rebuild using V3 organic planner. */
    static void triggerRebuild(UUID colonyId) {
        ServerLevel level = getServerLevel();
        if (level == null) return;

        BuildingSavedData buildingData = BuildingSavedData.get(level);
        RoadSavedData roadData = RoadSavedData.getOrCreate(level);

        List<RoadBuildingData> allBuildings = new ArrayList<>();
        for (BuildingState bs : buildingData.getAllBuildings()) {
            if (!bs.isStructureIntact() || bs.isShutdown()) continue;
            allBuildings.add(new RoadBuildingData(
                    bs.getBuildingId(), bs.getAnchor().getX(),
                    bs.getAnchor().getY(), bs.getAnchor().getZ()));
        }

        RoadNetwork network = roadData.getNetwork();
        RoadConfig config = RoadConfig.getInstance();

        // Compute access points
        OrganicRoadPlanner.AccessPointFn accessFn = (bd, dir) -> {
            BuildingState bs = buildingData.getBuilding(bd.id());
            if (bs == null) return new XZPoint(bd.x(), bd.z());
            var bounds = bs.getBounds();
            int halfW = (bounds.maxX() - bounds.minX() + 1) / 2;
            int halfD = (bounds.maxZ() - bounds.minZ() + 1) / 2;
            int margin = Math.max(halfW, halfD) + 1;
            return new XZPoint(bd.x() + dir.dx() * margin, bd.z() + dir.dz() * margin);
        };

        Set<XZPoint> obstacles = collectBuildingObstacles(buildingData);
        Random rng = new Random(level.getSeed() ^ allBuildings.size());

        PlanResult planResult = OrganicRoadPlanner.plan(
                allBuildings, accessFn, 0,  // threshold=0 forces planning
                corePool, obstacles, rng);

        LOGGER.info("[Road] rebuild: {} placements, {} edges",
                planResult.placements().size(), planResult.edgesCreated());

        if (!planResult.placements().isEmpty()) {
            processPlacements(planResult.placements(), level, config, network,
                    allBuildings, buildingData);
        }

        roadData.setBuildingCount(allBuildings.size());
        roadData.markChanged();
    }

    // ---- Helpers ----

    private static BlockPos parseAnchor(String s) {
        String[] parts = s.split(",");
        if (parts.length != 3) return null;
        try {
            return new BlockPos(
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]));
        } catch (NumberFormatException e) {
            LOGGER.warn("[Road] invalid anchor format: {}", s);
            return null;
        }
    }

    private static BuildingState findByAnchor(BuildingSavedData data, BlockPos anchor) {
        for (BuildingState state : data.getAllBuildings()) {
            if (state.getAnchor().equals(anchor)) return state;
        }
        return null;
    }

    private static ServerLevel getServerLevel() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server != null ? server.overworld() : null;
    }
}
