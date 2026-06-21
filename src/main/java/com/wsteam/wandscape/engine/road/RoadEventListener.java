package com.wsteam.wandscape.engine.road;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;

import com.google.gson.JsonArray;
import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.building.internal.BuildingSavedData;
import com.wsteam.wandscape.building.internal.BuildingState;
import com.wsteam.wandscape.core.event.CustomEvent;
import com.wsteam.wandscape.core.road.NetworkDiff;
import com.wsteam.wandscape.core.road.PathPoint;
import com.wsteam.wandscape.core.road.RoadBuildingData;
import com.wsteam.wandscape.core.road.RoadEdge;
import com.wsteam.wandscape.core.road.RoadNetwork;
import com.wsteam.wandscape.core.road.RoadNode;
import com.wsteam.wandscape.core.road.RoadPlanner;
import com.wsteam.wandscape.core.road.XZPoint;
import com.wsteam.wandscape.core.types.GridPos;
import com.wsteam.wandscape.engine.WandscapeEngine;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Listens for engine {@link CustomEvent}s related to road planning.
 *
 * <p>Handles:
 * <ul>
 *   <li>{@code build_complete} — triggers MST or incremental road planning</li>
 *   <li>{@code road_segment_complete} — updates edge build progress</li>
 * </ul>
 *
 * <p>Uses V1 L-shape paths with 3D coordinates (Y interpolated)
 * and V3 aesthetic block variation + excavation.
 */
public final class RoadEventListener {

    private static final Logger LOGGER = LogUtils.getLogger();

    private RoadEventListener() {}

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

    // ---- build_complete handler ----

    private static void onBuildComplete(CustomEvent event) {
        ServerLevel level = getServerLevel();
        if (level == null) return;

        BuildingSavedData buildingData = BuildingSavedData.get(level);
        RoadSavedData roadData = RoadSavedData.getOrCreate(level);
        RoadConfig config = RoadConfig.getInstance();

        List<RoadBuildingData> allBuildings = new ArrayList<>();
        for (BuildingState bs : buildingData.getAllBuildings()) {
            // Roads connect to any registered building with a known anchor.
            // Pattern mismatch doesn't prevent roads — the building exists here.
            if (bs.getAnchor() == null) continue;
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

        RoadNetwork network = roadData.getNetwork();

        var buildingBounds = new ArrayList<BoundingBox>();
        for (BuildingState bs : buildingData.getAllBuildings()) {
            // Collect ALL registered buildings, not just intact ones.
            // A newly-built building has structureIntact=false until
            // BuildCompleteListener processes this same event — but its
            // physical blocks are already placed and must be protected.
            buildingBounds.add(bs.getBounds());
        }

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

        // ── First-time full plan ──
        if (network.isEmpty()) {
            LOGGER.info("[Road] First MST plan — {} buildings (threshold={})",
                    buildingCount, threshold);

            RoadNetwork planned = RoadPlanner.computeMST(allBuildings, threshold);
            if (planned.getEdges().isEmpty()) {
                LOGGER.warn("[Road] MST plan produced no edges");
                roadData.markChanged();
                return;
            }

            LOGGER.info("[Road] Full plan: {} edges", planned.getEdges().size());

            for (RoadBuildingData bd : allBuildings) {
                network.addNode(new RoadNode(bd.id(),
                        new GridPos(bd.x(), bd.y(), bd.z()),
                        RoadNode.NodeType.BUILDING));
            }

            Set<XZPoint> occupiedTiles = new HashSet<>();
            for (RoadEdge edge : planned.getEdges().values()) {
                network.addEdge(edge);
                enqueueEdge(edge, level, config, buildingBounds, occupiedTiles);
            }
            roadData.markChanged();
            return;
        }

        // ── Incremental ──
        if (newBuilding == null) {
            LOGGER.debug("[Road] No new building parsed — skipping incremental");
            roadData.markChanged();
            return;
        }

        if (network.getBuildingNode(newBuilding.id()).isPresent()) {
            LOGGER.debug("[Road] Building {} already in network — skipping", buildingName);
            roadData.markChanged();
            return;
        }

        LOGGER.info("[Road] Incremental: connecting {} at ({},{}) to network",
                buildingName, newBuilding.x(), newBuilding.z());

        Set<XZPoint> occupiedTiles = new HashSet<>();
        for (RoadEdge e : network.getEdges().values()) {
            occupiedTiles.addAll(extractXz(e.getPath()));
        }

        RoadPlanner.incrementalAdd(network, newBuilding);

        for (RoadEdge edge : network.getEdges().values()) {
            if (edge.getFromNodeId().equals(newBuilding.id())
                    && edge.getStatus() == RoadEdge.EdgeStatus.PLANNED) {

                List<List<PathPoint>> segments = RoadPlanner.splitIntoSegments(
                        edge.getPath(), config.getSegmentMaxLength());
                for (List<PathPoint> seg : segments) {
                    List<PathPoint> fresh = RoadPlanner.filterNewPath(seg, occupiedTiles);
                    if (!fresh.isEmpty()) {
                        JsonArray tiles = RoadBuilder.buildTiles(
                                level, fresh, edge.getTier(), buildingBounds, new HashSet<>());
                        if (!tiles.isEmpty()) {
                            enqueueSegments(edge.getEdgeId(), tiles, config);
                            occupiedTiles.addAll(RoadBuilder.extractXZ(tiles));
                        }
                    }
                }
                break;
            }
        }

        roadData.markChanged();
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
            LOGGER.warn("[Road] invalid edge_id: {}", edgeIdStr);
            return;
        }

        RoadSavedData roadData = RoadSavedData.getOrCreate(level);
        RoadEdge edge = roadData.getNetwork().getEdge(edgeId);
        if (edge != null && edge.getStatus() == RoadEdge.EdgeStatus.BUILDING) {
            edge.setStatus(RoadEdge.EdgeStatus.COMPLETE);
            roadData.markChanged();
            LOGGER.info("[Road] edge {} marked COMPLETE", edgeId.toString().substring(0, 8));
        }
    }

    // ---- Rebuild ----

    static void triggerRebuild(UUID colonyId) {
        ServerLevel level = getServerLevel();
        if (level == null) return;

        BuildingSavedData buildingData = BuildingSavedData.get(level);
        RoadSavedData roadData = RoadSavedData.getOrCreate(level);
        RoadConfig config = RoadConfig.getInstance();

        List<RoadBuildingData> allBuildings = new ArrayList<>();
        for (BuildingState bs : buildingData.getAllBuildings()) {
            // Roads connect to any registered building with a known anchor.
            // Pattern mismatch doesn't prevent roads — the building exists here.
            if (bs.getAnchor() == null) continue;
            allBuildings.add(new RoadBuildingData(
                    bs.getBuildingId(), bs.getAnchor().getX(),
                    bs.getAnchor().getY(), bs.getAnchor().getZ()));
        }

        RoadNetwork network = roadData.getNetwork();

        var buildingBounds = new ArrayList<BoundingBox>();
        for (BuildingState bs : buildingData.getAllBuildings()) {
            // Collect ALL registered buildings — see onBuildComplete for rationale.
            buildingBounds.add(bs.getBounds());
        }

        NetworkDiff diff = RoadPlanner.rebuild(network, allBuildings);
        LOGGER.info("[Road] rebuild: {} retained, {} deprecated, {} new",
                diff.retained().size(), diff.deprecated().size(), diff.newEdges().size());

        Set<XZPoint> occupiedTiles = new HashSet<>();
        for (RoadEdge e : diff.retained()) occupiedTiles.addAll(extractXz(e.getPath()));
        for (RoadEdge e : diff.deprecated()) occupiedTiles.addAll(extractXz(e.getPath()));

        for (RoadEdge edge : diff.newEdges()) {
            network.addEdge(edge);
            enqueueEdge(edge, level, config, buildingBounds, occupiedTiles);
        }

        roadData.setBuildingCount(allBuildings.size());
        roadData.markChanged();
    }

    // ---- Helpers ----

    private static void enqueueEdge(RoadEdge edge, ServerLevel level, RoadConfig config,
                                     List<BoundingBox> buildingBounds,
                                     Set<XZPoint> occupiedTiles) {
        List<List<PathPoint>> segments = RoadPlanner.splitIntoSegments(
                edge.getPath(), config.getSegmentMaxLength());
        for (List<PathPoint> seg : segments) {
            JsonArray tiles = RoadBuilder.buildTiles(
                    level, seg, edge.getTier(), buildingBounds, occupiedTiles);
            if (!tiles.isEmpty()) {
                enqueueSegments(edge.getEdgeId(), tiles, config);
                occupiedTiles.addAll(RoadBuilder.extractXZ(tiles));
            }
        }
    }

    private static void enqueueSegments(UUID edgeId, JsonArray allTiles, RoadConfig config) {
        int maxLen = config.getSegmentMaxLength();
        int tileCount = allTiles.size();

        for (int start = 0; start < tileCount; start += maxLen) {
            int end = Math.min(start + maxLen, tileCount);
            JsonArray segTiles = new JsonArray();
            for (int i = start; i < end; i++) segTiles.add(allTiles.get(i));
            if (segTiles.isEmpty()) continue;

            UUID segId = UUID.randomUUID();
            RoadTaskSource.enqueueSegment(
                    new RoadTaskSource.PendingSegment(segId, edgeId, segTiles));
        }
        LOGGER.info("[Road] edge {}: {} tiles enqueued",
                edgeId.toString().substring(0, 8), tileCount);
    }

    private static Set<XZPoint> extractXz(List<PathPoint> path) {
        Set<XZPoint> result = new HashSet<>();
        for (PathPoint pp : path) result.add(pp.xz());
        return result;
    }

    private static BlockPos parseAnchor(String s) {
        String[] parts = s.split(",");
        if (parts.length != 3) return null;
        try {
            return new BlockPos(
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]));
        } catch (NumberFormatException e) {
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
