package com.wsteam.wandscape.content.road.algorithm;
import com.wsteam.wandscape.foundation.log.Log;

import com.wsteam.wandscape.content.road.core.*;
import com.wsteam.wandscape.content.road.core.*;

import java.util.*;

/**
 * Plans item transport and entity walking routes using the colony road network.
 *
 * <p>Pure core calculation with zero Minecraft dependencies.
 * Supports:
 * <ul>
 *   <li>Single-edge traversal</li>
 *   <li>End-to-end connected roads</li>
 *   <li>T-junctions and cross-intersections (attaching to the middle of road edges)</li>
 *   <li>Multi-segment road hops with off-road gaps ("野路 - road - 野路 - road - 野路")</li>
 * </ul>
 *
 * <p>Fast, lightweight, and guaranteed non-blocking (O(V + E) topology-based Dijkstra with hard step caps).
 */
public final class RoadRouter {

    private static final String TAG = "RoadRouter";

    /** Default ticks per block for on-road cruising (fast). */
    public static final int DEFAULT_TICKS_ON_ROAD = 2;

    /** Default ticks per block for off-road flight. */
    public static final int DEFAULT_TICKS_OFF_ROAD = 4;

    /** Maximum distance (blocks) from start/end position to nearest road to consider snapping. */
    public static final double MAX_SNAP_DISTANCE = 48.0;

    /** Minimum direct distance (blocks) below which we just fly directly. */
    public static final double MIN_ROAD_BENEFIT_DISTANCE = 6.0;

    /** Maximum gap (blocks) between road edge endpoints/intersections to connect as an on-road junction. */
    public static final double MAX_JUNCTION_GAP = 4.0;

    /** Maximum off-road gap (blocks) between disconnected road segments to hop across ("野路"). */
    public static final double MAX_ROAD_HOP_GAP = 24.0;

    /** Max ratio of (road travel time / direct flight time) before rejecting detour in favor of direct. */
    public static final double MAX_DETOUR_FACTOR = 1.8;

    /** Hard cap on Dijkstra search steps to guarantee zero performance spikes. */
    private static final int MAX_SEARCH_STEPS = 500;

    private RoadRouter() {}

    /**
     * Plan a transport route with default speeds.
     */
    public static TransportRoute plan(RoadNetwork network, PathPoint start, PathPoint end) {
        return plan(network, start, end, DEFAULT_TICKS_ON_ROAD, DEFAULT_TICKS_OFF_ROAD);
    }

    /**
     * Plan a transport route from start to end using the colony road network if beneficial.
     *
     * @param network      colony road network (may be null/empty)
     * @param start        origin position
     * @param end          destination position
     * @param ticksOnRoad  speed rate on road (ticks per block)
     * @param ticksOffRoad speed rate off road (ticks per block)
     * @return planned route (falls back to direct line if no road or road is an excessive detour)
     */
    public static TransportRoute plan(RoadNetwork network, PathPoint start, PathPoint end,
                                      int ticksOnRoad, int ticksOffRoad) {
        if (start == null || end == null) {
            return new TransportRoute(List.of());
        }

        TransportRoute directRoute = TransportRoute.direct(start, end);
        if (network == null || network.isEmpty()) {
            return directRoute;
        }

        double directDist = Math.sqrt(
                Math.pow(start.x() - end.x(), 2)
                + Math.pow(start.y() - end.y(), 2)
                + Math.pow(start.z() - end.z(), 2)
        );
        if (directDist < MIN_ROAD_BENEFIT_DISTANCE) {
            return directRoute;
        }

        List<RoadEdge> activeEdges = new ArrayList<>();
        for (RoadEdge edge : network.getEdges().values()) {
            if (edge.getStatus() == RoadEdge.EdgeStatus.COMPLETE && edge.getSpline() != null
                    && edge.getSpline().getSegmentsCount() > 0) {
                activeEdges.add(edge);
            }
        }
        if (activeEdges.isEmpty()) {
            return directRoute;
        }

        List<EdgeAABB> aabbs = new ArrayList<>(activeEdges.size());
        for (RoadEdge edge : activeEdges) {
            aabbs.add(EdgeAABB.of(edge));
        }

        // Find candidate road projections for start and end
        List<RoadProjection> startProjs = findCandidateProjections(activeEdges, aabbs, start, MAX_SNAP_DISTANCE);
        List<RoadProjection> endProjs = findCandidateProjections(activeEdges, aabbs, end, MAX_SNAP_DISTANCE);

        if (startProjs.isEmpty() || endProjs.isEmpty()) {
            return directRoute;
        }

        // Multi-segment topology graph search
        List<SplineLeg> routeLegs = searchRoadPath(activeEdges, aabbs, startProjs, endProjs, start, end, ticksOnRoad, ticksOffRoad);
        if (routeLegs == null || routeLegs.isEmpty()) {
            return directRoute;
        }

        List<SplineLeg> simplified = simplifyLegs(routeLegs);
        TransportRoute candidate = new TransportRoute(simplified);

        // Detour check: if road route takes much longer than direct flight, prefer direct
        int directDuration = directRoute.totalDuration(ticksOnRoad, ticksOffRoad);
        int roadDuration = candidate.totalDuration(ticksOnRoad, ticksOffRoad);
        if (roadDuration > directDuration * MAX_DETOUR_FACTOR) {
            return directRoute;
        }

        return candidate;
    }

    // ── Topology Search with T-Junctions and Off-Road Hops ──

    private static List<SplineLeg> searchRoadPath(List<RoadEdge> edges,
                                                  List<EdgeAABB> aabbs,
                                                  List<RoadProjection> startProjs,
                                                  List<RoadProjection> endProjs,
                                                  PathPoint start,
                                                  PathPoint end,
                                                  int ticksOnRoad,
                                                  int ticksOffRoad) {
        Graph graph = new Graph();
        int nodeIdSeq = 0;

        int startNode = nodeIdSeq++;
        int endNode = nodeIdSeq++;
        SplineVec3 startPos = new SplineVec3(start.x() + 0.5, start.y() + 0.5, start.z() + 0.5);
        SplineVec3 endPos = new SplineVec3(end.x() + 0.5, end.y() + 0.5, end.z() + 0.5);
        graph.setNodePos(startNode, startPos);
        graph.setNodePos(endNode, endPos);

        // 1. Collect split parameters for every edge (endpoints, snap points, T-junction projections)
        Map<RoadEdge, TreeSet<Double>> edgeSplitMap = new HashMap<>();
        for (RoadEdge edge : edges) {
            TreeSet<Double> splits = new TreeSet<>();
            splits.add(0.0);
            splits.add((double) edge.getSpline().getSegmentsCount());
            edgeSplitMap.put(edge, splits);
        }

        for (RoadProjection sp : startProjs) {
            if (edgeSplitMap.containsKey(sp.edge)) {
                edgeSplitMap.get(sp.edge).add(sp.u);
            }
        }
        for (RoadProjection ep : endProjs) {
            if (edgeSplitMap.containsKey(ep.edge)) {
                edgeSplitMap.get(ep.edge).add(ep.u);
            }
        }

        // T-Junction & Cross-Intersection Discovery with AABB pre-rejection:
        for (int i = 0; i < edges.size(); i++) {
            RoadEdge eA = edges.get(i);
            EdgeAABB boxA = aabbs.get(i);

            for (int j = 0; j < edges.size(); j++) {
                if (i == j) continue;
                RoadEdge eB = edges.get(j);
                EdgeAABB boxB = aabbs.get(j);
                if (!boxA.intersectsWithMargin(boxB, MAX_JUNCTION_GAP)) {
                    continue; // 1ns AABB rejection
                }

                SplineVec3 p0 = eB.getSpline().evaluate(0.0).position();
                if (boxA.intersectsWithMargin(p0, MAX_JUNCTION_GAP)) {
                    EdgeProjection proj0 = projectOntoEdge(eA, p0);
                    if (proj0.dist <= MAX_JUNCTION_GAP) {
                        edgeSplitMap.get(eA).add(proj0.u);
                    }
                }

                SplineVec3 p1 = eB.getSpline().evaluate(eB.getSpline().getSegmentsCount()).position();
                if (boxA.intersectsWithMargin(p1, MAX_JUNCTION_GAP)) {
                    EdgeProjection proj1 = projectOntoEdge(eA, p1);
                    if (proj1.dist <= MAX_JUNCTION_GAP) {
                        edgeSplitMap.get(eA).add(proj1.u);
                    }
                }
            }
        }

        // 2. Allocate graph nodes for all split parameters on each edge
        Map<EdgeParamKey, Integer> paramToNodeId = new HashMap<>();
        List<Integer> allRoadNodes = new ArrayList<>();

        for (RoadEdge edge : edges) {
            TreeSet<Double> splits = edgeSplitMap.get(edge);
            List<Double> sortedU = new ArrayList<>(splits);

            // Deduplicate parameters that are essentially identical
            List<Double> cleanU = new ArrayList<>();
            for (double u : sortedU) {
                if (cleanU.isEmpty() || Math.abs(u - cleanU.get(cleanU.size() - 1)) > 0.001) {
                    cleanU.add(u);
                }
            }

            List<Integer> edgeNodeIds = new ArrayList<>(cleanU.size());
            for (double u : cleanU) {
                int nId = nodeIdSeq++;
                SplineVec3 pos = edge.getSpline().evaluate(u).position();
                graph.setNodePos(nId, pos);
                paramToNodeId.put(new EdgeParamKey(edge, u), nId);
                edgeNodeIds.add(nId);
                allRoadNodes.add(nId);
            }

            // Connect consecutive sub-segments along this edge
            for (int k = 0; k < cleanU.size() - 1; k++) {
                double uA = cleanU.get(k);
                double uB = cleanU.get(k + 1);
                int nA = edgeNodeIds.get(k);
                int nB = edgeNodeIds.get(k + 1);

                SplineLeg fwdLeg = new SplineLeg(edge.getSpline(), uA, uB, false);
                SplineLeg revLeg = new SplineLeg(edge.getSpline(), uB, uA, false);

                double len = fwdLeg.getApproxLength();
                int weight = Math.max(1, (int) Math.round(len * ticksOnRoad));

                graph.addEdge(nA, nB, weight, fwdLeg);
                graph.addEdge(nB, nA, weight, revLeg);
            }
        }

        // 3. Connect inter-edge nodes with Sweep-Line X spatial index (O(N log N))
        allRoadNodes.sort(Comparator.comparingDouble(id -> graph.getNodePos(id).x()));

        for (int i = 0; i < allRoadNodes.size(); i++) {
            int nA = allRoadNodes.get(i);
            SplineVec3 posA = graph.getNodePos(nA);

            for (int j = i + 1; j < allRoadNodes.size(); j++) {
                int nB = allRoadNodes.get(j);
                SplineVec3 posB = graph.getNodePos(nB);

                double dx = posB.x() - posA.x();
                if (dx > MAX_ROAD_HOP_GAP) {
                    break; // Sorted along X — all subsequent nodes are even further, break immediately!
                }

                double dy = Math.abs(posB.y() - posA.y());
                if (dy > MAX_ROAD_HOP_GAP) continue;
                double dz = Math.abs(posB.z() - posA.z());
                if (dz > MAX_ROAD_HOP_GAP) continue;

                double gapSqr = dx * dx + dy * dy + dz * dz;
                if (gapSqr <= MAX_JUNCTION_GAP * MAX_JUNCTION_GAP) {
                    double gap = Math.sqrt(gapSqr);
                    int gapWeight = Math.max(1, (int) Math.round(gap * ticksOnRoad));
                    SplineLeg junctionLeg = createLinearSplineLeg(posA, posB, false);
                    SplineLeg revJunctionLeg = createLinearSplineLeg(posB, posA, false);
                    graph.addEdge(nA, nB, gapWeight, junctionLeg);
                    graph.addEdge(nB, nA, gapWeight, revJunctionLeg);
                } else if (gapSqr <= MAX_ROAD_HOP_GAP * MAX_ROAD_HOP_GAP) {
                    double gap = Math.sqrt(gapSqr);
                    int hopWeight = Math.max(1, (int) Math.round(gap * ticksOffRoad));
                    SplineLeg hopLeg = createLinearSplineLeg(posA, posB, true);
                    SplineLeg revHopLeg = createLinearSplineLeg(posB, posA, true);
                    graph.addEdge(nA, nB, hopWeight, hopLeg);
                    graph.addEdge(nB, nA, hopWeight, revHopLeg);
                }
            }
        }

        // 4. Connect startNode to candidate snap nodes
        for (RoadProjection sp : startProjs) {
            Integer targetNode = findClosestParamNode(paramToNodeId, sp);
            if (targetNode != null) {
                SplineVec3 posSnap = graph.getNodePos(targetNode);
                double d = startPos.subtract(posSnap).length();
                int weight = Math.max(1, (int) Math.round(d * ticksOffRoad));
                SplineLeg leg = createLinearSplineLeg(startPos, posSnap, true);
                graph.addEdge(startNode, targetNode, weight, leg);
            }
        }

        // 5. Connect candidate snap nodes to endNode
        for (RoadProjection ep : endProjs) {
            Integer targetNode = findClosestParamNode(paramToNodeId, ep);
            if (targetNode != null) {
                SplineVec3 posSnap = graph.getNodePos(targetNode);
                double d = posSnap.subtract(endPos).length();
                int weight = Math.max(1, (int) Math.round(d * ticksOffRoad));
                SplineLeg leg = createLinearSplineLeg(posSnap, endPos, true);
                graph.addEdge(targetNode, endNode, weight, leg);
            }
        }

        // 6. Direct line flight fallback in graph
        double directDist = startPos.subtract(endPos).length();
        int directWeight = Math.max(1, (int) Math.round(directDist * ticksOffRoad));
        SplineLeg directLeg = createLinearSplineLeg(startPos, endPos, true);
        graph.addEdge(startNode, endNode, directWeight, directLeg);

        // 7. Run Dijkstra
        return dijkstra(graph, startNode, endNode);
    }

    private record EdgeParamKey(RoadEdge edge, double u) {}

    private static Integer findClosestParamNode(Map<EdgeParamKey, Integer> paramToNodeId, RoadProjection proj) {
        double bestDiff = Double.MAX_VALUE;
        Integer bestNode = null;
        for (var entry : paramToNodeId.entrySet()) {
            EdgeParamKey key = entry.getKey();
            if (key.edge() == proj.edge()) {
                double diff = Math.abs(key.u() - proj.u());
                if (diff < bestDiff) {
                    bestDiff = diff;
                    bestNode = entry.getValue();
                }
            }
        }
        return bestNode;
    }

    private static List<SplineLeg> dijkstra(Graph graph, int startNode, int targetNode) {
        Map<Integer, Integer> dist = new HashMap<>();
        Map<Integer, Integer> prevNode = new HashMap<>();
        Map<Integer, SplineLeg> prevEdge = new HashMap<>();
        PriorityQueue<DistNode> pq = new PriorityQueue<>(Comparator.comparingInt(d -> d.dist));

        dist.put(startNode, 0);
        pq.add(new DistNode(startNode, 0));
        int steps = 0;

        while (!pq.isEmpty() && steps++ < MAX_SEARCH_STEPS) {
            DistNode cur = pq.poll();
            if (cur.dist > dist.getOrDefault(cur.nodeId, Integer.MAX_VALUE)) continue;
            if (cur.nodeId == targetNode) break;

            for (Edge edge : graph.getEdges(cur.nodeId)) {
                int newDist = cur.dist + edge.weight;
                if (newDist < dist.getOrDefault(edge.target, Integer.MAX_VALUE)) {
                    dist.put(edge.target, newDist);
                    prevNode.put(edge.target, cur.nodeId);
                    prevEdge.put(edge.target, edge.leg);
                    pq.add(new DistNode(edge.target, newDist));
                }
            }
        }

        if (!prevNode.containsKey(targetNode)) {
            return Collections.emptyList();
        }

        List<SplineLeg> path = new ArrayList<>();
        int curr = targetNode;
        while (curr != startNode) {
            SplineLeg leg = prevEdge.get(curr);
            if (leg != null) {
                path.add(leg);
            }
            Integer prev = prevNode.get(curr);
            if (prev == null) break;
            curr = prev;
        }

        Collections.reverse(path);
        return path;
    }

    // ── Road Projection ──

    private record RoadProjection(RoadEdge edge, double u, SplineVec3 pos, double dist) {}

    private record EdgeProjection(double u, SplineVec3 pos, double dist) {}

    private static EdgeProjection projectOntoEdge(RoadEdge edge, SplineVec3 targetPos) {
        SplineModel spline = edge.getSpline();
        if (spline == null || spline.getPoints().isEmpty()) {
            return new EdgeProjection(0.0, SplineVec3.ZERO, Double.MAX_VALUE);
        }

        int segCount = spline.getSegmentsCount();
        int samples = Math.max(10, segCount * 10);
        double du = (double) segCount / samples;

        double bestDist = Double.MAX_VALUE;
        double bestU = 0.0;
        SplineVec3 bestPos = SplineVec3.ZERO;

        for (int i = 0; i <= samples; i++) {
            double u = i * du;
            SplineVec3 pos = spline.evaluate(u).position();
            double d = targetPos.subtract(pos).length();
            if (d < bestDist) {
                bestDist = d;
                bestU = u;
                bestPos = pos;
            }
        }

        return new EdgeProjection(bestU, bestPos, bestDist);
    }

    private record EdgeAABB(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        static EdgeAABB of(RoadEdge edge) {
            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
            for (SplinePoint p : edge.getSpline().getPoints()) {
                SplineVec3 v = p.getAnchor();
                if (v.x() < minX) minX = v.x();
                if (v.x() > maxX) maxX = v.x();
                if (v.y() < minY) minY = v.y();
                if (v.y() > maxY) maxY = v.y();
                if (v.z() < minZ) minZ = v.z();
                if (v.z() > maxZ) maxZ = v.z();
            }
            return new EdgeAABB(minX, minY, minZ, maxX, maxY, maxZ);
        }

        boolean intersectsWithMargin(SplineVec3 p, double margin) {
            return p.x() >= minX - margin && p.x() <= maxX + margin
                    && p.y() >= minY - margin && p.y() <= maxY + margin
                    && p.z() >= minZ - margin && p.z() <= maxZ + margin;
        }

        boolean intersectsWithMargin(EdgeAABB other, double margin) {
            return !(other.maxX < minX - margin || other.minX > maxX + margin
                    || other.maxY < minY - margin || other.minY > maxY + margin
                    || other.maxZ < minZ - margin || other.minZ > maxZ + margin);
        }
    }

    private static List<RoadProjection> findCandidateProjections(List<RoadEdge> edges, List<EdgeAABB> aabbs,
                                                                 PathPoint target, double maxDist) {
        List<RoadProjection> list = new ArrayList<>();
        SplineVec3 targetPos = new SplineVec3(target.x() + 0.5, target.y() + 0.5, target.z() + 0.5);

        for (int i = 0; i < edges.size(); i++) {
            RoadEdge edge = edges.get(i);
            EdgeAABB box = aabbs.get(i);
            if (!box.intersectsWithMargin(targetPos, maxDist)) {
                continue; // 1ns AABB rejection
            }

            EdgeProjection proj = projectOntoEdge(edge, targetPos);
            if (proj.dist <= maxDist) {
                list.add(new RoadProjection(edge, proj.u, proj.pos, proj.dist));
            }
        }

        list.sort(Comparator.comparingDouble(p -> p.dist));
        // Keep at most top 3 closest edges to keep search graph fast
        if (list.size() > 3) {
            return list.subList(0, 3);
        }
        return list;
    }

    // ── Leg Construction & Simplification ──

    private static SplineLeg createLinearSplineLeg(SplineVec3 pA, SplineVec3 pB, boolean offRoad) {
        SplineModel gap = new SplineModel();
        gap.getPoints().add(new SplinePoint(pA, pA, pA, true));
        gap.getPoints().add(new SplinePoint(pB, pB, pB, true));
        return new SplineLeg(gap, 0.0, 1.0, offRoad);
    }

    private static List<SplineLeg> simplifyLegs(List<SplineLeg> raw) {
        if (raw == null || raw.size() <= 1) return raw;

        List<SplineLeg> result = new ArrayList<>();
        SplineLeg current = raw.get(0);

        for (int i = 1; i < raw.size(); i++) {
            SplineLeg next = raw.get(i);
            // Skip zero-length micro legs
            if (next.getApproxLength() < 0.01) {
                continue;
            }
            if (current.getApproxLength() < 0.01) {
                current = next;
                continue;
            }

            // Merge if they share the same spline instance and end matches start
            if (current.spline() == next.spline()
                    && current.offRoad() == next.offRoad()
                    && Math.abs(current.uEnd() - next.uStart()) < 0.001) {
                current = new SplineLeg(current.spline(), current.uStart(), next.uEnd(), current.offRoad());
            } else {
                result.add(current);
                current = next;
            }
        }
        result.add(current);
        return result;
    }

    // ── Internal Graph Helper Types ──

    private record DistNode(int nodeId, int dist) {}

    private record Edge(int target, int weight, SplineLeg leg) {}

    private static class Graph {
        private final Map<Integer, List<Edge>> adj = new HashMap<>();
        private final Map<Integer, SplineVec3> nodePositions = new HashMap<>();

        void setNodePos(int nodeId, SplineVec3 pos) {
            nodePositions.put(nodeId, pos);
        }

        SplineVec3 getNodePos(int nodeId) {
            return nodePositions.getOrDefault(nodeId, SplineVec3.ZERO);
        }

        void addEdge(int u, int v, int weight, SplineLeg leg) {
            adj.computeIfAbsent(u, k -> new ArrayList<>()).add(new Edge(v, weight, leg));
        }

        List<Edge> getEdges(int nodeId) {
            return adj.getOrDefault(nodeId, Collections.emptyList());
        }
    }
}

