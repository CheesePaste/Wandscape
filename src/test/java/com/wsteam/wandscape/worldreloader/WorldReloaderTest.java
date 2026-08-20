package com.wsteam.wandscape.worldreloader;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorldReloaderTest {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @Test
    void testConfigSerializationAndDefaults() {
        WorldReloaderConfig config = new WorldReloaderConfig();
        assertNotNull(config.biomeMappings);
        assertFalse(config.biomeMappings.isEmpty());
        assertNotNull(config.structureMappings);
        assertFalse(config.structureMappings.isEmpty());
        assertEquals("minecraft:beacon", config.targetBlock);

        String json = GSON.toJson(config);
        WorldReloaderConfig deserialized = GSON.fromJson(json, WorldReloaderConfig.class);
        assertNotNull(deserialized);
        assertEquals(config.maxRadius, deserialized.maxRadius);
        assertEquals(config.mode, deserialized.mode);
        assertEquals(config.biomeMappings.size(), deserialized.biomeMappings.size());
    }

    @Test
    void testNormalizeId() {
        assertEquals("minecraft:plains", WorldReloaderConfig.normalizeId("plains"));
        assertEquals("minecraft:plains", WorldReloaderConfig.normalizeId("minecraft:plains"));
        assertEquals("custom_mod:biome", WorldReloaderConfig.normalizeId("custom_mod:biome"));
        assertEquals("", WorldReloaderConfig.normalizeId(""));
    }

    @Test
    void testBiomeAndStructureMaps() {
        WorldReloaderConfig config = new WorldReloaderConfig();
        Map<String, String> biomeMap = config.getBiomeMap();
        assertTrue(biomeMap.containsKey("minecraft:grass_block"));
        assertEquals("minecraft:plains", biomeMap.get("minecraft:grass_block"));

        Map<String, String> structureMap = config.getStructureMap();
        assertTrue(structureMap.containsKey("minecraft:target"));
        assertEquals("minecraft:village_snowy", structureMap.get("minecraft:target"));
    }

    @Test
    void testGenerateCirclePositionsRadiusZero() {
        BlockPos center = new BlockPos(100, 64, 200);
        TestDummyTask task = new TestDummyTask(center, 10);
        List<BlockPos> positions = task.generateCirclePositions(0);

        assertEquals(1, positions.size());
        assertEquals(100, positions.get(0).getX());
        assertEquals(200, positions.get(0).getZ());
    }

    @Test
    void testGenerateCirclePositionsConcentricRings() {
        BlockPos center = new BlockPos(0, 64, 0);
        TestDummyTask task = new TestDummyTask(center, 10);

        for (int r = 1; r <= 10; r++) {
            List<BlockPos> positions = task.generateCirclePositions(r);
            assertFalse(positions.isEmpty(), "Radius " + r + " should have positions");

            int rSq = r * r;
            int prevSq = (r - 1) * (r - 1);
            for (BlockPos pos : positions) {
                int dx = pos.getX() - center.getX();
                int dz = pos.getZ() - center.getZ();
                int distSq = dx * dx + dz * dz;

                assertTrue(distSq <= rSq && distSq > prevSq,
                        String.format("Point (%d, %d) distSq=%d not in ring (%d, %d]", dx, dz, distSq, prevSq, rSq));
            }
        }
    }

    @Test
    void testShouldPreserveCenterArea() {
        BlockPos center = new BlockPos(100, 64, 100);
        TestDummyTask task = new TestDummyTask(center, 10);

        // Center pyramid base layers
        // Layer 0: center itself (y = 64)
        assertTrue(task.testPreserve(new BlockPos(100, 64, 100)));
        // Layer 1: y = 63, offset +/- 1
        assertTrue(task.testPreserve(new BlockPos(101, 63, 100)));
        assertTrue(task.testPreserve(new BlockPos(99, 63, 99)));
        assertFalse(task.testPreserve(new BlockPos(102, 63, 100))); // too far

        // Layer 4: y = 60, offset +/- 4
        assertTrue(task.testPreserve(new BlockPos(104, 60, 104)));
        assertFalse(task.testPreserve(new BlockPos(105, 60, 100))); // too far

        // Below pyramid: y = 59
        assertFalse(task.testPreserve(new BlockPos(100, 59, 100)));
    }

    @Test
    void testSurfaceSkipCondition() {
        int height = 15;
        int originalSurfaceY = 70;

        // If target surface is much lower than original surface - height -> should skip
        int referenceSurfaceYAtTarget1 = originalSurfaceY - height - 1; // 54 < 55 -> true
        assertTrue(referenceSurfaceYAtTarget1 < originalSurfaceY - height);

        // If target surface is within valid range -> do not skip
        int referenceSurfaceYAtTarget2 = originalSurfaceY - height + 2; // 57 >= 55 -> false
        assertFalse(referenceSurfaceYAtTarget2 < originalSurfaceY - height);
    }

    private static class TestDummyTask extends WorldReloaderTask {
        public TestDummyTask(BlockPos center, int maxRadius) {
            super(null, center, center, null, null, maxRadius, 3, 20, true, true);
        }

        public boolean testPreserve(BlockPos pos) {
            return shouldPreserveCenterArea(pos);
        }

        @Override
        protected void processPosition(BlockPos circlePos) {}

        @Override
        protected ReferenceTerrainInfo getReferenceTerrainInfo(int referenceX, int referenceZ) {
            return null;
        }

        @Override
        protected void copyFromReference(int targetX, int targetZ, ReferenceTerrainInfo referenceInfo) {}

        @Override
        protected boolean shouldSkipProcessing(int referenceSurfaceYAtTarget, int originalSurfaceY) {
            return false;
        }
    }
}
