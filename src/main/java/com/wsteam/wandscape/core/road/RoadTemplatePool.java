package com.wsteam.wandscape.core.road;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * A weighted pool of road templates for random selection during expansion.
 * Templates are registered by id; selection respects per-template weight.
 *
 * <p>All state is immutable after construction. Thread-safe for selection.
 */
public class RoadTemplatePool {

    private final Map<String, TemplateMeta> templates;
    private final List<TemplateMeta> list;
    private final int totalWeight;

    private RoadTemplatePool(Map<String, TemplateMeta> templates) {
        this.templates = Collections.unmodifiableMap(new LinkedHashMap<>(templates));
        this.list = List.copyOf(templates.values());
        int w = 0;
        for (TemplateMeta t : list) {
            w += t.weight();
        }
        this.totalWeight = w;
    }

    // ---- Factory ----

    public static RoadTemplatePool of(List<TemplateMeta> metas) {
        Map<String, TemplateMeta> map = new LinkedHashMap<>();
        for (TemplateMeta m : metas) {
            map.put(m.id(), m);
        }
        return new RoadTemplatePool(map);
    }

    // ---- Queries ----

    public TemplateMeta get(String id) {
        return templates.get(id);
    }

    public int size() {
        return templates.size();
    }

    public int totalWeight() {
        return totalWeight;
    }

    // ---- Selection ----

    /** Weighted random pick from ALL templates. */
    public TemplateMeta pick(Random rng) {
        return pickFrom(list, rng);
    }

    /**
     * Filter templates where an exit can face toward {@code heading}
     * after some rotation, then weighted-pick from the result.
     */
    public TemplateMeta pickFacing(CardinalFacing heading, Random rng) {
        List<TemplateMeta> candidates = new ArrayList<>();
        for (TemplateMeta tm : list) {
            if (canFaceToward(tm, heading)) {
                candidates.add(tm);
            }
        }
        if (candidates.isEmpty()) return null;
        return pickFrom(candidates, rng);
    }

    /**
     * Like {@link #pickFacing}, but also returns the best rotation
     * (0-3 CCW steps) that makes the exit face toward {@code heading}.
     */
    public Picked pickWithRotation(CardinalFacing heading, Random rng) {
        List<Picked> candidates = new ArrayList<>();
        for (TemplateMeta tm : list) {
            int rotation = bestRotationForExit(tm, heading);
            if (rotation >= 0) {
                // Weight the candidate
                candidates.add(new Picked(tm, rotation));
            }
        }
        if (candidates.isEmpty()) {
            // Fallback: pick any template, default rotation
            TemplateMeta fallback = pick(rng);
            if (fallback == null) return null;
            return new Picked(fallback, 0);
        }
        // Weighted selection from candidates
        return weightedPickFromCandidates(candidates, rng);
    }

    private Picked weightedPickFromCandidates(List<Picked> candidates, Random rng) {
        int totalW = 0;
        for (Picked p : candidates) {
            totalW += p.template().weight();
        }
        if (totalW == 0) return candidates.get(rng.nextInt(candidates.size()));
        int roll = rng.nextInt(totalW);
        int cumulative = 0;
        for (Picked p : candidates) {
            cumulative += p.template().weight();
            if (roll < cumulative) return p;
        }
        return candidates.get(candidates.size() - 1);
    }

    /**
     * Check if any exit of the template can face toward {@code targetHeading}
     * after some rotation.
     */
    public static boolean canFaceToward(TemplateMeta template, CardinalFacing targetHeading) {
        return bestRotationForExit(template, targetHeading) >= 0;
    }

    /**
     * Find the rotation (0-3 CCW steps) such that an exit faces toward
     * {@code targetHeading}. Returns -1 if no rotation works.
     */
    public static int bestRotationForExit(TemplateMeta template, CardinalFacing targetHeading) {
        for (int r = 0; r < 4; r++) {
            for (EntryExit exit : template.exits()) {
                CardinalFacing rotated = exit.facing().rotate(r);
                if (rotated == targetHeading) {
                    return r;
                }
            }
        }
        // No exact match — try ±90° tolerance
        for (int r = 0; r < 4; r++) {
            for (EntryExit exit : template.exits()) {
                CardinalFacing rotated = exit.facing().rotate(r);
                if (angularDistance(rotated, targetHeading) <= 1) {
                    return r;
                }
            }
        }
        return -1;
    }

    /** Angular distance in 90° steps (0 = same, 1 = adjacent, 2 = opposite). */
    static int angularDistance(CardinalFacing a, CardinalFacing b) {
        int diff = Math.abs(a.horizontalIndex() - b.horizontalIndex());
        return Math.min(diff, 4 - diff);
    }

    // ---- Internal ----

    private TemplateMeta pickFrom(List<TemplateMeta> candidates, Random rng) {
        if (candidates.isEmpty()) return null;
        int totalW = 0;
        for (TemplateMeta tm : candidates) {
            totalW += tm.weight();
        }
        if (totalW == 0) return candidates.get(rng.nextInt(candidates.size()));
        int roll = rng.nextInt(totalW);
        int cumulative = 0;
        for (TemplateMeta tm : candidates) {
            cumulative += tm.weight();
            if (roll < cumulative) return tm;
        }
        return candidates.get(candidates.size() - 1);
    }

    // ---- Types ----

    /** A template + pre-computed rotation for placement. */
    public record Picked(TemplateMeta template, int rotation) {}

    @Override
    public String toString() {
        return "RoadTemplatePool[" + templates.size() + " templates, totalWeight=" + totalWeight + "]";
    }
}
