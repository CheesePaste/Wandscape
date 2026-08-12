package com.wsteam.wandscape.engine.service;

/**
 * Pure view of colony state consumed by {@link GuideProgressService#computeStep}.
 * Kept MC-free so the step logic is unit-testable with a fake implementation.
 */
public interface GuideServerContext {

    boolean hasCategory(String category);

    boolean hasType(String buildingTypeId);

    /** Any service building with max_occupancy > 0 AND a tourist is staying overnight. */
    boolean hasInnWithStay();

    /** Whether the colony has at least one completed road segment. */
    boolean hasRoadBuilt();
}
