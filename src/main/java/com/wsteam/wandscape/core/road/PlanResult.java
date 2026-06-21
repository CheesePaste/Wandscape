package com.wsteam.wandscape.core.road;

import java.util.List;

import java.util.Collections;
import java.util.List;

/**
 * Result of an organic road planning run.
 *
 * @param placements   ordered template placements to build
 * @param edgesCreated number of new RoadEdges generated
 * @param budgetUsed   total template cost consumed across all constraints
 */
public record PlanResult(
        List<TemplatePlacement> placements,
        int edgesCreated,
        int budgetUsed) {

    public static final PlanResult EMPTY = new PlanResult(
            Collections.emptyList(), 0, 0);
}
