package com.wsteam.wandscape.road.algorithm;

import com.wsteam.wandscape.road.core.PathPoint;
import com.wsteam.wandscape.road.core.RoadEdge;
import com.wsteam.wandscape.road.core.RoadNetwork;
import com.wsteam.wandscape.road.core.SplineLeg;
import com.wsteam.wandscape.road.core.SplineModel;
import com.wsteam.wandscape.road.core.SplineVec3;
import com.wsteam.wandscape.road.core.TransportRoute;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Standalone & JUnit 5 high-load stress benchmark for the {@link RoadRouter} routing algorithm.
 *
 * <p>Can be executed directly via:
 * <ul>
 *   <li>{@code ./gradlew test --tests "com.wsteam.wandscape.road.algorithm.RoadRouterStressBenchmark"}</li>
 *   <li>Standard Java {@code main(String[] args)} entry point for standalone JVM profiling.</li>
 * </ul>
 */
public class RoadRouterStressBenchmark {

    public static void main(String[] args) {
        System.out.println("==========================================================================");
        System.out.println("          WANDSCAPE ROAD ROUTER STANDALONE STRESS BENCHMARK               ");
        System.out.println("==========================================================================");

        RoadRouterStressBenchmark bench = new RoadRouterStressBenchmark();
        bench.runMetropolisGridBenchmark();
        bench.runSuburbanTJunctionBenchmark();
        bench.runDisjointRuralHopsBenchmark();

        System.out.println("\nAll benchmark suites completed successfully!");
    }

    @Test
    @DisplayName("Metropolis Dense Grid Stress Benchmark (100 Edges, 10000 Queries)")
    void testMetropolisGrid() {
        runMetropolisGridBenchmark();
    }

    @Test
    @DisplayName("Suburban T-Junctions Stress Benchmark (100 Edges, 10000 Queries)")
    void testSuburbanTJunctions() {
        runSuburbanTJunctionBenchmark();
    }

    @Test
    @DisplayName("Disjoint Rural Multi-Hop Stress Benchmark (10 Clusters, 10000 Queries)")
    void testDisjointRuralHops() {
        runDisjointRuralHopsBenchmark();
    }

    // ── Benchmark Suite 1: Dense Metropolis Grid ──

    public void runMetropolisGridBenchmark() {
        System.out.println("\n[Suite 1] Dense Metropolis Grid (100 Road Edges, 10,000 Queries)");
        RoadNetwork network = buildMetropolisGrid(10, 10, 40.0);
        executeBenchmark("Metropolis Grid (10x10)", network, 400.0, 400.0, 2000, 10000);
    }

    // ── Benchmark Suite 2: Suburban T-Junction Network ──

    public void runSuburbanTJunctionBenchmark() {
        System.out.println("\n[Suite 2] Suburban Main Avenues + T-Junction Branches (100 Edges, 10,000 Queries)");
        RoadNetwork network = buildSuburbanNetwork(10, 9);
        executeBenchmark("Suburban T-Junctions", network, 500.0, 300.0, 2000, 10000);
    }

    // ── Benchmark Suite 3: Disjoint Rural Clusters with Off-Road Hops ──

    public void runDisjointRuralHopsBenchmark() {
        System.out.println("\n[Suite 3] Disjoint Rural Road Clusters (野路-Road-野路-Road, 10,000 Queries)");
        RoadNetwork network = buildDisjointRuralNetwork(8, 20.0);
        executeBenchmark("Disjoint Rural Hops", network, 600.0, 400.0, 2000, 10000);
    }

    // ── Benchmark Execution & Profiling Engine ──

    private void executeBenchmark(String name, RoadNetwork network, double spanX, double spanZ,
                                  int warmupIterations, int benchIterations) {
        Random rand = new Random(42);

        // Pre-generate query pairs
        List<QueryPair> warmupQueries = generateQueries(rand, warmupIterations, spanX, spanZ);
        List<QueryPair> benchQueries = generateQueries(rand, benchIterations, spanX, spanZ);

        // 1. Warm-up Phase (JIT optimization)
        for (QueryPair q : warmupQueries) {
            RoadRouter.plan(network, q.start, q.end);
        }

        // 2. Timed Benchmark Phase
        long[] latenciesNanos = new long[benchIterations];
        int roadUtilizedCount = 0;
        int directFallbackCount = 0;
        long totalLegs = 0;
        long totalOnRoadLegs = 0;
        long totalOffRoadHops = 0;

        long benchStartNanos = System.nanoTime();

        for (int i = 0; i < benchIterations; i++) {
            QueryPair q = benchQueries.get(i);
            long t0 = System.nanoTime();
            TransportRoute route = RoadRouter.plan(network, q.start, q.end);
            long t1 = System.nanoTime();
            latenciesNanos[i] = t1 - t0;

            assertNotNull(route);
            assertFalse(route.isEmpty());

            int legs = route.legs().size();
            totalLegs += legs;
            long onRoad = route.legs().stream().filter(l -> !l.offRoad()).count();
            long offRoad = route.legs().stream().filter(SplineLeg::offRoad).count();

            totalOnRoadLegs += onRoad;
            totalOffRoadHops += offRoad;

            if (onRoad > 0) {
                roadUtilizedCount++;
            } else {
                directFallbackCount++;
            }
        }

        long benchTotalNanos = System.nanoTime() - benchStartNanos;
        double benchTotalMs = benchTotalNanos / 1_000_000.0;
        double opsPerSec = (benchIterations / (double) benchTotalNanos) * 1_000_000_000.0;

        Arrays.sort(latenciesNanos);

        double avgMicros = (benchTotalNanos / 1000.0) / benchIterations;
        double minMicros = latenciesNanos[0] / 1000.0;
        double p50Micros = latenciesNanos[(int) (benchIterations * 0.50)] / 1000.0;
        double p90Micros = latenciesNanos[(int) (benchIterations * 0.90)] / 1000.0;
        double p95Micros = latenciesNanos[(int) (benchIterations * 0.95)] / 1000.0;
        double p99Micros = latenciesNanos[(int) (benchIterations * 0.99)] / 1000.0;
        double maxMicros = latenciesNanos[benchIterations - 1] / 1000.0;

        double roadUsagePct = (roadUtilizedCount * 100.0) / benchIterations;
        double avgLegs = (double) totalLegs / benchIterations;
        double avgOnRoadLegs = (double) totalOnRoadLegs / benchIterations;

        // Print Formatted Report Table
        System.out.println("--------------------------------------------------------------------------");
        System.out.printf("  Benchmark Target:       %s\n", name);
        System.out.printf("  Network Size:           %d Road Edges\n", network.edgeCount());
        System.out.printf("  Total Executions:       %d queries in %.2f ms\n", benchIterations, benchTotalMs);
        System.out.printf("  Throughput:             %,.0f ops/sec\n", opsPerSec);
        System.out.println("  ------------------------------------------------------------------------");
        System.out.printf("  Average Latency:        %.2f μs (%.4f ms)\n", avgMicros, avgMicros / 1000.0);
        System.out.printf("  Min / P50 / P90:        %.2f μs / %.2f μs / %.2f μs\n", minMicros, p50Micros, p90Micros);
        System.out.printf("  P95 / P99 / Max:        %.2f μs / %.2f μs / %.2f μs\n", p95Micros, p99Micros, maxMicros);
        System.out.println("  ------------------------------------------------------------------------");
        System.out.printf("  Road Utilization Rate:  %.1f%% (%d on-road, %d direct fallback)\n", roadUsagePct, roadUtilizedCount, directFallbackCount);
        System.out.printf("  Avg Legs per Route:     %.2f total (%.2f on-road cruising, %.2f off-road hops)\n", avgLegs, avgOnRoadLegs, (double) totalOffRoadHops / benchIterations);
        System.out.println("--------------------------------------------------------------------------");

        // Performance Assertions
        assertTrue(avgMicros < 250.0, "Average latency should be under 250 μs (actual: " + avgMicros + " μs)");
        assertTrue(p99Micros < 1500.0, "P99 latency should be under 1.5 ms (actual: " + p99Micros + " μs)");
        assertTrue(opsPerSec > 4000.0, "Throughput should exceed 4,000 ops/sec (actual: " + opsPerSec + ")");
    }

    // ── Topology Builders ──

    private static RoadNetwork buildMetropolisGrid(int rows, int cols, double spacing) {
        RoadNetwork network = new RoadNetwork();

        // Horizontal roads
        for (int r = 0; r < rows; r++) {
            double z = r * spacing;
            SplineModel spline = new SplineModel();
            spline.addPoint(new SplineVec3(0, 64, z));
            spline.addPoint(new SplineVec3((cols - 1) * spacing, 64, z));
            RoadEdge edge = new RoadEdge(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "stone", spline);
            edge.setStatus(RoadEdge.EdgeStatus.COMPLETE);
            network.addEdge(edge);
        }

        // Vertical cross roads (creating hundreds of cross-intersections)
        for (int c = 0; c < cols; c++) {
            double x = c * spacing;
            SplineModel spline = new SplineModel();
            spline.addPoint(new SplineVec3(x, 64, 0));
            spline.addPoint(new SplineVec3(x, 64, (rows - 1) * spacing));
            RoadEdge edge = new RoadEdge(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "stone", spline);
            edge.setStatus(RoadEdge.EdgeStatus.COMPLETE);
            network.addEdge(edge);
        }

        return network;
    }

    private static RoadNetwork buildSuburbanNetwork(int mainAvenues, int branchesPerAvenue) {
        RoadNetwork network = new RoadNetwork();

        for (int i = 0; i < mainAvenues; i++) {
            double z = i * 40.0;
            SplineModel aveSpline = new SplineModel();
            aveSpline.addPoint(new SplineVec3(0, 64, z));
            aveSpline.addPoint(new SplineVec3(250, 64, z));
            aveSpline.addPoint(new SplineVec3(500, 64, z));
            RoadEdge aveEdge = new RoadEdge(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "stone", aveSpline);
            aveEdge.setStatus(RoadEdge.EdgeStatus.COMPLETE);
            network.addEdge(aveEdge);

            // Branches forming T-junctions
            for (int b = 1; b <= branchesPerAvenue; b++) {
                double x = b * 50.0;
                SplineModel branchSpline = new SplineModel();
                branchSpline.addPoint(new SplineVec3(x, 64, z));
                branchSpline.addPoint(new SplineVec3(x, 64, z + 35.0));
                RoadEdge branchEdge = new RoadEdge(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "dirt", branchSpline);
                branchEdge.setStatus(RoadEdge.EdgeStatus.COMPLETE);
                network.addEdge(branchEdge);
            }
        }

        return network;
    }

    private static RoadNetwork buildDisjointRuralNetwork(int clusters, double gapDistance) {
        RoadNetwork network = new RoadNetwork();

        for (int c = 0; c < clusters; c++) {
            double startX = c * (60.0 + gapDistance);
            // Cluster internal road 1
            SplineModel s1 = new SplineModel();
            s1.addPoint(new SplineVec3(startX, 64, 0));
            s1.addPoint(new SplineVec3(startX + 30.0, 64, 20.0));
            RoadEdge e1 = new RoadEdge(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "stone", s1);
            e1.setStatus(RoadEdge.EdgeStatus.COMPLETE);
            network.addEdge(e1);

            // Cluster internal road 2 (connecting to road 1)
            SplineModel s2 = new SplineModel();
            s2.addPoint(new SplineVec3(startX + 30.0, 64, 20.0));
            s2.addPoint(new SplineVec3(startX + 60.0, 64, 0));
            RoadEdge e2 = new RoadEdge(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "dirt", s2);
            e2.setStatus(RoadEdge.EdgeStatus.COMPLETE);
            network.addEdge(e2);
        }

        return network;
    }

    private static List<QueryPair> generateQueries(Random rand, int count, double spanX, double spanZ) {
        List<QueryPair> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int sx = (int) (rand.nextDouble() * spanX);
            int sz = (int) (rand.nextDouble() * spanZ);
            int ex = (int) (rand.nextDouble() * spanX);
            int ez = (int) (rand.nextDouble() * spanZ);
            list.add(new QueryPair(new PathPoint(sx, 64, sz), new PathPoint(ex, 64, ez)));
        }
        return list;
    }

    private record QueryPair(PathPoint start, PathPoint end) {}
}
