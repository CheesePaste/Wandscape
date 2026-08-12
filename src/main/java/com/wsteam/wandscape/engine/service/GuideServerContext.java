package com.wsteam.wandscape.engine.service;

/**
 * Pure view of colony state consumed by {@link GuideProgressService#computeStep}.
 * Kept MC-free so the step logic is unit-testable with a fake implementation.
 */
public interface GuideServerContext {

    boolean hasCategory(String category);

    boolean hasType(String buildingTypeId);

    /** Player has deposited at least one item into the colony warehouse (step 3). */
    boolean hasPlayerDeposited();

    /** Player has published at least one workstation synthesize request (step 5). */
    boolean hasPlayerSynthesized();

    /** Player has manually placed at least one road (step 6). */
    boolean hasPlayerPlacedRoad();

    /** A breadshop is built AND has at least one stocked good (step 7). */
    boolean hasBreadshopStocked();

    /** A node is built AND the player has published at least one gather task (step 8). */
    boolean hasNodeGatherPublished();

    /** Any service building with max_occupancy > 0 AND a tourist is staying overnight. */
    boolean hasInnWithStay();
}
