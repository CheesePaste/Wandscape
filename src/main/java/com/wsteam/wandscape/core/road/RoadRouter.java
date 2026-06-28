package com.wsteam.wandscape.core.road;

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
    public static List<RouteSegment> planNpc(@Nullable RoadNetwork network,
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
    public static List<RouteSegment> planNpc(@Nullable RoadNetwork network,
                                              @Nullable RoadBlobCache blobCache,
                                              PathPoint start, PathPoint end) {
        List<RouteSegment> segments = plan(network, blobCache, start, end);
        if (segments.isEmpty()) return segments;

        for (RouteSegment seg : segments) {
            if (seg.onRoad()) continue;
            int dy = Math.abs((int) seg.toY() - (int) seg.fromY());
            if (dy > NPC_MAX_Y_STEP) {
                Log.info(TAG, "NPC: rejected — off-road dy=%d > %d at (%.0f,%.0f,%.0f)→(%.0f,%.0f,%.0f)",
                        dy, NPC_MAX_Y_STEP,
                        seg.fromX(), seg.fromY(), seg.fromZ(),
                        seg.toX(), seg.toY(), seg.toZ());
                return List.of();
            }
        }
        return segments;
    }

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
    public static List<RouteSegment> plan(@Nullable RoadNetwork network,
                                           @Nullable RoadBlobCache blobCache,
                                           PathPoint start, PathPoint end) {
        // ── 0. Direct distance baseline ──
        int directDist = start.manhattanXZTo(end);
        int directTicks = directDist * TICKS_PER_BLOCK_OFF_ROAD;

        boolean hasNetwork = network != null && !network.isEmpty();
        boolean hasBlobs = blobCache != null && !blobCache.isEmpty();

        if (!hasNetwork && !hasBlobs) {
            Log.info(TAG, "No road network and no blobs — direct fly %d blocks", directDist);
            return List.of();
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
            return List.of();
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
            return List.of();
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
            return List.of();
        }

        DijkstraResult dij = dijkstra(graph, startKey, endKey);

        if (dij.path == null || dij.path.isEmpty()) {
            Log.warn(TAG, "  No road path found → direct fly");
            return List.of();
        }

        // ── 4. Build segments using graph edge onRoad metadata ──
        List<RouteSegment> rawSegments = new ArrayList<>();

        // 4a. Start -> first graph node (always off-road)
        PathPoint firstRoad = dij.path.get(0);
        if (start.manhattanXZTo(firstRoad) > 0 || start.y() != firstRoad.y()) {
            rawSegments.add(new RouteSegment(start.x(), start.y(), start.z(),
                    firstRoad.x(), firstRoad.y(), firstRoad.z(), false));
        }

        // 4b. Path internally: look up each edge in the graph for its onRoad flag
        for (int i = 0; i < dij.path.size() - 1; i++) {
            PathPoint a = dij.path.get(i);
            PathPoint b = dij.path.get(i + 1);
            NodeKey aKey = new NodeKey(a.x(), a.y(), a.z());
            NodeKey bKey = new NodeKey(b.x(), b.y(), b.z());

            // Query the graph: is the a→b edge on-road?
            boolean onRoad = graph.isOnRoad(aKey, bKey);

            rawSegments.add(new RouteSegment(a.x(), a.y(), a.z(),
                    b.x(), b.y(), b.z(), onRoad));
        }

        // 4c. Last graph node -> End (always off-road)
        PathPoint lastRoad = dij.path.get(dij.path.size() - 1);
        if (end.manhattanXZTo(lastRoad) > 0 || end.y() != lastRoad.y()) {
            rawSegments.add(new RouteSegment(lastRoad.x(), lastRoad.y(), lastRoad.z(),
                    end.x(), end.y(), end.z(), false));
        }

        // ── [RoadPlan] Detailed point-by-point log ──
        logRoadPlan(start, end, dij.path, rawSegments);

        // 4d. Simplify colinear segments
        List<RouteSegment> segments = simplifySegments(rawSegments);

        // ── 5. Summary ──
        int totalTicks = 0;
        int offRoadBlocks = 0, onRoadBlocks = 0;

        for (RouteSegment seg : segments) {
            int dist = (int) (Math.abs(seg.toX() - seg.fromX()) + Math.abs(seg.toZ() - seg.fromZ()));
            if (seg.onRoad()) {
                onRoadBlocks += dist;
                totalTicks += dist * TICKS_PER_BLOCK_ON_ROAD;
            } else {
                offRoadBlocks += dist;
                totalTicks += dist * TICKS_PER_BLOCK_OFF_ROAD;
            }
        }

        int saved = directTicks - totalTicks;
        Log.info(TAG, "  Segments: %d (%d off-road, %d on-road)",
                segments.size(),
                (int) segments.stream().filter(s -> !s.onRoad()).count(),
                (int) segments.stream().filter(RouteSegment::onRoad).count());
        Log.info(TAG, "  Time: %d ticks (direct would be %d ticks, saved %.0f%%)",
                totalTicks, directTicks,
                directTicks > 0 ? (double) saved / directTicks * 100 : 0);
        Log.info(TAG, "═════ Route planned ✓");

        return segments;
    }

    // ── [RoadPlan] logging ────────────────────────────────────────

    private static void logRoadPlan(PathPoint start, PathPoint end,
                                     List<PathPoint> dijkstraPath,
                                     List<RouteSegment> rawSegments) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[RoadPlan] %d raw segments | Start= %s → End= %s",
                rawSegments.size(), start, end));

        for (int i = 0; i < rawSegments.size(); i++) {
            RouteSegment seg = rawSegments.get(i);
            int xzDist = (int) (Math.abs(seg.toX() - seg.fromX())
                    + Math.abs(seg.toZ() - seg.fromZ()));
            int dy = Math.abs((int) (seg.toY() - seg.fromY()));
            String type = seg.onRoad() ? "ROAD" : "OFF_ROAD";
            int speed = seg.onRoad() ? TICKS_PER_BLOCK_ON_ROAD : TICKS_PER_BLOCK_OFF_ROAD;

            sb.append(String.format("\n[RoadPlan]   #%d %s xz=%d dy=%d speed=%dt/b (%d ticks)  (%.0f,%.0f,%.0f)→(%.0f,%.0f,%.0f)",
                    i, type, xzDist, dy, speed, xzDist * speed,
                    seg.fromX(), seg.fromY(), seg.fromZ(),
                    seg.toX(), seg.toY(), seg.toZ()));
        }

        // Show full Dijkstra path as a compact coordinate trail
        sb.append("\n[RoadPlan]   Dijkstra path (").append(dijkstraPath.size()).append(" nodes):");
        for (int i = 0; i < dijkstraPath.size(); i++) {
            PathPoint p = dijkstraPath.get(i);
            if (i % 4 == 0) sb.append("\n[RoadPlan]     ");
            sb.append(String.format("(%d,%d,%d)", p.x(), p.y(), p.z()));
            if (i < dijkstraPath.size() - 1) sb.append(" → ");
        }

        Log.info(TAG, sb.toString());
    }

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
                List<PathPoint> pts = edge.getPath();
                if (pts.isEmpty()) continue;

                for (int i = 0; i < pts.size(); i++) {
                    PathPoint p = pts.get(i);
                    NodeKey key = new NodeKey(p.x(), p.y(), p.z());
                    allPoints.add(key);

                    if (i == 0 || i == pts.size() - 1) {
                        edgeEndpoints.add(key);
                    }

                    if (i < pts.size() - 1) {
                        PathPoint next = pts.get(i + 1);
                        NodeKey nextKey = new NodeKey(next.x(), next.y(), next.z());
                        int distXZ = p.manhattanXZTo(next);
                        int weight = Math.max(1, distXZ) * TICKS_PER_BLOCK_ON_ROAD;
                        graph.addEdge(key, nextKey, weight, true /* onRoad */);
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
                    graph.addEdge(bn, centroid, weight, true /* onRoad */);
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
                    graph.addEdge(ep, pt, weight, false /* offRoad */);
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

    private static List<RouteSegment> simplifySegments(List<RouteSegment> raw) {
        if (raw.isEmpty()) return raw;

        List<RouteSegment> result = new ArrayList<>();
        RouteSegment current = raw.get(0);

        for (int i = 1; i < raw.size(); i++) {
            RouteSegment next = raw.get(i);

            if (current.onRoad() == next.onRoad() &&
                    current.toX() == next.fromX() &&
                    current.toY() == next.fromY() &&
                    current.toZ() == next.fromZ()) {

                double dx1 = current.toX() - current.fromX();
                double dy1 = current.toY() - current.fromY();
                double dz1 = current.toZ() - current.fromZ();

                double dx2 = next.toX() - next.fromX();
                double dy2 = next.toY() - next.fromY();
                double dz2 = next.toZ() - next.fromZ();

                if (dx1 * dy2 == dx2 * dy1 && dx1 * dz2 == dx2 * dz1 && dy1 * dz2 == dy2 * dz1) {
                    if (Integer.signum((int) dx1) == Integer.signum((int) dx2) &&
                            Integer.signum((int) dy1) == Integer.signum((int) dy2) &&
                            Integer.signum((int) dz1) == Integer.signum((int) dz2)) {
                        current = new RouteSegment(
                                current.fromX(), current.fromY(), current.fromZ(),
                                next.toX(), next.toY(), next.toZ(),
                                current.onRoad()
                        );
                        continue;
                    }
                }
            }

            result.add(current);
            current = next;
        }
        result.add(current);
        return result;
    }

    // ── Internal types ────────────────────────────────────────────

    record NodeKey(int x, int y, int z) {}

    private record DistNode(NodeKey key, int dist) {}

    /** Per-edge metadata: travel weight (ticks) + whether it's on-road. */
    private record EdgeInfo(int weight, boolean onRoad) {}

    private static class Graph {
        final Map<NodeKey, Map<NodeKey, EdgeInfo>> nodes = new HashMap<>();

        void addEdge(NodeKey n1, NodeKey n2, int weight, boolean onRoad) {
            EdgeInfo info = new EdgeInfo(weight, onRoad);
            nodes.computeIfAbsent(n1, k -> new HashMap<>()).merge(n2, info,
                    (old, neu) -> old.weight <= neu.weight ? old : neu);
            nodes.computeIfAbsent(n2, k -> new HashMap<>()).merge(n1, info,
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
