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
import com.wsteam.wandscape.core.road.IntersectionDetector;
import com.wsteam.wandscape.core.road.NetworkDiff;
import com.wsteam.wandscape.core.road.PathGenerator;
import com.wsteam.wandscape.core.road.RoadBuildingData;
import com.wsteam.wandscape.core.road.RoadEdge;
import com.wsteam.wandscape.core.road.RoadNetwork;
import com.wsteam.wandscape.core.road.RoadPlanner;
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

    // ---- build_complete handler ----

    private static void onBuildComplete(CustomEvent event) {
        ServerLevel level = getServerLevel();
        if (level == null) return;

        BuildingSavedData buildingData = BuildingSavedData.get(level);
        RoadSavedData roadData = RoadSavedData.getOrCreate(level);
        RoadConfig config = RoadConfig.getInstance();

        // Extract the new building's info from the event
        String anchorStr = event.params().get("anchor");
        String buildingName = event.params().get("building_name");
        if (anchorStr == null) {
            LOGGER.warn("build_complete event missing anchor — cannot plan roads");
            return;
        }

        BlockPos anchor = parseAnchor(anchorStr);
        if (anchor == null) return;

        // Find the BuildingState at this anchor
        BuildingState newBuildingState = findByAnchor(buildingData, anchor);
        if (newBuildingState == null) return;

        // Build the RoadBuildingData list from all current buildings
        List<RoadBuildingData> allBuildings = new ArrayList<>();
        for (BuildingState bs : buildingData.getAllBuildings()) {
            if (!bs.isStructureIntact() || bs.isShutdown()) continue;
            allBuildings.add(new RoadBuildingData(
                    bs.getBuildingId(), bs.getAnchor().getX(),
                    bs.getAnchor().getY(), bs.getAnchor().getZ()));
        }

        RoadNetwork network = roadData.getNetwork();
        int threshold = config.getBuildingThreshold();
        int buildingCount = allBuildings.size();
        roadData.setBuildingCount(buildingCount);

        RoadBuildingData newBuilding = new RoadBuildingData(
                newBuildingState.getBuildingId(),
                anchor.getX(), anchor.getY(), anchor.getZ());

        if (network.isEmpty() && buildingCount >= threshold) {
            // First time: compute full MST
            LOGGER.info("[Road] First MST triggered — {} buildings (threshold={})",
                    buildingCount, threshold);
            RoadNetwork fresh = RoadPlanner.computeMST(allBuildings, threshold);

            // Merge fresh nodes/edges into the saved network
            for (var entry : fresh.getNodes().entrySet()) {
                network.addNode(entry.getValue());
            }
            for (var entry : fresh.getEdges().entrySet()) {
                network.addEdge(entry.getValue());
            }
        } else if (!network.isEmpty()) {
            // Incremental: add the new building
            LOGGER.info("[Road] Incremental add — building {} at {}", buildingName, anchor);
            RoadPlanner.incrementalAdd(network, newBuilding);
        } else {
            LOGGER.debug("[Road] Skipping — network empty and {} buildings < threshold {}",
                    buildingCount, threshold);
            roadData.markChanged();
            return;
        }

        // Process all PLANNED edges: split, build tiles, enqueue
        processPlannedEdges(network, level, config);

        roadData.markChanged();
    }

    /** Split PLANNED edges into segments and enqueue them for building. */
    private static void processPlannedEdges(RoadNetwork network,
                                             ServerLevel level, RoadConfig config) {
        int maxLen = config.getSegmentMaxLength();
        List<RoadEdge> allEdges = new ArrayList<>(network.getEdges().values());

        // Collect all XZ points already covered by existing (non-PLANNED) edges
        Set<XZPoint> occupied = new HashSet<>();
        for (RoadEdge e : allEdges) {
            if (e.getStatus() != RoadEdge.EdgeStatus.PLANNED) {
                occupied.addAll(e.getPath());
            }
        }

        // Collect all building bounding boxes to avoid placing roads on/inside buildings
        BuildingSavedData bd = BuildingSavedData.get(level);
        var buildingBounds = new ArrayList<BoundingBox>();
        for (BuildingState bs : bd.getAllBuildings()) {
            if (bs.isStructureIntact()) {
                buildingBounds.add(bs.getBounds());
            }
        }

        // Compute intersection points across ALL edges (directional crossing only)
        Set<XZPoint> intersections = IntersectionDetector.detectAll(allEdges);

        // Track actual tile XZ positions placed by edges processed in this batch,
        // so width-expanded tiles don't overlap between edges in the same batch.
        Set<XZPoint> occupiedTiles = new HashSet<>();

        for (RoadEdge edge : allEdges) {
            if (edge.getStatus() != RoadEdge.EdgeStatus.PLANNED) continue;

            List<XZPoint> path = edge.getPath();
            List<XZPoint> freshPath = RoadPlanner.filterNewPath(path, occupied);

            if (freshPath.isEmpty()) {
                LOGGER.info("[Road] edge {} has no fresh tiles — all {} path points overlap existing roads",
                        edge.getEdgeId().toString().substring(0, 8), path.size());
                edge.setStatus(RoadEdge.EdgeStatus.COMPLETE);
                continue;
            }

            List<List<XZPoint>> segments = RoadPlanner.splitIntoSegments(freshPath, maxLen);
            boolean hasSegments = false;

            for (List<XZPoint> segPath : segments) {
                UUID segId = UUID.randomUUID();
                JsonArray tiles = RoadBuilder.buildTiles(level, segPath,
                        edge.getTier(), intersections, buildingBounds, occupiedTiles);

                if (tiles.isEmpty()) {
                    LOGGER.debug("[Road] segment {} has no passable tiles, skipping", segId);
                    continue;
                }

                RoadTaskSource.enqueueSegment(
                        new RoadTaskSource.PendingSegment(segId, edge.getEdgeId(), tiles));
                hasSegments = true;

                LOGGER.debug("[Road] enqueued segment {} ({} tiles) for edge {}",
                        segId, tiles.size(), edge.getEdgeId());
            }

            // Set status to BUILDING so this edge is not re-processed on next event
            if (hasSegments) {
                edge.setStatus(RoadEdge.EdgeStatus.BUILDING);
            } else {
                edge.setStatus(RoadEdge.EdgeStatus.COMPLETE);
            }

            // Add this edge's fresh path points to occupied so subsequent edges
            // in the same batch don't generate overlapping center-line tiles.
            occupied.addAll(freshPath);

            // Also track actual tile XZ positions for width-aware dedup
            occupiedTiles.addAll(freshPath);
        }
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

    /** Trigger a full MST rebuild, diff, and enqueue new/deprecated changes. */
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

        NetworkDiff diff = RoadPlanner.rebuild(network, allBuildings);

        LOGGER.info("[Road] rebuild diff: retained={} deprecated={} new={}",
                diff.retained().size(), diff.deprecated().size(), diff.newEdges().size());

        // Deprecated edges: mark as PLANNED (keep road blocks, don't destroy)
        for (RoadEdge edge : diff.deprecated()) {
            edge.setStatus(RoadEdge.EdgeStatus.PLANNED);
            LOGGER.info("[Road] deprecated edge {} (keeping road blocks)", edge.getEdgeId());
        }

        // New edges: add to network
        for (RoadEdge edge : diff.newEdges()) {
            network.addEdge(edge);
            LOGGER.info("[Road] new edge {} ({} tiles)", edge.getEdgeId(), edge.getPath().size());
        }

        // Process any newly PLANNED edges
        processPlannedEdges(network, level, config);

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
