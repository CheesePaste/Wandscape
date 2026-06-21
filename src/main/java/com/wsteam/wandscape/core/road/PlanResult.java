package com.wsteam.wandscape.core.road;

import java.util.List;

/**
 * Result of an organic road planning run.
 *
 * @param placements   ordered template placements to build
 * @param edgesCreated number of new RoadEdges generated
 * @param budgetUsed   total budget consumed across all constraints
 */
public record PlanResult(
        List<TemplatePlacement> placements,
        int edgesCreated,
        int budgetUsed) {
}
