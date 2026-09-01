package com.wsteam.wandscape.building.internal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.wsteam.wandscape.content.building.internal.BuildingConfigLoader;
import org.junit.jupiter.api.Test;

class BuildingConfigLoaderTest {

    @Test
    void testNullSafety() {
        BuildingConfigLoader loader = BuildingConfigLoader.getInstance();

        assertDoesNotThrow(() -> {
            assertNull(loader.get(null), "loader.get(null) should return null without NPE");
            assertNull(loader.getByCategory(null), "loader.getByCategory(null) should return null without NPE");
            assertFalse(loader.has(null), "loader.has(null) should return false without NPE");
        });
    }
}
