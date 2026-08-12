package com.wsteam.wandscape.guard;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GuardScannerTest {

    @Test
    void testBlacklistLifecycle() {
        int entityId = 1001;
        long now = 1000L;

        assertFalse(GuardScanner.isBlacklisted(entityId, now), "Initially not blacklisted");

        // Blacklist for 600 ticks (from 1000 to 1600)
        GuardScanner.blacklistMob(entityId, now, 600);

        assertTrue(GuardScanner.isBlacklisted(entityId, now), "Is blacklisted immediately");
        assertTrue(GuardScanner.isBlacklisted(entityId, 1500L), "Is blacklisted before expiration");
        assertFalse(GuardScanner.isBlacklisted(entityId, 1600L), "Expired at expiration time");
        assertFalse(GuardScanner.isBlacklisted(entityId, 2000L), "Expired after expiration time");
    }
}
