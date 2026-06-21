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
import com.wsteam.wandscape.core.road.OrganicRoadPlanner;
import com.wsteam.wandscape.core.road.PlanResult;
import com.wsteam.wandscape.core.road.RoadBuildingData;
import com.wsteam.wandscape.core.road.RoadEdge;
import com.wsteam.wandscape.core.road.RoadNetwork;
import com.wsteam.wandscape.core.road.RoadNode;
import com.wsteam.wandscape.core.road.RoadTemplatePool;
import com.wsteam.wandscape.core.road.TemplateMeta;
import com.wsteam.wandscape.core.road.TemplatePlacement;
import com.wsteam.wandscape.core.road.XZPoint;
import com.wsteam.wandscape.core.types.GridPos;
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
            LOGGER.debug("[Road] Skipping — {} buildings < threshold {}", buildingCount, threshold);
            roadData.markChanged();
            return;
        }

        OrganicRoadPlanner.AccessPointFn accessFn = accessPointFn(buildingData);
        Set<XZPoint> obstacles = collectBuildingObstacles(buildingData);
        RoadNetwork network = roadData.getNetwork();
        Random rng = new Random(level.getSeed());

        // Parse new building info from event
        String anchorStr = event.params().get("anchor");
        String buildingName = event.params().get("building_name");
        RoadBuildingData newBuilding = null;
        if (anchorStr != null) {
            BlockPos anchor = parseAnchor(anchorStr);
            if (anchor != null) {
                BuildingState newBs = findByAnchor(buildingData, anchor);
                if (newBs != null) {
                    newBuilding = new RoadBuildingData(
                            newBs.getBuildingId(), anchor.getX(), anchor.getY(), anchor.getZ());
                }
            }
        }

        // ── First-time full plan (network was empty) ──
        if (network.isEmpty()) {
            LOGGER.info("[Road] First MST plan — {} buildings (threshold={})",
                    buildingCount, threshold);
            PlanResult planResult = OrganicRoadPlanner.plan(
                    allBuildings, accessFn, threshold, corePool, obstacles, rng);

            if (planResult.placements().isEmpty()) {
                LOGGER.warn("[Road] MST plan produced no placements");
                roadData.markChanged();
                return;
            }

            LOGGER.info("[Road] Full plan: {} placements, {} edges, cost={}",
                    planResult.placements().size(),
                    planResult.edgesCreated(),
                    planResult.budgetUsed());

            // Register all building nodes
            for (RoadBuildingData bd : allBuildings) {
                network.addNode(new RoadNode(bd.id(),
                        new GridPos(
                                bd.x(), bd.y(), bd.z()),
                        RoadNode.NodeType.BUILDING));
            }

            processPlacementsSingleEdge(planResult.placements(), level, config, network);
            roadData.markChanged();
            return;
        }

        // ── Incremental: connect new building to nearest existing node ──
        if (newBuilding == null) {
            LOGGER.debug("[Road] No new building parsed — skipping incremental");
            roadData.markChanged();
            return;
        }

        // Check if this building is already connected (already has a node in network)
        if (network.getBuildingNode(newBuilding.id()).isPresent()) {
            LOGGER.debug("[Road] Building {} already in network — skipping", buildingName);
            roadData.markChanged();
            return;
        }

        // Build list of existing buildings (exclude the new one)
        List<RoadBuildingData> existingBuildings = new ArrayList<>();
        for (RoadBuildingData bd : allBuildings) {
            if (!bd.id().equals(newBuilding.id())) {
                existingBuildings.add(bd);
            }
        }

        LOGGER.info("[Road] Incremental: connecting {} at ({},{}) to network",
                buildingName, newBuilding.x(), newBuilding.z());

        List<TemplatePlacement> chain = OrganicRoadPlanner.incrementalExpand(
                newBuilding, network, existingBuildings,
                accessFn, corePool, obstacles, rng);

        if (chain.isEmpty()) {
            LOGGER.warn("[Road] Incremental expansion produced no placements for {}",
                    buildingName);
            roadData.markChanged();
            return;
        }

        LOGGER.info("[Road] Incremental chain: {} placements for {}",
                chain.size(), buildingName);
        enqueueChain(chain, newBuilding.id(), level, config, network);

        // Register node in the network so future buildings find it
        network.addNode(new RoadNode(newBuilding.id(),
                new GridPos(
                        newBuilding.x(), newBuilding.y(), newBuilding.z()),
                RoadNode.NodeType.BUILDING));

        roadData.markChanged();
    }

    /** Build tiles from placements and enqueue as one edge (first-time MST plan). */
    private static void processPlacementsSingleEdge(List<TemplatePlacement> placements,
                                                     ServerLevel level, RoadConfig config,
                                                     RoadNetwork network) {
        BuildingSavedData bd = BuildingSavedData.get(level);
        var buildingBounds = new ArrayList<BoundingBox>();
        for (BuildingState bs : bd.getAllBuildings()) {
            if (bs.isStructureIntact()) buildingBounds.add(bs.getBounds());
        }

        // Existing road tiles
        Set<XZPoint> occupiedTiles = new HashSet<>();
        for (RoadEdge e : network.getEdges().values()) {
            if (e.getStatus() != RoadEdge.EdgeStatus.PLANNED) {
                occupiedTiles.addAll(e.getPath());
            }
        }

        JsonArray allTiles = RoadTemplatePlacer.buildTiles(
                level, placements, enginePool, buildingBounds, occupiedTiles);
        if (allTiles.isEmpty()) {
            LOGGER.warn("[Road] No tiles from full-plan {} placements", placements.size());
            return;
        }

        List<XZPoint> path = extractPath(allTiles);
        UUID edgeId = UUID.randomUUID();
        RoadEdge edge = new RoadEdge(edgeId, UUID.randomUUID(), UUID.randomUUID(), "dirt", path);
        network.addEdge(edge);
        enqueueSegments(edgeId, allTiles, config);
    }

    /** Build tiles from a single incremental chain and enqueue as one edge. */
    private static void enqueueChain(List<TemplatePlacement> chain, UUID buildingId,
                                      ServerLevel level, RoadConfig config,
                                      RoadNetwork network) {
        BuildingSavedData bd = BuildingSavedData.get(level);
        var buildingBounds = new ArrayList<BoundingBox>();
        for (BuildingState bs : bd.getAllBuildings()) {
            if (bs.isStructureIntact()) buildingBounds.add(bs.getBounds());
        }

        Set<XZPoint> occupiedTiles = new HashSet<>();
        for (RoadEdge e : network.getEdges().values()) {
            occupiedTiles.addAll(e.getPath());
        }

        JsonArray allTiles = RoadTemplatePlacer.buildTiles(
                level, chain, enginePool, buildingBounds, occupiedTiles);
        if (allTiles.isEmpty()) {
            LOGGER.warn("[Road] No tiles from incremental chain ({} placements)", chain.size());
            return;
        }

        List<XZPoint> path = extractPath(allTiles);
        UUID edgeId = UUID.randomUUID();
        RoadEdge edge = new RoadEdge(edgeId, buildingId, UUID.randomUUID(), "dirt", path);
        network.addEdge(edge);
        enqueueSegments(edgeId, allTiles, config);
    }

    private static List<XZPoint> extractPath(JsonArray tiles) {
        List<XZPoint> path = new ArrayList<>();
        for (int i = 0; i < tiles.size(); i++) {
            var t = tiles.get(i).getAsJsonObject().getAsJsonArray("pos");
            path.add(new XZPoint(t.get(0).getAsInt(), t.get(2).getAsInt()));
        }
        return path;
    }

    private static void enqueueSegments(UUID edgeId, JsonArray allTiles, RoadConfig config) {
        int maxLen = config.getSegmentMaxLength();
        int tileCount = allTiles.size();
        int segCount = 0;

        for (int start = 0; start < tileCount; start += maxLen) {
            int end = Math.min(start + maxLen, tileCount);
            JsonArray segTiles = new JsonArray();
            for (int i = start; i < end; i++) segTiles.add(allTiles.get(i));
            if (segTiles.isEmpty()) continue;

            UUID segId = UUID.randomUUID();
            RoadTaskSource.enqueueSegment(
                    new RoadTaskSource.PendingSegment(segId, edgeId, segTiles));
            segCount++;
        }
        LOGGER.info("[Road] edge {}: {} tiles → {} segments → BUILDING",
                edgeId.toString().substring(0, 8), tileCount, segCount);
    }

    private static OrganicRoadPlanner.AccessPointFn accessPointFn(BuildingSavedData bd) {
        return (bdArg, dir) -> {
            BuildingState bs = bd.getBuilding(bdArg.id());
            if (bs == null) return new XZPoint(bdArg.x(), bdArg.z());
            var bounds = bs.getBounds();
            int halfW = (bounds.maxX() - bounds.minX() + 1) / 2;
            int halfD = (bounds.maxZ() - bounds.minZ() + 1) / 2;
            int margin = Math.max(halfW, halfD) + 1;
            return new XZPoint(bdArg.x() + dir.dx() * margin, bdArg.z() + dir.dz() * margin);
        };
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
        Set<XZPoint> obstacles = collectBuildingObstacles(buildingData);
        Random rng = new Random(level.getSeed());

        PlanResult planResult = OrganicRoadPlanner.plan(
                allBuildings, accessPointFn(buildingData), 0,
                corePool, obstacles, rng);

        LOGGER.info("[Road] rebuild: {} placements, {} edges, cost={}",
                planResult.placements().size(), planResult.edgesCreated(),
                planResult.budgetUsed());

        if (!planResult.placements().isEmpty()) {
            processPlacementsSingleEdge(planResult.placements(), level, config, network);
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
