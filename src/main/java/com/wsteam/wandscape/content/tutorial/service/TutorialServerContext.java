package com.wsteam.wandscape.content.tutorial.service;

/**
 * Pure view of colony state consumed by {@link TutorialProgressService#computeStep}.
 * Kept MC-free so the step logic is unit-testable with a fake implementation.
 * Check order must match {@code TutorialRegistry.STEPS}.
 */
public interface TutorialServerContext {

    boolean hasCategory(String category);

    /** Player has deposited at least one item into the colony warehouse (step 3). */
    boolean hasPlayerDeposited();

    /** Player has published at least one workstation synthesize request (step 5). */
    boolean hasPlayerSynthesized();

    /** A bakery is built AND has at least one stocked good (step 6). */
    boolean hasBakeryStocked();

    /** An altar is built (step 7). */
    boolean hasAltar();

    /** A tavern is built AND the player has recruited at least one mage there (step 8). */
    boolean hasTavernRecruited();

    /** A mage hut is built AND a mage has moved in as its resident (step 9). */
    boolean hasMageHutResident();

    /** Any service building with max_occupancy > 0 AND a tourist is staying overnight (step 10). */
    boolean hasInnWithStay();
}
