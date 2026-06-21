package com.wsteam.wandscape.core.road;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Greedy forward template chain expander.
 *
 * <p>Given a starting position, target, budget, and template pool,
 * expands a chain of template placements toward the target.
 * Templates are selected with weighted randomness; collision with
 * blocked positions triggers lateral jitter.
 *
 * <p>Purely algorithmic — zero MC dependencies.
 */
public final class TemplateExpander {

    /** Distance threshold: when closer than this, stop expanding and place cap. */
    private static final int CLOSE_THRESHOLD = 8;

    private TemplateExpander() {}

    /**
     * Expand a template chain from {@code entryPos} toward {@code target}.
     *
     * @param entryPos  starting world position (access point, in XZ)
     * @param target    desired destination (other access point, in XZ)
     * @param budget    total template cost budget (consumed as templates are placed)
     * @param pool      template pool for weighted random selection
     * @param obstacles set of blocked XZ positions (buildings, existing roads, water, etc.)
     * @param rng       random source for template selection
     * @return ordered list of placements (may be empty if no progress possible)
     */
    public static List<TemplatePlacement> expand(
            XZPoint entryPos,
            XZPoint target,
            int budget,
            RoadTemplatePool pool,
            Set<XZPoint> obstacles,
            Random rng) {

        if (pool == null || pool.size() == 0) {
            return Collections.emptyList();
        }

        List<TemplatePlacement> placements = new ArrayList<>();
        XZPoint tip = entryPos;  // world position of the chain's leading edge
        int remaining = budget;

        while (remaining > 0) {
            int dx = target.x() - tip.x();
            int dz = target.z() - tip.z();
            int dist = Math.abs(dx) + Math.abs(dz);

            // Close enough to target — done
            if (dist <= CLOSE_THRESHOLD) break;

            CardinalFacing heading = CardinalFacing.toward(dx, dz);

            // Pick template whose exit can face toward heading
            RoadTemplatePool.Picked picked = pool.pickWithRotation(heading, rng);
            if (picked == null) break;

            TemplateMeta tm = picked.template();
            int rotation = picked.rotation();

            // Find the entry that best aligns with the chain's incoming direction
            CardinalFacing incomingDir = heading.opposite();
            EntryExit alignedEntry = findAlignedEntry(tm, rotation, incomingDir);

            // Place template so its aligned entry sits at the chain tip
            int originX = tip.x() - alignedEntry.dx();
            int originZ = tip.z() - alignedEntry.dz();

            // Collision check at origin
            XZPoint originPt = new XZPoint(originX, originZ);
            if (obstacles.contains(originPt)) {
                XZPoint jittered = jitter(originPt, heading);
                if (jittered != null && !obstacles.contains(jittered)) {
                    originX = jittered.x();
                    originZ = jittered.z();
                } else {
                    continue; // try different template
                }
            }

            // Find the exit facing nearest to target heading
            EntryExit exit = findBestExit(tm, rotation, heading);

            // Record placement
            placements.add(new TemplatePlacement(tm.id(), originX, originZ, rotation));
            remaining -= tm.budgetCost();

            // Advance chain tip to this template's exit world position
            tip = new XZPoint(originX + exit.dx(), originZ + exit.dz());
        }

        return placements;
    }

    /**
     * Find the entry whose rotated facing best matches {@code incomingDir}
     * (the direction from which the chain arrives at this template).
     */
    static EntryExit findAlignedEntry(TemplateMeta template, int rotation, CardinalFacing incomingDir) {
        EntryExit best = null;
        int bestScore = Integer.MAX_VALUE;
        for (EntryExit e : template.entries()) {
            EntryExit rotated = e.rotate(rotation);
            int score = RoadTemplatePool.angularDistance(rotated.facing(), incomingDir);
            if (score < bestScore) {
                bestScore = score;
                best = rotated;
            }
        }
        return best != null ? best : template.entries().get(0).rotate(rotation);
    }

    /**
     * Find the exit whose rotated facing best matches {@code heading}.
     * Prefers exact match, then ±90°.
     */
    static EntryExit findBestExit(TemplateMeta template, int rotation, CardinalFacing heading) {
        EntryExit best = null;
        int bestScore = Integer.MAX_VALUE;
        for (EntryExit e : template.exits()) {
            EntryExit rotated = e.rotate(rotation);
            int score = RoadTemplatePool.angularDistance(rotated.facing(), heading);
            if (score < bestScore) {
                bestScore = score;
                best = rotated;
            }
        }
        return best != null ? best : template.exits().get(0).rotate(rotation);
    }

    /**
     * Try to jitter laterally relative to heading.
     * Returns null if jitter positions are also blocked.
     */
    static XZPoint jitter(XZPoint pos, CardinalFacing heading) {
        // Lateral direction = ±90° from heading
        CardinalFacing lateralLeft;
        CardinalFacing lateralRight;
        switch (heading) {
            case NORTH, SOUTH:
                lateralLeft = CardinalFacing.WEST;
                lateralRight = CardinalFacing.EAST;
                break;
            case EAST, WEST:
                lateralLeft = CardinalFacing.NORTH;
                lateralRight = CardinalFacing.SOUTH;
                break;
            default:
                return null;
        }
        // Try left first, then right
        return new XZPoint(pos.x() + lateralLeft.dx(), pos.z() + lateralLeft.dz());
    }

    /** Simple 2D axis-aligned rectangle for obstacle checking. */
    public record Rect2D(int minX, int minZ, int maxX, int maxZ) {
        public boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }
}
