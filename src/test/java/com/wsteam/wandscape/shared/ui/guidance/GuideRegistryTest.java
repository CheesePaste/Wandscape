package com.wsteam.wandscape.shared.ui.guidance;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuideRegistryTest {

    private static GuideContext ctx(Set<String> categories, Set<String> types) {
        return new GuideContext() {
            @Override
            public boolean hasCategory(String category) {
                return categories.contains(category);
            }

            @Override
            public boolean hasType(String buildingTypeId) {
                return types.contains(buildingTypeId);
            }
        };
    }

    @BeforeEach
    void resetSession() {
        // Fresh session: no confirmed step, not dismissed.
        GuideSession.applySync(0, false);
    }

    @Test
    void emptyColonyStartsAtTownHallStep() {
        GuideContext c = ctx(Set.of(), Set.of());
        assertEquals(0, GuideSession.derivedStep(c));
        assertEquals(0, GuideSession.currentStep());
        assertTrue(GuideSession.shouldShow());
    }

    @Test
    void townHallOnlyAdvancesToWarehouseStep() {
        GuideContext c = ctx(Set.of("government"), Set.of());
        assertEquals(1, GuideSession.derivedStep(c));
    }

    @Test
    void townHallAndWarehouseCompletesAllSteps() {
        GuideContext c = ctx(Set.of("government"), Set.of("warehouse"));
        assertEquals(2, GuideSession.derivedStep(c));
        assertEquals(GuideRegistry.STEPS.size(), GuideSession.derivedStep(c));
    }

    @Test
    void serverStepNeverRegresses() {
        // Even with an empty colony cache, a server-confirmed step 1 keeps the
        // warehouse step active instead of falling back to the town hall.
        GuideSession.applySync(1, false);
        assertEquals(1, GuideSession.currentStep());
        assertTrue(GuideSession.shouldShow());
    }

    @Test
    void dismissedSuppressesShow() {
        GuideSession.applySync(0, true);
        assertFalse(GuideSession.shouldShow());
    }
}
