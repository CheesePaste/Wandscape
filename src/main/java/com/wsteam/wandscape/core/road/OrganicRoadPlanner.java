package com.wsteam.wandscape.core.road;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Organic road network planner.
 *
 * <p>Uses MST to determine which building pairs must connect (connectivity
 * guarantee), then delegates to {@link TemplateExpander} for organic
 * template-based path generation.
 *
 * <p>Purely algorithmic — zero MC dependencies.
 */
public final class OrganicRoadPlanner {

    /** Budget multiplier: manhattanDist × this = template budget. */
    private static final double BUDGET_MULTIPLIER = 1.3;

    /** Minimum budget per constraint (prevents zero-budget for nearby buildings). */
    private static final int MIN_BUDGET = 16;

    private OrganicRoadPlanner() {}

    /**
     * Plan organic roads for a set of buildings.
     *
     * @param buildings  all buildings in the colony
     * @param accessFn   computes the access point for a building given a target direction
     * @param threshold  minimum building count to trigger road planning
     * @param pool       template pool for weighted random selection
     * @param obstacles  set of blocked XZ positions (architectural/geographic)
     * @param rng        random source for template selection
     * @return placement plan, or empty if below threshold
     */
    public static PlanResult plan(
            List<RoadBuildingData> buildings,
            AccessPointFn accessFn,
            int threshold,
            RoadTemplatePool pool,
            Set<XZPoint> obstacles,
            Random rng) {

        int count = buildings.size();
        if (count < threshold) {
            return new PlanResult(Collections.emptyList(), 0, 0);
        }

        // 1. Compute MST constraint pairs
        List<UuidPair> pairs = mstPairs(buildings);
        if (pairs.isEmpty()) {
            return new PlanResult(Collections.emptyList(), 0, 0);
        }

        // 2. For each pair: compute access points + budget, then expand
        List<TemplatePlacement> allPlacements = new ArrayList<>();
        Set<XZPoint> occupied = new HashSet<>(obstacles);
        int totalBudgetUsed = 0;

        for (UuidPair pair : pairs) {
            RoadBuildingData bdA = findBuilding(buildings, pair.a());
            RoadBuildingData bdB = findBuilding(buildings, pair.b());
            if (bdA == null || bdB == null) continue;

            XZPoint centerA = XZPoint.fromBuildData(bdA);
            XZPoint centerB = XZPoint.fromBuildData(bdB);

            // Compute direction and access points
            int dx = centerB.x() - centerA.x();
            int dz = centerB.z() - centerA.z();
            CardinalFacing dirAToB = CardinalFacing.toward(dx, dz);
            CardinalFacing dirBToA = CardinalFacing.toward(-dx, -dz);

            XZPoint accessA = accessFn.compute(bdA, dirAToB);
            XZPoint accessB = accessFn.compute(bdB, dirBToA);

            int manhattanDist = accessA.manhattanTo(accessB);
            int budget = Math.max(MIN_BUDGET, (int) (manhattanDist * BUDGET_MULTIPLIER));

            // Add access points to occupied (don't build on them)
            occupied.add(accessA);
            occupied.add(accessB);

            // Expand from A toward B
            List<TemplatePlacement> chain = TemplateExpander.expand(
                    accessA, accessB, budget, pool, occupied, rng);

            if (!chain.isEmpty()) {
                allPlacements.addAll(chain);
                totalBudgetUsed += budget - remaining(chain, pool);
                // Mark placement positions as occupied
                for (TemplatePlacement p : chain) {
                    occupied.add(new XZPoint(p.x(), p.z()));
                }
            }
        }

        return new PlanResult(allPlacements, pairs.size(), totalBudgetUsed);
    }

    // ---- MST constraint extraction ----

    /** Unordered UUID pair representing an MST edge. */
    record UuidPair(UUID a, UUID b) {
        static UuidPair of(UUID x, UUID y) {
            return x.compareTo(y) <= 0 ? new UuidPair(x, y) : new UuidPair(y, x);
        }
    }

    static List<UuidPair> mstPairs(List<RoadBuildingData> buildings) {
        if (buildings.size() < 2) return Collections.emptyList();

        List<XZPoint> points = buildings.stream()
                .map(XZPoint::fromBuildData)
                .toList();
        List<MstEdge> mst = MstCalculator.prim(points, XZPoint::manhattanTo);

        List<UuidPair> pairs = new ArrayList<>();
        for (MstEdge e : mst) {
            UUID a = buildings.get(e.fromIndex()).id();
            UUID b = buildings.get(e.toIndex()).id();
            pairs.add(UuidPair.of(a, b));
        }
        return pairs;
    }

    // ---- Helpers ----

    private static RoadBuildingData findBuilding(List<RoadBuildingData> buildings, UUID id) {
        for (RoadBuildingData bd : buildings) {
            if (bd.id().equals(id)) return bd;
        }
        return null;
    }

    private static int remaining(List<TemplatePlacement> placements, RoadTemplatePool pool) {
        int cost = 0;
        for (TemplatePlacement p : placements) {
            TemplateMeta tm = pool.get(p.templateId());
            if (tm != null) {
                cost += tm.budgetCost();
            }
        }
        return cost;
    }

    // ---- Functional interface ----

    /**
     * Compute the road access point for a building, given
     * the direction toward the other building.
     */
    @FunctionalInterface
    public interface AccessPointFn {
        XZPoint compute(RoadBuildingData building, CardinalFacing direction);
    }
}
