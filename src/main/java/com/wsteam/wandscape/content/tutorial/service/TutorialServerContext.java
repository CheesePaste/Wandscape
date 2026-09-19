package com.wsteam.wandscape.content.tutorial.service;

/**
 * Pure view of colony state consumed by {@link TutorialProgressService#computeStep}.
 * Kept MC-free so the step logic is unit-testable with a fake implementation.
 * Check order must match {@code TutorialRegistry.STEPS}.
 */
public interface TutorialServerContext {

    /** The colony owns at least one building of this category (steps 1, 2 and 4). */
    boolean hasCategory(String category);

    /** Player has deposited at least one item into the colony warehouse (step 3). */
    boolean hasPlayerDeposited();

    /** Player has published at least one workstation synthesize request (step 5). */
    boolean hasPlayerSynthesized();
}
