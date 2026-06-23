package com.wsteam.wandscape.core.task;

import com.wsteam.wandscape.core.types.BehaviourLevel;
import com.wsteam.wandscape.core.types.BehaviourTag;
import org.junit.jupiter.api.Test;

import java.util.*;

import static com.wsteam.wandscape.core.types.BehaviourTag.*;
import static org.junit.jupiter.api.Assertions.*;

class GlobalTaskPoolTest {

    // ── mergeOverrides ──

    @Test
    void mergeOverridesEmptyReturnsDerivedUnchanged() {
        Map<BehaviourTag, BehaviourLevel> derived = Map.of(BUILDING, BehaviourLevel.of(1));
        Map<BehaviourTag, Integer> overrides = Collections.emptyMap();
        Map<BehaviourTag, BehaviourLevel> result = GlobalTaskPool.mergeOverrides(derived, overrides);
        assertSame(derived, result, "empty overrides should return derived map unchanged");
    }

    @Test
    void mergeOverridesZeroRemovesRequirement() {
        Map<BehaviourTag, BehaviourLevel> derived = new HashMap<>();
        derived.put(BUILDING, BehaviourLevel.of(1));
        derived.put(CRAFTING, BehaviourLevel.of(1));

        Map<BehaviourTag, Integer> overrides = Map.of(CRAFTING, 0);
        Map<BehaviourTag, BehaviourLevel> result = GlobalTaskPool.mergeOverrides(derived, overrides);

        assertEquals(1, result.size());
        assertTrue(result.containsKey(BUILDING));
        assertFalse(result.containsKey(CRAFTING), "zero override should remove CRAFTING");
    }

    @Test
    void mergeOverridesPositiveValueOverridesLevel() {
        Map<BehaviourTag, BehaviourLevel> derived = Map.of(GATHERING, BehaviourLevel.of(1));
        Map<BehaviourTag, Integer> overrides = Map.of(GATHERING, 3);

        Map<BehaviourTag, BehaviourLevel> result = GlobalTaskPool.mergeOverrides(derived, overrides);

        assertEquals(1, result.size());
        assertEquals(BehaviourLevel.of(3), result.get(GATHERING));
    }

    @Test
    void mergeOverridesAddsNewRequirement() {
        Map<BehaviourTag, BehaviourLevel> derived = Map.of(BUILDING, BehaviourLevel.of(1));
        Map<BehaviourTag, Integer> overrides = Map.of(GATHERING, 2);

        Map<BehaviourTag, BehaviourLevel> result = GlobalTaskPool.mergeOverrides(derived, overrides);

        assertEquals(2, result.size());
        assertEquals(BehaviourLevel.of(1), result.get(BUILDING));
        assertEquals(BehaviourLevel.of(2), result.get(GATHERING));
    }

    @Test
    void mergeOverridesMixedOperations() {
        Map<BehaviourTag, BehaviourLevel> derived = new HashMap<>();
        derived.put(BUILDING, BehaviourLevel.of(1));
        derived.put(CRAFTING, BehaviourLevel.of(2));
        derived.put(GATHERING, BehaviourLevel.of(1));

        Map<BehaviourTag, Integer> overrides = new HashMap<>();
        overrides.put(GATHERING, 0);     // remove
        overrides.put(CRAFTING, 3);      // override
        overrides.put(RITUAL, 2);        // add

        Map<BehaviourTag, BehaviourLevel> result = GlobalTaskPool.mergeOverrides(derived, overrides);

        assertEquals(3, result.size());
        assertEquals(BehaviourLevel.of(1), result.get(BUILDING));   // unchanged
        assertEquals(BehaviourLevel.of(3), result.get(CRAFTING));   // overridden
        assertEquals(BehaviourLevel.of(2), result.get(RITUAL));      // added
        assertFalse(result.containsKey(GATHERING));                  // removed
    }

    @Test
    void mergeOverridesDoesNotMutateDerived() {
        Map<BehaviourTag, BehaviourLevel> derived = new HashMap<>();
        derived.put(BUILDING, BehaviourLevel.of(1));

        Map<BehaviourTag, BehaviourLevel> copy = new HashMap<>(derived);
        Map<BehaviourTag, Integer> overrides = Map.of(CRAFTING, 2);

        GlobalTaskPool.mergeOverrides(derived, overrides);
        assertEquals(copy, derived, "original derived map must not be mutated");
    }
}
