package com.wsteam.wandscape.road.algorithm;

import com.wsteam.wandscape.road.core.PathPoint;
import com.wsteam.wandscape.road.core.RoadEdge;
import com.wsteam.wandscape.road.core.RoadNetwork;
import com.wsteam.wandscape.road.core.SplineLeg;
import com.wsteam.wandscape.road.core.SplineModel;
import com.wsteam.wandscape.road.core.SplinePoint;
import com.wsteam.wandscape.road.core.SplineVec3;
import com.wsteam.wandscape.road.core.TransportRoute;
import com.wsteam.wandscape.shared.log.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Plans item transport routes using the colony road network.
 *
 * <p>Pure core calculation with zero Minecraft dependencies.
 * Fast, lightweight, and guaranteed non-blocking (O(V + E) topology-based A* with hard step caps).
 */
public final class RoadRouter {

    private static final String TAG = "RoadRouter";

    /** Default ticks per block for on-road cruising (fast). */
    public static final int DEFAULT_TICKS_ON_ROAD = 2;

    /** Default ticks per block for off-road flight. */
    public static final int DEFAULT_TICKS_OFF_ROAD = 4;

    /** Maximum distance (blocks) from start/end position to nearest road to consider using road. */
    public static final double MAX_SNAP_DISTANCE = 32.0;

    /** Minimum direct distance (blocks) below which we just fly directly. */
    public static final double MIN_ROAD_BENEFIT_DISTANCE = 6.0;

    /** Maximum gap (blocks) between road edge endpoints to connect as a junction. */
    public static final double MAX_JUNCTION_GAP = 4.0;

    /** Max ratio of (road travel time / direct flight time) before rejecting detour in favor of direct. */
    public static final double MAX_DETOUR_FACTOR = 1.8;

    /** Hard cap on A* search steps to guarantee zero performance spikes. */
    private static final int MAX_SEARCH_STEPS = 300;

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
     * @return planned route (falls back to direct line if no road or road is a detour)
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

        // Find nearest road projection for start and end
        RoadProjection snapStart = findNearestProjection(activeEdges, start);
        RoadProjection snapEnd = findNearestProjection(activeEdges, end);

        if (snapStart == null || snapEnd == null
                || snapStart.dist > MAX_SNAP_DISTANCE || snapEnd.dist > MAX_SNAP_DISTANCE) {
            return directRoute;
        }

        List<SplineLeg> legs;

        if (snapStart.edge == snapEnd.edge) {
            // Both points snap to the same road edge
            legs = new ArrayList<>();
            if (snapStart.dist > 0.5) {
                legs.add(createLinearLeg(start, snapStart.pos, true));
            }
            legs.add(new SplineLeg(snapStart.edge.getSpline(), snapStart.u, snapEnd.u, false));
            if (snapEnd.dist > 0.5) {
                legs.add(createLinearLeg(snapEnd.pos, end, true));
            }
        } else {
            // Multi-edge topology search
            List<SplineLeg> onRoadLegs = searchRoadPath(activeEdges, snapStart, snapEnd, ticksOnRoad);
            if (onRoadLegs == null || onRoadLegs.isEmpty()) {
                return directRoute;
            }

            legs = new ArrayList<>();
            if (snapStart.dist > 0.5) {
                legs.add(createLinearLeg(start, snapStart.pos, true));
            }
            legs.addAll(onRoadLegs);
            if (snapEnd.dist > 0.5) {
                legs.add(createLinearLeg(snapEnd.pos, end, true));
            }
        }

        List<SplineLeg> simplified = simplifyLegs(legs);
        TransportRoute candidate = new TransportRoute(simplified);

        // Detour check: if road route takes much longer than direct flight, prefer direct
        int directDuration = directRoute.totalDuration(ticksOnRoad, ticksOffRoad);
        int roadDuration = candidate.totalDuration(ticksOnRoad, ticksOffRoad);
        if (roadDuration > directDuration * MAX_DETOUR_FACTOR) {
            return directRoute;
        }

        return candidate;
    }

    // ── Topology Search ──

    private static List<SplineLeg> searchRoadPath(List<RoadEdge> edges,
                                                  RoadProjection startProj,
                                                  RoadProjection endProj,
                                                  int ticksOnRoad) {
        // Build graph of endpoints + snap points
        Graph graph = new Graph();
        int nodeIdSeq = 0;

        int startNode = nodeIdSeq++;
        int endNode = nodeIdSeq++;
        graph.setNodePos(startNode, startProj.pos);
        graph.setNodePos(endNode, endProj.pos);

        Map<RoadEdge, int[]> edgeEndpoints = new HashMap<>();

        for (RoadEdge edge : edges) {
            int segCount = edge.getSpline().getSegmentsCount();
            SplineVec3 p0 = edge.getSpline().evaluate(0.0).position();
            SplineVec3 p1 = edge.getSpline().evaluate(segCount).position();

            int n0 = nodeIdSeq++;
            int n1 = nodeIdSeq++;
            graph.setNodePos(n0, p0);
            graph.setNodePos(n1, p1);
            edgeEndpoints.put(edge, new int[]{n0, n1});

            // Edge internal traversal
            double length = new SplineLeg(edge.getSpline(), 0.0, segCount, false).getApproxLength();
            int weight = Math.max(1, (int) Math.round(length * ticksOnRoad));
            graph.addEdge(n0, n1, weight, new SplineLeg(edge.getSpline(), 0.0, segCount, false));
            graph.addEdge(n1, n0, weight, new SplineLeg(edge.getSpline(), segCount, 0.0, false));
        }

        // Connect road junctions (endpoints close to each other)
        for (int i = 0; i < edges.size(); i++) {
            RoadEdge e1 = edges.get(i);
            int[] ep1 = edgeEndpoints.get(e1);
            for (int j = i + 1; j < edges.size(); j++) {
                RoadEdge e2 = edges.get(j);
                int[] ep2 = edgeEndpoints.get(e2);

                for (int nA : ep1) {
                    for (int nB : ep2) {
                        SplineVec3 posA = graph.getNodePos(nA);
                        SplineVec3 posB = graph.getNodePos(nB);
                        double gap = posA.subtract(posB).length();
                        if (gap <= MAX_JUNCTION_GAP) {
                            int gapWeight = Math.max(1, (int) Math.round(gap * ticksOnRoad));
                            SplineLeg junctionLeg = createLinearSplineLeg(posA, posB, false);
                            SplineLeg revJunctionLeg = createLinearSplineLeg(posB, posA, false);
                            graph.addEdge(nA, nB, gapWeight, junctionLeg);
                            graph.addEdge(nB, nA, gapWeight, revJunctionLeg);
                        }
                    }
                }
            }
        }

        // Connect snapStart to startEdge endpoints
        int[] startEp = edgeEndpoints.get(startProj.edge);
        if (startEp != null) {
            double lenTo0 = new SplineLeg(startProj.edge.getSpline(), startProj.u, 0.0, false).getApproxLength();
            int w0 = Math.max(1, (int) Math.round(lenTo0 * ticksOnRoad));
            graph.addEdge(startNode, startEp[0], w0, new SplineLeg(startProj.edge.getSpline(), startProj.u, 0.0, false));

            int maxU = startProj.edge.getSpline().getSegmentsCount();
            double lenTo1 = new SplineLeg(startProj.edge.getSpline(), startProj.u, maxU, false).getApproxLength();
            int w1 = Math.max(1, (int) Math.round(lenTo1 * ticksOnRoad));
            graph.addEdge(startNode, startEp[1], w1, new SplineLeg(startProj.edge.getSpline(), startProj.u, maxU, false));
        }

        // Connect endEdge endpoints to snapEnd
        int[] endEp = edgeEndpoints.get(endProj.edge);
        if (endEp != null) {
            double lenFrom0 = new SplineLeg(endProj.edge.getSpline(), 0.0, endProj.u, false).getApproxLength();
            int w0 = Math.max(1, (int) Math.round(lenFrom0 * ticksOnRoad));
            graph.addEdge(endEp[0], endNode, w0, new SplineLeg(endProj.edge.getSpline(), 0.0, endProj.u, false));

            int maxU = endProj.edge.getSpline().getSegmentsCount();
            double lenFrom1 = new SplineLeg(endProj.edge.getSpline(), maxU, endProj.u, false).getApproxLength();
            int w1 = Math.max(1, (int) Math.round(lenFrom1 * ticksOnRoad));
            graph.addEdge(endEp[1], endNode, w1, new SplineLeg(endProj.edge.getSpline(), maxU, endProj.u, false));
        }

        // A* / Dijkstra search
        return dijkstra(graph, startNode, endNode);
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

    private static RoadProjection findNearestProjection(List<RoadEdge> edges, PathPoint target) {
        RoadProjection best = null;
        double bestDist = Double.MAX_VALUE;

        SplineVec3 targetPos = new SplineVec3(target.x() + 0.5, target.y() + 0.5, target.z() + 0.5);

        for (RoadEdge edge : edges) {
            SplineModel spline = edge.getSpline();
            if (spline == null || spline.getPoints().isEmpty()) continue;

            int segCount = spline.getSegmentsCount();
            int samples = Math.max(10, segCount * 10);
            double du = (double) segCount / samples;

            for (int i = 0; i <= samples; i++) {
                double u = i * du;
                SplineVec3 pos = spline.evaluate(u).position();
                double d = targetPos.subtract(pos).length();
                if (d < bestDist) {
                    bestDist = d;
                    best = new RoadProjection(edge, u, pos, d);
                }
            }
        }

        return best;
    }

    // ── Leg Construction & Simplification ──

    private static SplineLeg createLinearLeg(PathPoint from, SplineVec3 to, boolean offRoad) {
        SplineVec3 pA = new SplineVec3(from.x() + 0.5, from.y() + 0.5, from.z() + 0.5);
        return createLinearSplineLeg(pA, to, offRoad);
    }

    private static SplineLeg createLinearLeg(SplineVec3 from, PathPoint to, boolean offRoad) {
        SplineVec3 pB = new SplineVec3(to.x() + 0.5, to.y() + 0.5, to.z() + 0.5);
        return createLinearSplineLeg(from, pB, offRoad);
    }

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
