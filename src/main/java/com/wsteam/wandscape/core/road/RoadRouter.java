package com.wsteam.wandscape.core.road;

import com.wsteam.wandscape.core.Log;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Plans item transport routes using the colony road network.
 *
 * <p>Pure core — zero MC dependencies. Takes a {@link RoadNetwork} and
 * start/end {@link PathPoint}s, returns a list of {@link RouteSegment}s.
 *
 * <p>If the road network is empty or unreachable, returns an empty list;
 * the caller falls back to direct off-road line-of-sight transport.
 *
 * <h3>Algorithm</h3>
 * <ol>
 *   <li>Find nearest {@link PathPoint} on any edge to start and end.</li>
 *   <li>Build graph from all edges: each unique PathPoint is a node,
 *       consecutive points within an edge are connected (weight = XZ distance),
 *       identical (x,y,z) points across different edges are connected (weight = 0).</li>
 *   <li>Dijkstra from start point to end point.</li>
 *   <li>Build segments: off-road → road chain → off-road.</li>
 * </ol>
 */
public final class RoadRouter {

    private static final String TAG = "RoadRouter";

    /** Ticks per block of XZ distance for off-road transport (2 blocks/sec). */
    public static final int TICKS_PER_BLOCK_OFF_ROAD = 10;

    /** Ticks per block of XZ distance for on-road transport (4 blocks/sec). */
    public static final int TICKS_PER_BLOCK_ON_ROAD = 5;

    private RoadRouter() {}

    /**
     * Plan a route using the road network.
     *
     * @param network  the colony's road network (may be empty)
     * @param start    world position to start from
     * @param end      world position to deliver to
     * @return ordered list of segments (empty = no road available, use direct)
     */
    public static List<RouteSegment> plan(@Nullable RoadNetwork network,
                                          PathPoint start, PathPoint end) {
        // ── 0. Direct distance baseline ──
        int directDist = start.manhattanXZTo(end);

        if (network == null || network.isEmpty()) {
            Log.info(TAG, "No road network — direct fly %d blocks", directDist);
            return List.of();
        }

        int nodeCount = network.nodeCount();
        int edgeCount = network.edgeCount();
        int totalPathPoints = 0;
        for (RoadEdge e : network.getEdges().values()) {
            totalPathPoints += e.getPath().size();
        }

        Log.info(TAG, "═════ Route planning ═════");
        Log.info(TAG, "  From:  %s", start);
        Log.info(TAG, "  To:    %s", end);
        Log.info(TAG, "  Direct XZ distance: %d blocks", directDist);
        Log.info(TAG, "  Network: %d nodes, %d edges, %d path points",
                nodeCount, edgeCount, totalPathPoints);

        // ── 1. Nearest road entry/exit ──
        PathPoint roadStart = network.findNearestWalkablePathPoint(start);
        PathPoint roadEnd = network.findNearestWalkablePathPoint(end);

        if (roadStart == null || roadEnd == null) {
            Log.info(TAG, "  No reachable road point found → direct fly");
            return List.of();
        }

        int entryDist = start.manhattanXZTo(roadStart);
        int exitDist = end.manhattanXZTo(roadEnd);
        int roadDist = roadStart.manhattanXZTo(roadEnd);

        Log.info(TAG, "  Road entry: %s (off-road: %d blocks)", roadStart, entryDist);
        Log.info(TAG, "  Road exit:  %s (off-road: %d blocks)", roadEnd, exitDist);
        Log.info(TAG, "  Road XZ span: %d blocks", roadDist);

        // If the road entry/exit are too close or going via road is much worse, skip
        if (roadDist <= 2) {
            Log.info(TAG, "  Road span too short (≤2) → direct fly");
            return List.of();
        }

        int viaRoadDist = entryDist + roadDist + exitDist;
        if (viaRoadDist > directDist * 2) {
            Log.info(TAG, "  Via-road %d > direct*2 %d → detour too long, direct fly",
                    viaRoadDist, directDist * 2);
            return List.of();
        }

        // ── 2. Build graph ──
        Graph graph = buildGraph(network);
        Log.info(TAG, "  Graph: %d unique position nodes", graph.nodes.size());

        // ── 3. Dijkstra ──
        NodeKey startKey = new NodeKey(roadStart.x(), roadStart.y(), roadStart.z());
        NodeKey endKey = new NodeKey(roadEnd.x(), roadEnd.y(), roadEnd.z());

        if (!graph.nodes.containsKey(startKey)) {
            startKey = graph.findNearest(roadStart);
            if (startKey == null) {
                Log.warn(TAG, "  Start point not in graph → direct fly");
                return List.of();
            }
            Log.info(TAG, "  Snapped start: %s", startKey);
        }
        if (!graph.nodes.containsKey(endKey)) {
            endKey = graph.findNearest(roadEnd);
            if (endKey == null) {
                Log.warn(TAG, "  End point not in graph → direct fly");
                return List.of();
            }
            Log.info(TAG, "  Snapped end: %s", endKey);
        }

        if (startKey.equals(endKey)) {
            Log.info(TAG, "  Start==End in graph → direct fly");
            return List.of();
        }

        DijkstraResult dij = dijkstra(graph, startKey, endKey);
        Log.info(TAG, "  Dijkstra: visited %d nodes, path found: %s (%d nodes)",
                dij.visited, dij.path != null, dij.path != null ? dij.path.size() : 0);

        if (dij.path == null || dij.path.isEmpty()) {
            Log.warn(TAG, "  No road path found → direct fly");
            return List.of();
        }

        // Simplify
        List<PathPoint> simplified = simplifyPath(dij.path);
        Log.info(TAG, "  Simplified: %d → %d points", dij.path.size(), simplified.size());

        // ── 4. Build segments ──
        List<RouteSegment> segments = new ArrayList<>();

        // Off-road: start → first road point
        if (start.manhattanXZTo(simplified.get(0)) > 0) {
            segments.add(new RouteSegment(
                    start.x(), start.y(), start.z(),
                    simplified.get(0).x(), simplified.get(0).y(), simplified.get(0).z(),
                    false));
        }

        // On-road: chain
        for (int i = 0; i < simplified.size() - 1; i++) {
            PathPoint a = simplified.get(i);
            PathPoint b = simplified.get(i + 1);
            segments.add(new RouteSegment(a.x(), a.y(), a.z(), b.x(), b.y(), b.z(), true));
        }

        // Off-road: last road point → end
        PathPoint lastRoad = simplified.get(simplified.size() - 1);
        if (lastRoad.manhattanXZTo(end) > 0) {
            segments.add(new RouteSegment(
                    lastRoad.x(), lastRoad.y(), lastRoad.z(),
                    end.x(), end.y(), end.z(),
                    false));
        }

        // ── 5. Summary ──
        int offRoadBlocks = 0;
        int onRoadBlocks = 0;
        for (RouteSegment seg : segments) {
            int dist = (int) (Math.abs(seg.toX() - seg.fromX()) + Math.abs(seg.toZ() - seg.fromZ()));
            if (seg.onRoad()) {
                onRoadBlocks += dist;
            } else {
                offRoadBlocks += dist;
            }
        }
        int totalTicks = offRoadBlocks * TICKS_PER_BLOCK_OFF_ROAD
                + onRoadBlocks * TICKS_PER_BLOCK_ON_ROAD;
        int directTicks = directDist * TICKS_PER_BLOCK_OFF_ROAD;
        int saved = directTicks - totalTicks;

        Log.info(TAG, "  Segments: %d (%d off-road, %d on-road)",
                segments.size(),
                (int) segments.stream().filter(s -> !s.onRoad()).count(),
                (int) segments.stream().filter(RouteSegment::onRoad).count());
        Log.info(TAG, "  Distance: %d off-road + %d on-road = %d blocks",
                offRoadBlocks, onRoadBlocks, offRoadBlocks + onRoadBlocks);
        Log.info(TAG, "  Time: %d ticks (direct would be %d ticks, saved %d = %.0f%%)",
                totalTicks, directTicks, saved,
                directTicks > 0 ? (double) saved / directTicks * 100 : 0);
        Log.info(TAG, "═════ Route planned ✓");

        return segments;
    }

    // ── Dijkstra ──

    private static DijkstraResult dijkstra(Graph graph, NodeKey start, NodeKey end) {
        Map<NodeKey, Integer> dist = new HashMap<>();
        Map<NodeKey, NodeKey> prev = new HashMap<>();
        PriorityQueue<DistNode> pq = new PriorityQueue<>(Comparator.comparingInt(d -> d.dist));

        for (NodeKey n : graph.nodes.keySet()) {
            dist.put(n, Integer.MAX_VALUE);
        }
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
                int newDist = cur.dist + entry.getValue();
                if (newDist < dist.getOrDefault(nb, Integer.MAX_VALUE)) {
                    dist.put(nb, newDist);
                    prev.put(nb, cur.key);
                    pq.add(new DistNode(nb, newDist));
                }
            }
        }

        // Reconstruct path
        List<PathPoint> path = null;
        if (prev.containsKey(end) || end.equals(start)) {
            path = new ArrayList<>();
            NodeKey cur = end;
            while (cur != null) {
                path.add(new PathPoint(cur.x, cur.y, cur.z));
                if (cur.equals(start)) break;
                cur = prev.get(cur);
            }
            Collections.reverse(path);
        }

        return new DijkstraResult(visited, path);
    }

    record DijkstraResult(int visited, @Nullable List<PathPoint> path) {}

    /**
     * Remove redundant intermediate points on straight lines to reduce
     * segment count without changing the route.
     */
    private static List<PathPoint> simplifyPath(List<PathPoint> path) {
        if (path.size() <= 2) return path;

        List<PathPoint> result = new ArrayList<>();
        result.add(path.get(0));

        for (int i = 1; i < path.size() - 1; i++) {
            PathPoint prev = result.get(result.size() - 1);
            PathPoint cur = path.get(i);
            PathPoint next = path.get(i + 1);

            // Keep if direction changes (not colinear in XZ)
            int dx1 = cur.x() - prev.x();
            int dz1 = cur.z() - prev.z();
            int dx2 = next.x() - cur.x();
            int dz2 = next.z() - cur.z();

            if (dx1 != dx2 || dz1 != dz2) {
                result.add(cur);
            }
            // Also keep Y changes so the item follows terrain
            if (cur.y() != prev.y() || cur.y() != next.y()) {
                result.add(cur);
            }
        }

        result.add(path.get(path.size() - 1));
        return result;
    }

    // ── Graph building ──

    /** Build adjacency graph from all edges' PathPoints. */
    private static Graph buildGraph(RoadNetwork network) {
        Graph graph = new Graph();

        // Map from (x,y,z) to set of edge IDs that pass through it (for cross-edge links)
        Map<NodeKey, Set<UUID>> pointToEdges = new HashMap<>();

        for (RoadEdge edge : network.getEdges().values()) {
            List<PathPoint> pts = edge.getPath();
            if (pts.size() < 2) continue;

            for (int i = 0; i < pts.size(); i++) {
                PathPoint p = pts.get(i);
                NodeKey key = new NodeKey(p.x(), p.y(), p.z());
                graph.nodes.putIfAbsent(key, new HashMap<>());
                pointToEdges.computeIfAbsent(key, k -> new HashSet<>()).add(edge.getEdgeId());

                // Within-edge link: connect to next point
                if (i < pts.size() - 1) {
                    PathPoint next = pts.get(i + 1);
                    NodeKey nextKey = new NodeKey(next.x(), next.y(), next.z());
                    int weight = p.manhattanXZTo(next);
                    graph.nodes.computeIfAbsent(key, k -> new HashMap<>())
                            .merge(nextKey, weight, Math::min);
                    graph.nodes.computeIfAbsent(nextKey, k -> new HashMap<>())
                            .merge(key, weight, Math::min);
                }
            }
        }

        // Cross-edge links: identical coordinates across different edges
        for (var entry : pointToEdges.entrySet()) {
            if (entry.getValue().size() < 2) continue; // only one edge passes through
            // Multiple edges share this point → already connected via within-edge links
        }

        return graph;
    }

    // ── Internal types ──

    /** Immutable (x,y,z) triple for graph nodes. */
    record NodeKey(int x, int y, int z) {}

    /** Node + distance for priority queue. */
    private record DistNode(NodeKey key, int dist) {}

    /** Simple adjacency graph. */
    private static class Graph {
        final Map<NodeKey, Map<NodeKey, Integer>> nodes = new HashMap<>();

        Map<NodeKey, Integer> neighbors(NodeKey n) {
            return nodes.getOrDefault(n, Map.of());
        }

        /** Find the graph node nearest to a target point (by XZ manhattan, then Y). */
        @Nullable
        NodeKey findNearest(PathPoint target) {
            NodeKey best = null;
            int bestDist = Integer.MAX_VALUE;
            for (NodeKey n : nodes.keySet()) {
                int xzDist = Math.abs(n.x - target.x()) + Math.abs(n.z - target.z());
                int yDist = Math.abs(n.y - target.y());
                // Primary: XZ distance. Secondary: Y difference.
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
