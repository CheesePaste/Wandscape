package com.wsteam.wandscape.road.engine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.wsteam.wandscape.building.internal.BuildingSavedData;
import com.wsteam.wandscape.building.internal.BuildingState;
import com.wsteam.wandscape.core.event.CustomEvent;
import com.wsteam.wandscape.road.algorithm.DecorationPlanner;
import com.wsteam.wandscape.road.algorithm.NetworkDiff;
import com.wsteam.wandscape.road.core.PathPoint;
import com.wsteam.wandscape.road.core.RoadBuildingData;
import com.wsteam.wandscape.road.core.RoadEdge;
import com.wsteam.wandscape.road.core.RoadNetwork;
import com.wsteam.wandscape.road.core.RoadNode;
import com.wsteam.wandscape.road.algorithm.RoadPlanner;
import com.wsteam.wandscape.road.core.DecorationPoint;
import com.wsteam.wandscape.core.types.GridPos;
import com.wsteam.wandscape.engine.WandscapeEngine;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Listens for engine {@link CustomEvent}s related to road planning.
 *
 * <p>Handles:
 * <ul>
 *   <li>{@code build_complete} — triggers MST or incremental road planning</li>
 *   <li>{@code road_segment_complete} — updates edge build progress;
 *       when the last segment finishes, triggers decoration generation</li>
 * </ul>
 *
 * <p>Uses V1 L-shape paths with 3D coordinates (Y interpolated),
 * weighted palette surface, and edge-completion-driven decoration.
 */
public final class RoadEventListener {

    private static final String TAG = "RoadEventListener";

    private RoadEventListener() {}

    public static void register() {
        var world = WandscapeEngine.getWorld();
        if (world == null || world.eventBus == null) {
            Log.warn(TAG, "Cannot register RoadEventListener — engine not bootstrapped");
            return;
        }
        world.eventBus.subscribe(CustomEvent.class, RoadEventListener::onEvent);
        Log.info(TAG, "RoadEventListener registered on engine EventBus");
    }

    private static void onEvent(CustomEvent event) {
        Log.debug(TAG, "[Road] event received: {} params={}", event.name(), event.params().keySet());
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
            Log.debug(TAG, "[Road] Skipping — {} buildings < threshold {}", buildingCount, threshold);
            roadData.markChanged();
            return;
        }

        RoadNetwork network = roadData.getNetwork();
        int amplitude = config.getDefaultWidth() * 2;

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
            Log.info(TAG, "[Road] First MST plan — {} buildings (threshold={})",
                    buildingCount, threshold);

            RoadNetwork planned = RoadPlanner.computeMST(allBuildings, threshold, amplitude);
            if (planned.getEdges().isEmpty()) {
                Log.warn(TAG, "[Road] MST plan produced no edges");
                roadData.markChanged();
                return;
            }

            Log.info(TAG, "[Road] Full plan: {} edges", planned.getEdges().size());

            for (RoadBuildingData bd : allBuildings) {
                network.addNode(new RoadNode(bd.id(),
                        new GridPos(bd.x(), bd.y(), bd.z()),
                        RoadNode.NodeType.BUILDING));
            }

            Set<PathPoint> occupiedTiles = new HashSet<>();
            for (RoadEdge edge : planned.getEdges().values()) {
                network.addEdge(edge);
                enqueueEdge(edge, level, config, buildingBounds, occupiedTiles);
            }
            roadData.markChanged();
            return;
        }

        // ── Incremental ──
        if (newBuilding == null) {
            Log.debug(TAG, "[Road] No new building parsed — skipping incremental");
            roadData.markChanged();
            return;
        }

        if (network.getBuildingNode(newBuilding.id()).isPresent()) {
            Log.debug(TAG, "[Road] Building {} already in network — skipping", buildingName);
            roadData.markChanged();
            return;
        }

        Log.info(TAG, "[Road] Incremental: connecting {} at ({},{}) to network",
                buildingName, newBuilding.x(), newBuilding.z());

        Set<PathPoint> occupiedTiles = new HashSet<>();
        for (RoadEdge e : network.getEdges().values()) {
            occupiedTiles.addAll(e.getPlacedBlocks());
        }

        RoadPlanner.incrementalAdd(network, newBuilding, amplitude);

        for (RoadEdge edge : network.getEdges().values()) {
            if (edge.getFromNodeId().equals(newBuilding.id())
                    && edge.getStatus() == RoadEdge.EdgeStatus.PLANNED) {

                List<List<PathPoint>> segments = RoadPlanner.splitIntoSegments(
                        edge.getPath(), config.getSegmentMaxLength());
                int segCount = 0;
                for (List<PathPoint> seg : segments) {
                    List<PathPoint> fresh = RoadPlanner.filterNewPath(seg, occupiedTiles);
                    if (!fresh.isEmpty()) {
                        JsonArray tiles = RoadBuilder.buildTiles(
                                level, fresh, edge.getTier(), buildingBounds, occupiedTiles, edge.getWidth());
                        if (!tiles.isEmpty()) {
                            segCount += enqueueSegments(edge.getEdgeId(), tiles, config);
                            occupiedTiles.addAll(RoadBuilder.extractPathPoints(tiles));
                        }
                    }
                }
                if (segCount > 0) {
                    edge.incrementPendingSegments(segCount);
                    Log.info(TAG, "[Road] incremental edge {}: {} segments enqueued",
                            edge.getEdgeId().toString().substring(0, 8), segCount);
                }
                break;
            }
        }

        roadData.markChanged();
    }

    // ---- road_segment_complete handler ----

    private static void onSegmentComplete(CustomEvent event) {
        ServerLevel level = getServerLevel();
        if (level == null) {
            Log.warn(TAG, "[Road] onSegmentComplete: no server level");
            return;
        }

        String edgeIdStr = event.params().get("edge_id");
        String segIdStr = event.params().get("segment_id");
        Log.debug(TAG, "[Road] segment_complete event: edge_id={} segment_id={} params={}",
                edgeIdStr, segIdStr, event.params().keySet());

        if (edgeIdStr == null) {
            Log.warn(TAG, "[Road] segment_complete event missing edge_id — params={}",
                    event.params().keySet());
            return;
        }

        UUID edgeId;
        try {
            edgeId = UUID.fromString(edgeIdStr);
        } catch (IllegalArgumentException e) {
            Log.warn(TAG, "[Road] invalid edge_id in segment_complete: '{}'", edgeIdStr);
            return;
        }

        RoadSavedData roadData = RoadSavedData.getOrCreate(level);
        RoadEdge edge = roadData.getNetwork().getEdge(edgeId);
        if (edge == null) {
            Log.warn(TAG, "[Road] segment_complete for unknown edge {} (network has {} edges)",
                    edgeIdStr, roadData.getNetwork().edgeCount());
            return;
        }

        // Dedup via segment_id if present; always decrement counter
        UUID segmentId = null;
        if (segIdStr != null) {
            try {
                segmentId = UUID.fromString(segIdStr);
            } catch (IllegalArgumentException e) {
                Log.warn(TAG, "[Road] invalid segment_id in event: '{}' — will count anyway", segIdStr);
            }
        }

        boolean allDone;
        if (segmentId != null) {
            allDone = edge.recordSegmentComplete(segmentId);
        } else {
            // No dedup — just decrement (safe: duplicate COMPLETE is idempotent)
            allDone = edge.decrementAndCheckComplete();
        }

        int remaining = edge.getPendingSegmentCount();
        Log.info(TAG, "[Road] segment_complete: edge={} status={} remaining={} allDone={}",
                edgeIdStr, edge.getStatus(), remaining, allDone);

        if (!allDone) return;

        edge.setStatus(RoadEdge.EdgeStatus.COMPLETE);
        roadData.markChanged();
        Log.info(TAG, "[Road] edge {} → COMPLETE — triggering decoration", edgeIdStr);

        triggerDecorationForEdge(edge, level, roadData);
    }

    private static void triggerDecorationForEdge(RoadEdge edge, ServerLevel level,
                                                  RoadSavedData roadData) {
        String edgeShort = edge.getEdgeId().toString().substring(0, 8);
        RoadConfig config = RoadConfig.getInstance();

        if (!config.isDecorationEnabled()) {
            Log.info(TAG, "[Deco] edge={}: disabled in config", edgeShort);
            return;
        }
        if (edge.getDecorationTaskId() != null) {
            Log.info(TAG, "[Deco] edge={}: already enqueued (decoTaskId={})",
                    edgeShort, edge.getDecorationTaskId());
            return;
        }

        Log.info(TAG, "[Deco] edge={}: planning decorations...", edgeShort);

        BuildingSavedData buildingData = BuildingSavedData.get(level);
        var buildingBounds = new ArrayList<BoundingBox>();
        for (BuildingState bs : buildingData.getAllBuildings()) {
            buildingBounds.add(bs.getBounds());
        }

        RoadConfig.DecorationConfig deco = config.getDecorationConfig();
        int halfWidth = edge.getWidth() / 2;
        Log.info(TAG, "[Deco] edge={}: lampSpacing={} benchSpacing={} halfWidth={} (edgeWidth={})",
                edgeShort, deco.lampSpacing(), deco.benchSpacing(), halfWidth, edge.getWidth());

        List<DecorationPoint> points = DecorationPlanner.planForEdge(
                edge, deco.lampSpacing(), deco.benchSpacing(), halfWidth);

        if (points.isEmpty()) {
            Log.info(TAG, "[Deco] edge={}: planner produced 0 points (pathLen={})",
                    edgeShort, edge.getPath().size());
            return;
        }
        Log.info(TAG, "[Deco] edge={}: {} decoration points planned", edgeShort, points.size());

        JsonArray tiles = DecorationBuilder.buildTiles(points, level, buildingBounds, config);
        if (tiles.isEmpty()) {
            Log.info(TAG, "[Deco] edge={}: builder produced 0 tiles ({} points dropped by terrain/building checks)",
                    edgeShort, points.size());
            return;
        }

        // Record decoration positions for clean demolition
        edge.addPlacedBlocks(extractPlacedBlocks(tiles));

        UUID decoId = UUID.randomUUID();
        RoadTaskSource.enqueueDecoration(
                new RoadTaskSource.PendingDecoration(decoId, edge.getEdgeId(), tiles));
        edge.setDecorationTaskId(1L);
        roadData.markChanged();

        Log.info(TAG, "[Deco] edge={}: ENQUEUED {} decoration tiles ({} points → {} tiles → taskId={})",
                edgeShort, tiles.size(), points.size(), tiles.size(),
                decoId.toString().substring(0, 8));
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
        int amplitude = config.getDefaultWidth() * 2;

        var buildingBounds = new ArrayList<BoundingBox>();
        for (BuildingState bs : buildingData.getAllBuildings()) {
            // Collect ALL registered buildings — see onBuildComplete for rationale.
            buildingBounds.add(bs.getBounds());
        }

        NetworkDiff diff = RoadPlanner.rebuild(network, allBuildings, amplitude);
        Log.info(TAG, "[Road] rebuild: {} retained, {} deprecated, {} new",
                diff.retained().size(), diff.deprecated().size(), diff.newEdges().size());

        Set<PathPoint> occupiedTiles = new HashSet<>();
        for (RoadEdge e : diff.retained()) occupiedTiles.addAll(e.getPlacedBlocks());
        for (RoadEdge e : diff.deprecated()) occupiedTiles.addAll(e.getPlacedBlocks());

        for (RoadEdge edge : diff.newEdges()) {
            network.addEdge(edge);
            enqueueEdge(edge, level, config, buildingBounds, occupiedTiles);
        }

        roadData.setBuildingCount(allBuildings.size());
        roadData.markChanged();
    }

    // ---- Helpers ----

    /**
     * Enqueue all segments of an edge as NPC build tasks.
     * Public for use by the road editor path planning system.
     */
    public static void enqueueEdge(RoadEdge edge, ServerLevel level, RoadConfig config,
                                     List<BoundingBox> buildingBounds,
                                     Set<PathPoint> occupiedTiles) {
        List<List<PathPoint>> segments = RoadPlanner.splitIntoSegments(
                edge.getPath(), config.getSegmentMaxLength());
        int roadWidth = edge.getWidth();
        int segmentCount = 0;
        Set<PathPoint> allPlaced = new HashSet<>();
        for (List<PathPoint> seg : segments) {
            JsonArray tiles = RoadBuilder.buildTiles(
                    level, seg, edge.getTier(), buildingBounds, occupiedTiles, roadWidth);
            if (!tiles.isEmpty()) {
                segmentCount += enqueueSegments(edge.getEdgeId(), tiles, config);
                occupiedTiles.addAll(RoadBuilder.extractPathPoints(tiles));
                allPlaced.addAll(extractPlacedBlocks(tiles));
            }
        }
        if (segmentCount > 0) {
            edge.incrementPendingSegments(segmentCount);
            edge.addPlacedBlocks(allPlaced);
            Log.info(TAG, "[Road] enqueueEdge: edge={} segments={} placedBlocks={} status={}",
                    edge.getEdgeId().toString().substring(0, 8), segmentCount,
                    edge.getPlacedBlocks().size(), edge.getStatus());
        } else {
            Log.warn(TAG, "[Road] enqueueEdge: edge={} produced 0 segments! pathLen={}",
                    edge.getEdgeId().toString().substring(0, 8), edge.getPath().size());
        }
    }

    /** @return number of task segments created */
    private static int enqueueSegments(UUID edgeId, JsonArray allTiles, RoadConfig config) {
        int maxLen = config.getSegmentMaxLength();
        int tileCount = allTiles.size();
        int count = 0;

        for (int start = 0; start < tileCount; start += maxLen) {
            int end = Math.min(start + maxLen, tileCount);
            JsonArray segTiles = new JsonArray();
            for (int i = start; i < end; i++) segTiles.add(allTiles.get(i));
            if (segTiles.isEmpty()) continue;

            UUID segId = UUID.randomUUID();
            RoadTaskSource.enqueueSegment(
                    new RoadTaskSource.PendingSegment(segId, edgeId, segTiles));
            count++;
        }
        Log.info(TAG, "[Road] edge {}: {} tiles in {} segments",
                edgeId.toString().substring(0, 8), tileCount, count);
        return count;
    }

    /** Extract 3D block positions from a JSON tile array for demolition tracking. */
    private static Set<PathPoint> extractPlacedBlocks(JsonArray tiles) {
        Set<PathPoint> result = new HashSet<>();
        for (int i = 0; i < tiles.size(); i++) {
            var t = tiles.get(i).getAsJsonObject().getAsJsonArray("pos");
            result.add(new PathPoint(
                    t.get(0).getAsInt(), t.get(1).getAsInt(), t.get(2).getAsInt()));
        }
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
