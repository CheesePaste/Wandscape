package com.wsteam.wandscape.shared.data;

import java.util.List;
import java.util.Map;

import com.wsteam.wandscape.shared.data.WandBehaviorData;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AbilitySetTest {

    @Test
    void constructor_defensiveCopy_originalModificationDoesNotAffect() {
        Map<BehaviorType, Integer> original = new java.util.HashMap<>();
        original.put(BehaviorType.BUILDING, 3);
        AbilitySet set = new AbilitySet(original);
        original.put(BehaviorType.MINING, 5);
        assertFalse(set.abilities().containsKey(BehaviorType.MINING));
    }

    @Test
    void constructor_nullInput_throwsNullPointerException() {
        assertThrows(NullPointerException.class, () -> new AbilitySet(null));
    }

    @Test
    void empty_abilitiesIsEmptyMap() {
        assertTrue(AbilitySet.EMPTY.abilities().isEmpty());
    }

    @Test
    void empty_getLevel_returnsZero() {
        assertEquals(0, AbilitySet.EMPTY.getLevel(BehaviorType.BUILDING));
    }

    @Test
    void empty_satisfies_returnsFalseForPositiveRequired() {
        assertFalse(AbilitySet.EMPTY.satisfies(BehaviorType.BUILDING, 1));
    }

    @Test
    void satisfies_exactLevelMatch_returnsTrue() {
        AbilitySet set = new AbilitySet(Map.of(BehaviorType.BUILDING, 2));
        assertTrue(set.satisfies(BehaviorType.BUILDING, 2));
    }

    @Test
    void satisfies_higherThanRequired_returnsTrue() {
        AbilitySet set = new AbilitySet(Map.of(BehaviorType.BUILDING, 3));
        assertTrue(set.satisfies(BehaviorType.BUILDING, 1));
    }

    @Test
    void satisfies_lowerThanRequired_returnsFalse() {
        AbilitySet set = new AbilitySet(Map.of(BehaviorType.BUILDING, 1));
        assertFalse(set.satisfies(BehaviorType.BUILDING, 2));
    }

    @Test
    void satisfies_behaviorNotPresent_returnsFalse() {
        AbilitySet set = new AbilitySet(Map.of(BehaviorType.MINING, 3));
        assertFalse(set.satisfies(BehaviorType.BUILDING, 1));
    }

    @Test
    void satisfies_requiredLevelZero_alwaysTrue() {
        assertTrue(AbilitySet.EMPTY.satisfies(BehaviorType.BUILDING, 0));
        AbilitySet set = new AbilitySet(Map.of());
        assertTrue(set.satisfies(BehaviorType.RITUAL, 0));
    }

    @Test
    void getLevel_present_returnsCorrectLevel() {
        AbilitySet set = new AbilitySet(Map.of(BehaviorType.FARMING, 4));
        assertEquals(4, set.getLevel(BehaviorType.FARMING));
    }

    @Test
    void getLevel_missing_returnsZero() {
        AbilitySet set = new AbilitySet(Map.of(BehaviorType.BUILDING, 3));
        assertEquals(0, set.getLevel(BehaviorType.MINING));
    }

    @Test
    void merge_emptyList_returnsEmptySet() {
        AbilitySet result = AbilitySet.merge(List.of());
        assertEquals(Map.of(), result.abilities());
    }

    @Test
    void merge_singleWand_returnsSameAbilities() {
        WandBehaviorData wand = fakeWand(Map.of(BehaviorType.BUILDING, 3));
        AbilitySet result = AbilitySet.merge(List.of(wand));
        assertEquals(3, result.getLevel(BehaviorType.BUILDING));
    }

    @Test
    void merge_twoWandsWithOverlap_takesMaxLevel() {
        WandBehaviorData wandA = fakeWand(Map.of(BehaviorType.BUILDING, 2));
        WandBehaviorData wandB = fakeWand(Map.of(BehaviorType.BUILDING, 5));
        AbilitySet result = AbilitySet.merge(List.of(wandA, wandB));
        assertEquals(5, result.getLevel(BehaviorType.BUILDING));
    }

    @Test
    void merge_wandsWithDistinctAndOverlapping_behaviors() {
        WandBehaviorData wandA = fakeWand(Map.of(BehaviorType.BUILDING, 3, BehaviorType.FARMING, 1));
        WandBehaviorData wandB = fakeWand(Map.of(BehaviorType.BUILDING, 1, BehaviorType.MINING, 2));
        AbilitySet result = AbilitySet.merge(List.of(wandA, wandB));
        assertEquals(3, result.getLevel(BehaviorType.BUILDING));
        assertEquals(1, result.getLevel(BehaviorType.FARMING));
        assertEquals(2, result.getLevel(BehaviorType.MINING));
    }

    private static WandBehaviorData fakeWand(Map<BehaviorType, Integer> behaviors) {
        return new WandBehaviorData() {
            @Override public String wandColor() { return "#FFF"; }
            @Override public Map<BehaviorType, Integer> behaviors() { return behaviors; }
            @Override public int range() { return 1; }
            @Override public float manaCostMultiplier() { return 1.0f; }
        };
    }

    @Test
    void abilities_emptyMap_producesEmptySet() {
        AbilitySet set = new AbilitySet(Map.of());
        assertTrue(set.abilities().isEmpty());
        assertEquals(0, set.getLevel(BehaviorType.BUILDING));
    }

    @Test
    void abilities_immutableAfterConstruction() {
        Map<BehaviorType, Integer> input = new java.util.HashMap<>();
        input.put(BehaviorType.CRAFTING, 4);
        AbilitySet set = new AbilitySet(input);
        assertThrows(UnsupportedOperationException.class,
            () -> set.abilities().put(BehaviorType.RITUAL, 1));
    }
}
