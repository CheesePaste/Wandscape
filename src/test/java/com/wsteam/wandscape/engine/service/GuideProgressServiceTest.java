package com.wsteam.wandscape.engine.service;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GuideProgressServiceTest {

    private static final class FakeCtx implements GuideServerContext {
        final Set<String> categories = new HashSet<>();
        final Set<String> types = new HashSet<>();
        boolean innWithStay;
        boolean hasRoadBuilt;

        @Override public boolean hasCategory(String category) { return categories.contains(category); }
        @Override public boolean hasType(String buildingTypeId) { return types.contains(buildingTypeId); }
        @Override public boolean hasInnWithStay() { return innWithStay; }
        @Override public boolean hasRoadBuilt() { return hasRoadBuilt; }
    }

    private static FakeCtx empty() {
        return new FakeCtx();
    }

    @Test
    void emptyColonyStartsAtStepZero() {
        assertEquals(0, GuideProgressService.computeStep(empty()));
    }

    @Test
    void progressesThroughBuildingSteps() {
        FakeCtx c = empty();
        // 1 — town hall
        c.categories.add("government");
        assertEquals(1, GuideProgressService.computeStep(c));

        // 2 — warehouse
        c.types.add("warehouse");
        assertEquals(2, GuideProgressService.computeStep(c));

        // 3 — workstation
        c.categories.add("workstation");
        assertEquals(3, GuideProgressService.computeStep(c));

        // 4 — road
        c.hasRoadBuilt = true;
        assertEquals(4, GuideProgressService.computeStep(c));

        // 5 — bakery
        c.types.add("breadshop");
        assertEquals(5, GuideProgressService.computeStep(c));
    }

    @Test
    void roadRequiredBeforeBakery() {
        FakeCtx c = empty();
        c.categories.add("government");
        c.types.add("warehouse");
        c.categories.add("workstation");
        c.types.add("breadshop"); // bakery built but no road

        // Road not built → stuck at step 3 (waiting for road)
        assertEquals(3, GuideProgressService.computeStep(c));

        c.hasRoadBuilt = true;
        assertEquals(5, GuideProgressService.computeStep(c));
    }

    @Test
    void innStepRequiresStay() {
        FakeCtx c = empty();
        c.categories.add("government");
        c.types.add("warehouse");
        c.categories.add("workstation");
        c.hasRoadBuilt = true;
        c.types.add("breadshop");

        // Inn built but no overnight stay → stays at 5.
        c.categories.add("service");
        assertEquals(5, GuideProgressService.computeStep(c));

        c.innWithStay = true;
        assertEquals(6, GuideProgressService.computeStep(c));
    }

    @Test
    void nodeAndAltarAndCraftStationCompleteTutorial() {
        FakeCtx c = empty();
        c.categories.add("government");
        c.types.add("warehouse");
        c.categories.add("workstation");
        c.hasRoadBuilt = true;
        c.types.add("breadshop");
        c.categories.add("service");
        c.innWithStay = true;

        // After inn → step 6, node needed
        assertEquals(6, GuideProgressService.computeStep(c));

        c.categories.add("node");
        assertEquals(7, GuideProgressService.computeStep(c));

        c.categories.add("altar");
        assertEquals(8, GuideProgressService.computeStep(c));

        c.categories.add("crafting_station");
        assertEquals(9, GuideProgressService.computeStep(c));
        assertEquals(guideRegistryStepCount(), GuideProgressService.computeStep(c));
    }

    /** Keep the server check count and the client step list in sync. */
    private static int guideRegistryStepCount() {
        return com.wsteam.wandscape.shared.ui.guidance.GuideRegistry.STEPS.size();
    }
}
