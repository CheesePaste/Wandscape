package com.wsteam.wandscape.engine.service;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GuideProgressServiceTest {

    private static final class FakeCtx implements GuideServerContext {
        final Set<String> categories = new HashSet<>();
        final Set<String> types = new HashSet<>();
        boolean shopPurchased;
        boolean innWithStay;
        boolean tavernRecruited;
        int level;

        @Override public boolean hasCategory(String category) { return categories.contains(category); }
        @Override public boolean hasType(String buildingTypeId) { return types.contains(buildingTypeId); }
        @Override public boolean hasShopPurchased() { return shopPurchased; }
        @Override public boolean hasInnWithStay() { return innWithStay; }
        @Override public boolean hasTavernRecruited() { return tavernRecruited; }
        @Override public int colonyLevel() { return level; }
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
        c.categories.add("government");
        assertEquals(1, GuideProgressService.computeStep(c));

        c.types.add("warehouse");
        assertEquals(2, GuideProgressService.computeStep(c));

        c.categories.add("node");
        assertEquals(3, GuideProgressService.computeStep(c));

        c.categories.add("workstation");
        assertEquals(4, GuideProgressService.computeStep(c));

        c.categories.add("crafting_station");
        assertEquals(5, GuideProgressService.computeStep(c));
    }

    @Test
    void shopStepRequiresPurchase() {
        FakeCtx c = empty();
        c.categories.add("government");
        c.types.add("warehouse");
        c.categories.add("node");
        c.categories.add("workstation");
        c.categories.add("crafting_station");

        // Shop built but no purchase yet → still on shop step.
        c.categories.add("shop");
        assertEquals(5, GuideProgressService.computeStep(c));

        c.shopPurchased = true;
        assertEquals(6, GuideProgressService.computeStep(c));
    }

    @Test
    void innStepRequiresStayAndTavernStepRequiresNpc() {
        FakeCtx c = empty();
        c.categories.add("government");
        c.types.add("warehouse");
        c.categories.add("node");
        c.categories.add("workstation");
        c.categories.add("crafting_station");
        c.categories.add("shop");
        c.shopPurchased = true;

        // Inn built but no overnight stay → stays at 6.
        c.categories.add("service");
        assertEquals(6, GuideProgressService.computeStep(c));

        c.innWithStay = true;
        assertEquals(7, GuideProgressService.computeStep(c));

        // Tavern built but no NPC → stays at 7.
        c.categories.add("tavern");
        assertEquals(7, GuideProgressService.computeStep(c));

        c.tavernRecruited = true;
        assertEquals(8, GuideProgressService.computeStep(c));
    }

    @Test
    void levelUpCompletesTutorial() {
        FakeCtx c = empty();
        c.categories.add("government");
        c.types.add("warehouse");
        c.categories.add("node");
        c.categories.add("workstation");
        c.categories.add("crafting_station");
        c.categories.add("shop");
        c.shopPurchased = true;
        c.categories.add("service");
        c.innWithStay = true;
        c.categories.add("tavern");
        c.tavernRecruited = true;

        assertEquals(8, GuideProgressService.computeStep(c));

        c.level = 2;
        assertEquals(9, GuideProgressService.computeStep(c));
        assertEquals(guideRegistryStepCount(), GuideProgressService.computeStep(c));
    }

    /** Keep the server check count and the client step list in sync. */
    private static int guideRegistryStepCount() {
        return com.wsteam.wandscape.shared.ui.guidance.GuideRegistry.STEPS.size();
    }
}
