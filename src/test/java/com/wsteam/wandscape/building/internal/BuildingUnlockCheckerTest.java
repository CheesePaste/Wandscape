package com.wsteam.wandscape.building.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import com.wsteam.wandscape.building.data.BuildingConfig;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure unlock-decision rule in {@link BuildingUnlockChecker}.
 *
 * <p>These tests exercise {@link BuildingUnlockChecker#isUnlockedAtLevel} and the
 * no-colony branch of {@link BuildingUnlockChecker#isUnlocked} — neither of which
 * touches {@code WandscapeEngine} or FML, so they run in a bare JVM. The level
 * <em>resolution</em> (server manager vs. client synced panel level) is side-aware
 * and covered by the single-player/dedicated-server behaviour itself.
 */
class BuildingUnlockCheckerTest {

    /** A minimal config with just the fields the unlock rule reads (category + unlock requirement). */
    private static BuildingConfig building(String category, BuildingConfig.UnlockRequirement req) {
        return new BuildingConfig(
                "test", "Test", "test", category,
                List.of(), List.of(), List.of(), Map.of(),
                0, 0, 0,
                req,
                null, null, null,
                null, null, null, null, null, null,
                List.of(), List.of(),
                false, false,
                List.of());
    }

    @Test
    void governmentAlwaysUnlocked() {
        var cfg = building("government", new BuildingConfig.UnlockRequirement(30));
        assertTrue(BuildingUnlockChecker.isUnlockedAtLevel(1, cfg));
        assertTrue(BuildingUnlockChecker.isUnlocked(null, cfg));
    }

    @Test
    void noneRequirementAlwaysUnlocked() {
        var cfg = building("shop", BuildingConfig.UnlockRequirement.NONE);
        assertTrue(BuildingUnlockChecker.isUnlockedAtLevel(1, cfg));
    }

    @Test
    void levelBelowRequirementLocked() {
        var cfg = building("shop", new BuildingConfig.UnlockRequirement(5));
        assertFalse(BuildingUnlockChecker.isUnlockedAtLevel(4, cfg));
    }

    @Test
    void levelAtRequirementUnlocked() {
        var cfg = building("shop", new BuildingConfig.UnlockRequirement(5));
        assertTrue(BuildingUnlockChecker.isUnlockedAtLevel(5, cfg));
    }

    @Test
    void maxLevelUnlocksMaxRequirement() {
        // Regression for the "colony level 30 but buildings still locked" bug.
        var cfg = building("shop", new BuildingConfig.UnlockRequirement(30));
        assertTrue(BuildingUnlockChecker.isUnlockedAtLevel(30, cfg));
    }

    @Test
    void nullConfigLocked() {
        assertFalse(BuildingUnlockChecker.isUnlockedAtLevel(30, null));
    }

    @Test
    void nonGovernmentWithoutColonyLocked() {
        var cfg = building("shop", BuildingConfig.UnlockRequirement.NONE);
        assertFalse(BuildingUnlockChecker.isUnlocked(null, cfg));
    }
}
