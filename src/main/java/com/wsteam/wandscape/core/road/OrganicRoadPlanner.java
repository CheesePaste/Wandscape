package com.wsteam.wandscape.core.road;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
 * <p>Two operation modes:
 * <ul>
 *   <li>{@link #plan} — full MST-based plan for all buildings (first-time setup)</li>
 *   <li>{@link #incrementalExpand} — connect a single new building to the
 *       nearest existing node in the network</li>
 * </ul>
 */
public final class OrganicRoadPlanner {

    /** Budget multiplier: manhattanDist × this = template budget. */
    private static final double BUDGET_MULTIPLIER = 1.3;

    /** Minimum budget per constraint. */
    private static final int MIN_BUDGET = 16;

    private OrganicRoadPlanner() {}

    /**
     * Plan organic roads for all buildings via MST constraints.
     * Only called once when threshold is first reached.
     *
     * @return placement result per MST pair
     */
    public static PlanResult plan(
            List<RoadBuildingData> buildings,
            AccessPointFn accessFn,
            int threshold,
            RoadTemplatePool pool,
            Set<XZPoint> obstacles,
            Random rng) {

        int count = buildings.size();
        if (count < threshold || count < 2) {
            return PlanResult.EMPTY;
        }

        // MST constraint pairs
        List<UuidPair> pairs = mstPairs(buildings);
        if (pairs.isEmpty()) {
            return PlanResult.EMPTY;
        }

        // For each pair: compute access points, expand
        List<TemplatePlacement> allPlacements = new ArrayList<>();
        Set<XZPoint> occupied = new HashSet<>(obstacles);
        int totalCost = 0;

        for (UuidPair pair : pairs) {
            RoadBuildingData bdA = findBuilding(buildings, pair.a());
            RoadBuildingData bdB = findBuilding(buildings, pair.b());
            if (bdA == null || bdB == null) continue;

            XZPoint centerA = XZPoint.fromBuildData(bdA);
            XZPoint centerB = XZPoint.fromBuildData(bdB);
            int dx = centerB.x() - centerA.x();
            int dz = centerB.z() - centerA.z();
            CardinalFacing dirAToB = CardinalFacing.toward(dx, dz);
            CardinalFacing dirBToA = CardinalFacing.toward(-dx, -dz);

            XZPoint accessA = accessFn.compute(bdA, dirAToB);
            XZPoint accessB = accessFn.compute(bdB, dirBToA);

            int manhattanDist = accessA.manhattanTo(accessB);
            int budget = Math.max(MIN_BUDGET, (int) (manhattanDist * BUDGET_MULTIPLIER));

            occupied.add(accessA);
            occupied.add(accessB);

            List<TemplatePlacement> chain = TemplateExpander.expand(
                    accessA, accessB, budget, pool, occupied, rng);

            if (!chain.isEmpty()) {
                totalCost += templateCost(chain, pool);
                allPlacements.addAll(chain);
                // Mark placed origins as occupied
                for (TemplatePlacement p : chain) {
                    occupied.add(new XZPoint(p.x(), p.z()));
                }
            }
        }

        if (allPlacements.isEmpty()) {
            return PlanResult.EMPTY;
        }
        return new PlanResult(allPlacements, pairs.size(), totalCost);
    }

    /**
     * Connect a new building to the nearest node in the existing road network.
     * Used for incremental builds after the initial MST plan.
     *
     * @param newBuilding    the newly built building
     * @param network        existing road network (nodes + edges)
     * @param existingBldgs  all existing buildings (excluding the new one)
     * @param accessFn       access point function
     * @param pool           template pool
     * @param obstacles      blocked XZ positions
     * @param rng            random source
     * @return placements for this single connection (may be empty if no nearby node)
     */
    public static List<TemplatePlacement> incrementalExpand(
            RoadBuildingData newBuilding,
            RoadNetwork network,
            List<RoadBuildingData> existingBldgs,
            AccessPointFn accessFn,
            RoadTemplatePool pool,
            Set<XZPoint> obstacles,
            Random rng) {

        if (network.isEmpty() || existingBldgs.isEmpty()) {
            return Collections.emptyList();
        }

        // Find nearest node (may be BUILDING or INTERSECTION)
        XZPoint newXz = XZPoint.fromBuildData(newBuilding);
        RoadNode nearest = network.findNearestNode(newXz);
        if (nearest == null) return Collections.emptyList();

        // Get the nearest node's world position
        XZPoint nearestXz = nearest.xz();

        // If nearest is a BUILDING node, compute proper access point.
        // For INTERSECTION nodes, use the node position directly.
        XZPoint accessNearest;
        RoadBuildingData nearestBd = null;
        for (RoadBuildingData bd : existingBldgs) {
            if (bd.id().equals(nearest.nodeId())) {
                nearestBd = bd;
                break;
            }
        }

        int dx = nearestXz.x() - newXz.x();
        int dz = nearestXz.z() - newXz.z();
        CardinalFacing dirToNearest = CardinalFacing.toward(dx, dz);

        XZPoint accessNew = accessFn.compute(newBuilding, dirToNearest);

        if (nearestBd != null) {
            CardinalFacing dirFromNearest = CardinalFacing.toward(-dx, -dz);
            accessNearest = accessFn.compute(nearestBd, dirFromNearest);
        } else {
            // INTERSECTION node or orphan — use node position directly
            accessNearest = nearestXz;
        }

        int manhattanDist = accessNew.manhattanTo(accessNearest);
        int budget = Math.max(MIN_BUDGET, (int) (manhattanDist * BUDGET_MULTIPLIER));

        Set<XZPoint> occupied = new HashSet<>(obstacles);
        occupied.add(accessNew);
        occupied.add(accessNearest);
        // Also occupy existing road positions
        for (RoadEdge e : network.getEdges().values()) {
            occupied.addAll(e.getPath());
        }

        return TemplateExpander.expand(accessNew, accessNearest, budget, pool, occupied, rng);
    }

    // ---- Helpers ----

    private static int templateCost(List<TemplatePlacement> chain, RoadTemplatePool pool) {
        int cost = 0;
        for (TemplatePlacement p : chain) {
            TemplateMeta tm = pool.get(p.templateId());
            if (tm != null) cost += tm.budgetCost();
        }
        return cost;
    }

    private static RoadBuildingData findBuilding(List<RoadBuildingData> buildings, UUID id) {
        for (RoadBuildingData bd : buildings) {
            if (bd.id().equals(id)) return bd;
        }
        return null;
    }

    // ---- MST ----

    static List<UuidPair> mstPairs(List<RoadBuildingData> buildings) {
        if (buildings.size() < 2) return Collections.emptyList();
        List<XZPoint> points = buildings.stream().map(XZPoint::fromBuildData).toList();
        List<MstEdge> mst = MstCalculator.prim(points, XZPoint::manhattanTo);
        List<UuidPair> pairs = new ArrayList<>();
        for (MstEdge e : mst) {
            UUID a = buildings.get(e.fromIndex()).id();
            UUID b = buildings.get(e.toIndex()).id();
            pairs.add(UuidPair.of(a, b));
        }
        return pairs;
    }

    record UuidPair(UUID a, UUID b) {
        static UuidPair of(UUID x, UUID y) {
            return x.compareTo(y) <= 0 ? new UuidPair(x, y) : new UuidPair(y, x);
        }
    }

    @FunctionalInterface
    public interface AccessPointFn {
        XZPoint compute(RoadBuildingData building, CardinalFacing direction);
    }
}
