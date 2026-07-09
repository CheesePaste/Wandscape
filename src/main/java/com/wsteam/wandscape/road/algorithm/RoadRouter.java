package com.wsteam.wandscape.road.algorithm;

import com.wsteam.wandscape.road.core.*;
import com.wsteam.wandscape.shared.log.Log;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Plans item transport routes using the colony road network.
 *
 * <p>Supports gap bridging: seamlessly handles T-junctions, road width variations,
 * and small broken gaps by allowing off-road "jumps" between disconnected edges.
 *
 * <p>Also supports lazy player-road blobs: contiguous player-built surfaces
 * discovered by BFS and cached in {@link RoadBlobCache}. Blob boundaries are
 * injected as "虫洞" nodes — NPCs can traverse them at on-road speed.
 *
 * <p>Pure core — zero MC dependencies. Takes a {@link RoadNetwork} and
 * start/end {@link PathPoint}s, returns a list of {@link RouteSegment}s.
 *
 * <p>If the road network is empty or unreachable, returns an empty list;
 * the caller falls back to direct off-road line-of-sight transport.
 *
 * <h3>Algorithm</h3>
 * <ol>
 *   <li>Find nearest {@link PathPoint} on any edge or cached blob to start and end.</li>
 *   <li>Build graph: within-edge consecutive points + cross-edge gap bridging
 *       + blob centroid虫洞 connections. Each edge records whether it's on-road.</li>
 *   <li>Dijkstra shortest-path (weight = estimated ticks).</li>
 *   <li>Build segments: use edge onRoad flag from graph, merge colinear same-type segments.</li>
 * </ol>
 */
public final class RoadRouter {

    private static final String TAG = "RoadRouter";

    /** Ticks per block of XZ distance for off-road transport (2 blocks/sec). */
    public static final int TICKS_PER_BLOCK_OFF_ROAD = 10;

    /** Ticks per block of XZ distance for on-road transport (4 blocks/sec). */
    public static final int TICKS_PER_BLOCK_ON_ROAD = 5;

    /** Maximum allowed gap to bridge between disconnected roads (in blocks). */
    private static final int MAX_GAP_XZ = 6;
    private static final int MAX_GAP_Y = 3;
    private static final int NPC_MAX_Y_STEP = 1;

    private RoadRouter() {}

    // ── Public API ────────────────────────────────────────────────

    /**
     * Plan a route for an NPC walker.
     *
     * <p>Same as {@link #plan(RoadNetwork, PathPoint, PathPoint)} but rejects
     * any off-road segment whose |dy| &gt; 1 — NPCs can't fly or climb sheer
     * walls like item transport can. On-road segments are always safe
     * (roads are flat).
     *
     * <p>If the route is rejected, the caller should fall back to searching
     * for a different walkable destination near the building.
     *
     * @param network  the colony's road network (may be empty)
     * @param start    world position the NPC starts from
     * @param end      world position the NPC wants to reach
     * @return ordered list of segments (empty = unreachable for NPC)
     */
    public static TransportRoute planNpc(@Nullable RoadNetwork network,
                                             PathPoint start, PathPoint end) {
        return planNpc(network, null, start, end);
    }

    /**
     * Plan a route for an NPC walker, with lazy player-road blob support.
     *
     * @param network   the colony's road network (may be empty)
     * @param blobCache cached player-built road blobs (nullable)
     * @param start     world position the NPC starts from
     * @param end       world position the NPC wants to reach
     * @return ordered list of segments (empty = unreachable for NPC)
     */
    public static TransportRoute planNpc(@Nullable RoadNetwork network,
                                              @Nullable RoadBlobCache blobCache,
                                              PathPoint start, PathPoint end) {
        TransportRoute route = plan(network, blobCache, start, end);
        if (route.isEmpty()) return route;

        for (SplineLeg seg : route.legs()) {
            if (!seg.offRoad()) continue;
            SplineVec3 startPos = seg.spline().evaluate(seg.uStart()).position();
            SplineVec3 endPos = seg.spline().evaluate(seg.uEnd()).position();
            int dy = Math.abs((int) endPos.y() - (int) startPos.y());
            if (dy > NPC_MAX_Y_STEP) {
                Log.info(TAG, "NPC: rejected — off-road dy=%d > %d", dy, NPC_MAX_Y_STEP);
                return new TransportRoute(List.of());
            }
        }
        return route;
    }

    /**
     * Plan a route using the road network.
     *
     * @param network  the colony's road network (may be empty)
     * @param start    world position to start from
     * @param end      world position to deliver to
     * @return ordered list of segments (empty = no road available, use direct)
     */
    public static TransportRoute plan(@Nullable RoadNetwork network,
                                          PathPoint start, PathPoint end) {
        return plan(network, null, start, end);
    }

    /**
     * Plan a route using the road network and lazy player-road blob cache.
     *
     * @param network   the colony's road network (may be empty)
     * @param blobCache cached player-built road blobs (nullable)
     * @param start     world position to start from
     * @param end       world position to deliver to
     * @return ordered list of segments (empty = no road available, use direct)
     */
    public static TransportRoute plan(@Nullable RoadNetwork network,
                                           @Nullable RoadBlobCache blobCache,
                                           PathPoint start, PathPoint end) {
        // ── 0. Direct distance baseline ──
        int directDist = start.manhattanXZTo(end);
        int directTicks = directDist * TICKS_PER_BLOCK_OFF_ROAD;

        boolean hasNetwork = network != null && !network.isEmpty();
        boolean hasBlobs = blobCache != null && !blobCache.isEmpty();

        if (!hasNetwork && !hasBlobs) {
            Log.info(TAG, "No road network and no blobs — direct fly %d blocks", directDist);
            return new TransportRoute(List.of());
        }

        Log.info(TAG, "═════ Route planning ═════");
        Log.info(TAG, "  From:  %s", start);
        Log.info(TAG, "  To:    %s", end);
        if (blobCache != null && blobCache.blobCount() > 0) {
            Log.info(TAG, "  Blobs: %d cached (%d blocks total)",
                    blobCache.blobCount(), blobCache.totalBlockCount());
        }

        // ── 1. Nearest road entry/exit ──
        PathPoint roadStart = null;
        PathPoint roadEnd = null;

        if (hasNetwork) {
            roadStart = network.findNearestWalkablePathPoint(start);
            roadEnd = network.findNearestWalkablePathPoint(end);
        }

        // Also check blob cache for possibly nearer entry/exit points
        if (blobCache != null) {
            PathPoint blobStart = blobCache.findNearestBlobPoint(start);
            PathPoint blobEnd = blobCache.findNearestBlobPoint(end);
            roadStart = betterSnap(roadStart, blobStart, start);
            roadEnd = betterSnap(roadEnd, blobEnd, end);
        }

        if (roadStart == null || roadEnd == null) {
            Log.info(TAG, "  No reachable road point found → direct fly");
            return new TransportRoute(List.of());
        }

        int entryDist = start.manhattanXZTo(roadStart);
        int exitDist = end.manhattanXZTo(roadEnd);
        int roadDist = roadStart.manhattanXZTo(roadEnd);

        // Smart early exit
        int estimatedTicks = (entryDist + exitDist) * TICKS_PER_BLOCK_OFF_ROAD
                + roadDist * TICKS_PER_BLOCK_ON_ROAD;
        if (estimatedTicks > directTicks * 1.5) {
            Log.info(TAG, "  Estimated network time too high (%d ticks > %d * 1.5) → direct fly",
                    estimatedTicks, directTicks);
            return new TransportRoute(List.of());
        }

        // ── 2. Build graph ──
        Graph graph = buildGraph(network, blobCache);

        // ── 3. Dijkstra ──
        NodeKey startKey = new NodeKey(roadStart.x(), roadStart.y(), roadStart.z());
        NodeKey endKey = new NodeKey(roadEnd.x(), roadEnd.y(), roadEnd.z());

        if (!graph.nodes.containsKey(startKey)) startKey = graph.findNearest(roadStart);
        if (!graph.nodes.containsKey(endKey)) endKey = graph.findNearest(roadEnd);

        if (startKey == null || endKey == null || startKey.equals(endKey)) {
            Log.info(TAG, "  Points invalid or equal in graph → direct fly");
            return new TransportRoute(List.of());
        }

        DijkstraResult dij = dijkstra(graph, startKey, endKey);

        if (dij.path == null || dij.path.isEmpty()) {
            Log.warn(TAG, "  No road path found → direct fly");
            return new TransportRoute(List.of());
        }

        // ── 4. Build segments using graph edge onRoad metadata ──
        List<SplineLeg> rawLegs = new ArrayList<>();

        // 4a. Start -> first graph node (always off-road)
        PathPoint firstRoad = dij.path.get(0);
        if (start.manhattanXZTo(firstRoad) > 0 || start.y() != firstRoad.y()) {
            SplineModel gap = new SplineModel();
            SplineVec3 pA = new SplineVec3(start.x() + 0.5, start.y() + 0.5, start.z() + 0.5);
            SplineVec3 pB = new SplineVec3(firstRoad.x() + 0.5, firstRoad.y() + 1.0, firstRoad.z() + 0.5);
            gap.getPoints().add(new SplinePoint(pA, pA, pA, true));
            gap.getPoints().add(new SplinePoint(pB, pB, pB, true));
            rawLegs.add(new SplineLeg(gap, 0, 1, true));
        }

        // 4b. Path internally: look up each edge in the graph for its onRoad flag
        for (int i = 0; i < dij.path.size() - 1; i++) {
            PathPoint a = dij.path.get(i);
            PathPoint b = dij.path.get(i + 1);
            NodeKey aKey = new NodeKey(a.x(), a.y(), a.z());
            NodeKey bKey = new NodeKey(b.x(), b.y(), b.z());

            EdgeInfo info = graph.getEdgeInfo(aKey, bKey);
            if (info == null) continue; // Should never happen
            
            if (info.roadEdge != null) {
                rawLegs.add(new SplineLeg(info.roadEdge.getSpline(), info.uStart, info.uEnd, !info.onRoad));
            } else {
                SplineModel gap = new SplineModel();
                SplineVec3 pA = new SplineVec3(a.x() + 0.5, a.y() + (info.onRoad ? 1.0 : 0.5), a.z() + 0.5);
                SplineVec3 pB = new SplineVec3(b.x() + 0.5, b.y() + (info.onRoad ? 1.0 : 0.5), b.z() + 0.5);
                gap.getPoints().add(new SplinePoint(pA, pA, pA, true));
                gap.getPoints().add(new SplinePoint(pB, pB, pB, true));
                rawLegs.add(new SplineLeg(gap, 0, 1, !info.onRoad));
            }
        }

        // 4c. Last graph node -> End (always off-road)
        PathPoint lastRoad = dij.path.get(dij.path.size() - 1);
        if (end.manhattanXZTo(lastRoad) > 0 || end.y() != lastRoad.y()) {
            SplineModel gap = new SplineModel();
            SplineVec3 pA = new SplineVec3(lastRoad.x() + 0.5, lastRoad.y() + 1.0, lastRoad.z() + 0.5);
            SplineVec3 pB = new SplineVec3(end.x() + 0.5, end.y() + 0.5, end.z() + 0.5);
            gap.getPoints().add(new SplinePoint(pA, pA, pA, true));
            gap.getPoints().add(new SplinePoint(pB, pB, pB, true));
            rawLegs.add(new SplineLeg(gap, 0, 1, true));
        }

        // 4d. Simplify colinear/adjacent segments
        List<SplineLeg> legs = simplifyLegs(rawLegs);

        // ── 5. Summary ──
        int totalTicks = 0;
        int offRoadBlocks = 0, onRoadBlocks = 0;

        for (SplineLeg leg : legs) {
            int dist = (int) leg.getApproxLength();
            if (!leg.offRoad()) {
                onRoadBlocks += dist;
                totalTicks += dist * TICKS_PER_BLOCK_ON_ROAD;
            } else {
                offRoadBlocks += dist;
                totalTicks += dist * TICKS_PER_BLOCK_OFF_ROAD;
            }
        }

        int saved = directTicks - totalTicks;
        Log.info(TAG, "  Legs: %d (%d off-road, %d on-road)",
                legs.size(),
                (int) legs.stream().filter(SplineLeg::offRoad).count(),
                (int) legs.stream().filter(l -> !l.offRoad()).count());
        Log.info(TAG, "  Time: %d ticks (direct would be %d ticks, saved %.0f%%)",
                totalTicks, directTicks,
                directTicks > 0 ? (double) saved / directTicks * 100 : 0);
        Log.info(TAG, "═════ Route planned ✓");

        return new TransportRoute(legs);
    }

    // ── [RoadPlan] logging ────────────────────────────────────────

    // Removed logRoadPlan

    // ── Snap helper ───────────────────────────────────────────────

    @Nullable
    private static PathPoint betterSnap(@Nullable PathPoint a, @Nullable PathPoint b,
                                         PathPoint target) {
        if (a == null) return b;
        if (b == null) return a;
        return scoreSnap(a, target) <= scoreSnap(b, target) ? a : b;
    }

    private static double scoreSnap(PathPoint pp, PathPoint target) {
        int xzDist = pp.manhattanXZTo(target);
        int dy = Math.abs(pp.y() - target.y());
        double score = xzDist + dy * 0.8;
        if (dy <= xzDist) score -= 0.4 * xzDist;
        return score;
    }

    // ── Graph building ────────────────────────────────────────────

    private static Graph buildGraph(@Nullable RoadNetwork network,
                                    @Nullable RoadBlobCache blobCache) {
        Graph graph = new Graph();
        Set<NodeKey> allPoints = new HashSet<>();
        List<NodeKey> edgeEndpoints = new ArrayList<>();

        // Phase 1: Network edges (all on-road)
        if (network != null) {
            for (RoadEdge edge : network.getEdges().values()) {
                if (edge.getStatus() != RoadEdge.EdgeStatus.COMPLETE) continue;
                List<SplinePointCache> pts = edge.getDetailedPathCache();
                if (pts.isEmpty()) continue;

                for (int i = 0; i < pts.size(); i++) {
                    PathPoint p = pts.get(i).point();
                    NodeKey key = new NodeKey(p.x(), p.y(), p.z());
                    allPoints.add(key);

                    if (i == 0 || i == pts.size() - 1) {
                        edgeEndpoints.add(key);
                    }

                    if (i < pts.size() - 1) {
                        SplinePointCache next = pts.get(i + 1);
                        NodeKey nextKey = new NodeKey(next.point().x(), next.point().y(), next.point().z());
                        int distXZ = p.manhattanXZTo(next.point());
                        int weight = Math.max(1, distXZ) * TICKS_PER_BLOCK_ON_ROAD;
                        graph.addEdge(key, nextKey, weight, true /* onRoad */, edge, pts.get(i).u(), next.u());
                    }
                }
            }
        }

        // Phase 2: Blob虫洞 (all on-road)
        List<NodeKey> blobBoundaryNodes = new ArrayList<>();
        if (blobCache != null && !blobCache.isEmpty()) {
            for (Map.Entry<UUID, Set<PathPoint>> entry : blobCache.getAllBlobs().entrySet()) {
                UUID blobId = entry.getKey();
                Set<PathPoint> boundaries = blobCache.getBoundaryPoints(blobId);

                if (boundaries.size() < 2) continue;

                List<NodeKey> bNodes = new ArrayList<>();
                for (PathPoint bp : boundaries) {
                    NodeKey key = new NodeKey(bp.x(), bp.y(), bp.z());
                    bNodes.add(key);
                    allPoints.add(key);
                    blobBoundaryNodes.add(key);
                }

                NodeKey centroid = computeCentroid(bNodes);

                // Connect each boundary point to centroid — onRoad
                for (NodeKey bn : bNodes) {
                    int dxz = Math.abs(bn.x() - centroid.x()) + Math.abs(bn.z() - centroid.z());
                    int weight = Math.max(1, dxz) * TICKS_PER_BLOCK_ON_ROAD;
                    graph.addEdge(bn, centroid, weight, true /* onRoad */, null, 0, 0);
                }
            }
        }

        // Phase 3: Gap bridging (all off-road)
        List<NodeKey> allEndpoints = new ArrayList<>(edgeEndpoints);
        allEndpoints.addAll(blobBoundaryNodes);

        for (NodeKey ep : allEndpoints) {
            for (NodeKey pt : allPoints) {
                if (ep.equals(pt)) continue;
                if (graph.neighbors(ep).containsKey(pt)) continue;

                int dx = Math.abs(ep.x() - pt.x());
                int dz = Math.abs(ep.z() - pt.z());
                int dy = Math.abs(ep.y() - pt.y());

                if (dx + dz <= MAX_GAP_XZ && dy <= MAX_GAP_Y) {
                    int distXZ = dx + dz;
                    int weight = Math.max(1, distXZ) * TICKS_PER_BLOCK_OFF_ROAD;
                    graph.addEdge(ep, pt, weight, false /* offRoad */, null, 0, 0);
                }
            }
        }

        return graph;
    }

    private static NodeKey computeCentroid(List<NodeKey> nodes) {
        int n = nodes.size();
        int[] xs = new int[n];
        int[] zs = new int[n];

        for (int i = 0; i < n; i++) {
            xs[i] = nodes.get(i).x();
            zs[i] = nodes.get(i).z();
        }
        Arrays.sort(xs);
        Arrays.sort(zs);

        int cx = xs[n / 2];
        int cz = zs[n / 2];

        Map<Integer, Integer> yCounts = new HashMap<>();
        for (NodeKey nk : nodes) {
            yCounts.merge(nk.y(), 1, Integer::sum);
        }
        int cy = nodes.get(0).y();
        int bestCount = 0;
        for (var entry : yCounts.entrySet()) {
            if (entry.getValue() > bestCount) {
                bestCount = entry.getValue();
                cy = entry.getKey();
            }
        }

        return new NodeKey(cx, cy, cz);
    }

    // ── Dijkstra ──────────────────────────────────────────────────

    private static DijkstraResult dijkstra(Graph graph, NodeKey start, NodeKey end) {
        Map<NodeKey, Integer> dist = new HashMap<>();
        Map<NodeKey, NodeKey> prev = new HashMap<>();
        PriorityQueue<DistNode> pq = new PriorityQueue<>(Comparator.comparingInt(d -> d.dist));

        dist.put(start, 0);
        pq.add(new DistNode(start, 0));
        int visited = 0;

        while (!pq.isEmpty()) {
            DistNode cur = pq.poll();
            if (cur.dist > dist.getOrDefault(cur.key, Integer.MAX_VALUE)) continue;
            visited++;
            if (cur.key.equals(end)) break;

            for (var entry : graph.neighbors(cur.key).entrySet()) {
                NodeKey nb = entry.getKey();
                int newDist = cur.dist + entry.getValue().weight;
                if (newDist < dist.getOrDefault(nb, Integer.MAX_VALUE)) {
                    dist.put(nb, newDist);
                    prev.put(nb, cur.key);
                    pq.add(new DistNode(nb, newDist));
                }
            }
        }

        List<PathPoint> path = null;
        if (prev.containsKey(end) || end.equals(start)) {
            path = new ArrayList<>();
            NodeKey cur = end;
            while (cur != null) {
                path.add(new PathPoint(cur.x(), cur.y(), cur.z()));
                if (cur.equals(start)) break;
                cur = prev.get(cur);
            }
            Collections.reverse(path);
        }

        return new DijkstraResult(visited, path);
    }

    record DijkstraResult(int visited, @Nullable List<PathPoint> path) {}

    // ── Simplification ────────────────────────────────────────────

    private static List<SplineLeg> simplifyLegs(List<SplineLeg> raw) {
        if (raw.isEmpty()) return raw;

        List<SplineLeg> result = new ArrayList<>();
        SplineLeg current = raw.get(0);

        for (int i = 1; i < raw.size(); i++) {
            SplineLeg next = raw.get(i);

            // Merge if they share the same spline and the endpoint matches the next startpoint perfectly
            if (current.spline() == next.spline() && current.offRoad() == next.offRoad() && current.uEnd() == next.uStart()) {
                current = new SplineLeg(current.spline(), current.uStart(), next.uEnd(), current.offRoad());
            } else {
                result.add(current);
                current = next;
            }
        }
        result.add(current);
        return result;
    }

    // ── Internal types ────────────────────────────────────────────

    record NodeKey(int x, int y, int z) {}

    private record DistNode(NodeKey key, int dist) {}

    /** Per-edge metadata: travel weight (ticks) + whether it's on-road + Spline tracing data. */
    private record EdgeInfo(int weight, boolean onRoad, @Nullable RoadEdge roadEdge, double uStart, double uEnd) {}

    private static class Graph {
        final Map<NodeKey, Map<NodeKey, EdgeInfo>> nodes = new HashMap<>();

        void addEdge(NodeKey n1, NodeKey n2, int weight, boolean onRoad, @Nullable RoadEdge roadEdge, double uStart, double uEnd) {
            EdgeInfo info = new EdgeInfo(weight, onRoad, roadEdge, uStart, uEnd);
            nodes.computeIfAbsent(n1, k -> new HashMap<>()).merge(n2, info,
                    (old, neu) -> old.weight <= neu.weight ? old : neu);
            
            EdgeInfo reverseInfo = new EdgeInfo(weight, onRoad, roadEdge, uEnd, uStart);
            nodes.computeIfAbsent(n2, k -> new HashMap<>()).merge(n1, reverseInfo,
                    (old, neu) -> old.weight <= neu.weight ? old : neu);
        }

        Map<NodeKey, EdgeInfo> neighbors(NodeKey n) {
            return nodes.getOrDefault(n, Map.of());
        }

        /**
         * Query whether the edge n1→n2 is on-road.
         * Returns false if the edge doesn't exist (shouldn't happen for Dijkstra path).
         */
        boolean isOnRoad(NodeKey n1, NodeKey n2) {
            EdgeInfo info = nodes.getOrDefault(n1, Map.of()).get(n2);
            return info != null && info.onRoad;
        }

        EdgeInfo getEdgeInfo(NodeKey n1, NodeKey n2) {
            return nodes.getOrDefault(n1, Map.of()).get(n2);
        }

        @Nullable
        NodeKey findNearest(PathPoint target) {
            NodeKey best = null;
            int bestDist = Integer.MAX_VALUE;
            for (NodeKey n : nodes.keySet()) {
                int xzDist = Math.abs(n.x() - target.x()) + Math.abs(n.z() - target.z());
                int yDist = Math.abs(n.y() - target.y());
                int score = xzDist * 100 + yDist;
                if (score < bestDist) {
                    bestDist = score;
                    best = n;
                }
            }
            return best;
        }
    }
}
