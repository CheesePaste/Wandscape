package com.wsteam.wandscape.engine.service;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GuideProgressServiceTest {

    private static final class FakeCtx implements GuideServerContext {
        final Set<String> categories = new HashSet<>();
        final Set<String> types = new HashSet<>();
        boolean deposited;
        boolean synthesized;
        boolean roadPlaced;
        boolean breadshopStocked;
        boolean nodeGatherPublished;
        boolean innWithStay;

        @Override public boolean hasCategory(String category) { return categories.contains(category); }
        @Override public boolean hasType(String buildingTypeId) { return types.contains(buildingTypeId); }
        @Override public boolean hasPlayerDeposited() { return deposited; }
        @Override public boolean hasPlayerSynthesized() { return synthesized; }
        @Override public boolean hasPlayerPlacedRoad() { return roadPlaced; }
        @Override public boolean hasBreadshopStocked() { return breadshopStocked; }
        @Override public boolean hasNodeGatherPublished() { return nodeGatherPublished; }
        @Override public boolean hasInnWithStay() { return innWithStay; }
    }

    private static FakeCtx empty() {
        return new FakeCtx();
    }

    @Test
    void emptyColonyStartsAtStepZero() {
        assertEquals(0, GuideProgressService.computeStep(empty()));
    }

    @Test
    void progressesThroughAllTenSteps() {
        FakeCtx c = empty();

        c.categories.add("government");
        assertEquals(1, GuideProgressService.computeStep(c));

        c.types.add("warehouse");
        assertEquals(2, GuideProgressService.computeStep(c));

        c.deposited = true;
        assertEquals(3, GuideProgressService.computeStep(c));

        c.categories.add("workstation");
        assertEquals(4, GuideProgressService.computeStep(c));

        c.synthesized = true;
        assertEquals(5, GuideProgressService.computeStep(c));

        c.roadPlaced = true;
        assertEquals(6, GuideProgressService.computeStep(c));

        c.types.add("breadshop");
        c.breadshopStocked = true;
        assertEquals(7, GuideProgressService.computeStep(c));

        c.categories.add("node");
        c.nodeGatherPublished = true;
        assertEquals(8, GuideProgressService.computeStep(c));

        c.categories.add("altar");
        assertEquals(9, GuideProgressService.computeStep(c));

        c.innWithStay = true;
        assertEquals(10, GuideProgressService.computeStep(c));
        assertEquals(guideRegistryStepCount(), GuideProgressService.computeStep(c));
    }

    @Test
    void interactionStepsRequireTheRealAction() {
        FakeCtx c = empty();
        c.categories.add("government");
        c.types.add("warehouse");

        // Warehouse built but nothing deposited yet → stays on step 3's prerequisite (2).
        assertEquals(2, GuideProgressService.computeStep(c));

        c.deposited = true;
        assertEquals(3, GuideProgressService.computeStep(c));

        // Workstation built but no synthesize published → stays at 4.
        c.categories.add("workstation");
        assertEquals(4, GuideProgressService.computeStep(c));

        c.synthesized = true;
        assertEquals(5, GuideProgressService.computeStep(c));

        // No road placed yet → stays at 5.
        assertEquals(5, GuideProgressService.computeStep(c));

        c.roadPlaced = true;
        assertEquals(6, GuideProgressService.computeStep(c));

        // Breadshop built but not yet stocked → stays at 6.
        c.types.add("breadshop");
        assertEquals(6, GuideProgressService.computeStep(c));

        c.breadshopStocked = true;
        assertEquals(7, GuideProgressService.computeStep(c));

        // Node built but no gather published → stays at 7.
        c.categories.add("node");
        assertEquals(7, GuideProgressService.computeStep(c));

        c.nodeGatherPublished = true;
        assertEquals(8, GuideProgressService.computeStep(c));
    }

    @Test
    void breadshopNeedsStockAndInnNeedsOvernightStay() {
        FakeCtx c = empty();
        c.categories.add("government");
        c.types.add("warehouse");
        c.deposited = true;
        c.categories.add("workstation");
        c.synthesized = true;
        c.roadPlaced = true;
        c.types.add("breadshop");
        c.breadshopStocked = true;
        c.categories.add("node");
        c.nodeGatherPublished = true;
        c.categories.add("altar");
        assertEquals(9, GuideProgressService.computeStep(c));

        // Inn built but no overnight stay → stays at 9.
        c.categories.add("service");
        assertEquals(9, GuideProgressService.computeStep(c));

        c.innWithStay = true;
        assertEquals(10, GuideProgressService.computeStep(c));
        assertEquals(guideRegistryStepCount(), GuideProgressService.computeStep(c));
    }

    /** Keep the server check count and the client step list in sync. */
    private static int guideRegistryStepCount() {
        return com.wsteam.wandscape.shared.ui.guidance.GuideRegistry.STEPS.size();
    }
}
