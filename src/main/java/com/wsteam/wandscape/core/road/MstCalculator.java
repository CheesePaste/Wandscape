package com.wsteam.wandscape.core.road;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.ToIntBiFunction;

/**
 * Computes a Minimum Spanning Tree using Prim's algorithm.
 * The MST guarantees all buildings are connected with minimum total path length.
 */
public final class MstCalculator {

    private MstCalculator() {}

    /**
     * Compute the MST edges for a set of 2D points.
     *
     * @param points     the points to connect
     * @param distanceFn function computing distance between two points (e.g. Manhattan)
     * @return list of MST edges (empty if fewer than 2 points)
     */
    public static List<MstEdge> prim(List<XZPoint> points,
                                      ToIntBiFunction<XZPoint, XZPoint> distanceFn) {
        int n = points.size();
        if (n < 2) return List.of();

        // Prim's algorithm
        boolean[] inTree = new boolean[n];
        int[] bestDist = new int[n];
        int[] bestFrom = new int[n];

        for (int i = 0; i < n; i++) {
            bestDist[i] = Integer.MAX_VALUE;
            bestFrom[i] = -1;
        }

        // Start from vertex 0
        bestDist[0] = 0;
        List<MstEdge> result = new ArrayList<>();

        // Min-heap of (distance, vertex) pairs
        PriorityQueue<int[]> pq = new PriorityQueue<>(
                Comparator.comparingInt(a -> a[0]));
        pq.offer(new int[]{0, 0}); // {distance, vertex}

        int added = 0;
        while (!pq.isEmpty() && added < n) {
            int[] cur = pq.poll();
            int dist = cur[0];
            int v = cur[1];

            if (inTree[v]) continue;

            inTree[v] = true;
            added++;

            // Record edge (except for the start vertex)
            if (bestFrom[v] != -1) {
                result.add(new MstEdge(bestFrom[v], v, dist));
            }

            // Relax edges from v
            for (int u = 0; u < n; u++) {
                if (inTree[u]) continue;
                int d = distanceFn.applyAsInt(points.get(v), points.get(u));
                if (d < bestDist[u]) {
                    bestDist[u] = d;
                    bestFrom[u] = v;
                    pq.offer(new int[]{d, u});
                }
            }
        }

        return result;
    }
}
