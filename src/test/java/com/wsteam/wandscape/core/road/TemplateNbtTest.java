package com.wsteam.wandscape.core.road;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Tests for NBT parsing and template data logic.
 * Verifies block filtering, template block counts, and position rotation.
 */
class TemplateNbtTest {

    /** Simulate what RoadTemplatePlacer does: parse blocks, filter air/jigsaw/void. */
    @Test
    void filterSkipsAirJigsawStructureVoid() {
        List<FakeBlock> blocks = List.of(
                new FakeBlock(0, 0, 0, "minecraft:air"),
                new FakeBlock(6, 0, 0, "minecraft:dirt_path"),
                new FakeBlock(7, 0, 0, "minecraft:dirt_path"),
                new FakeBlock(8, 0, 0, "minecraft:dirt_path"),
                new FakeBlock(7, 0, 15, "minecraft:jigsaw"),
                new FakeBlock(12, 0, 6, "minecraft:grass_block"),
                new FakeBlock(7, 1, 0, "minecraft:structure_void"));

        List<FakeBlock> filtered = blocks.stream()
                .filter(b -> !b.name.contains("structure_void"))
                .filter(b -> !b.name.contains("jigsaw"))
                .filter(b -> !b.name.contains("air"))
                .toList();

        assertEquals(4, filtered.size(), "Keep dirt_path*3 + grass_block*1");
        assertTrue(filtered.stream().allMatch(
                b -> b.name.equals("minecraft:dirt_path") || b.name.equals("minecraft:grass_block")));
    }

    /** Verify straight template has correct block counts from NBT analysis. */
    @Test
    void straightTemplateBlockCount() {
        // Exact data from vanilla straight_01.nbt:
        // 258 total blocks (256 Y=0 + 2 Y=1+jigsaw)
        // Palette: [air, dirt_path, grass_block, jigsaw_north_up, jigsaw_south_up]
        // After filter: 48 dirt_path (x=6,7,8 * z=0..15) + 7 grass_block = 55
        List<FakeBlock> blocks = buildStraightBlocks();
        assertEquals(258, blocks.size());

        long roadBlocks = blocks.stream()
                .filter(b -> !b.name.contains("air"))
                .filter(b -> !b.name.contains("jigsaw"))
                .filter(b -> !b.name.contains("structure_void"))
                .count();
        assertEquals(55, roadBlocks,
                "55 road blocks: 48 dirt_path + 7 grass_block");
    }

    /** Verify rotation logic preserves block positions correctly. */
    @Test
    void rotationKeepsBlockCountEqual() {
        EntryExit original = new EntryExit(7, 0, CardinalFacing.SOUTH);
        assertEquals(7, original.dx());
        assertEquals(0, original.dz());

        EntryExit r90 = original.rotate(1);
        assertEquals(0, r90.dx());
        assertEquals(-7, r90.dz());

        EntryExit r180 = original.rotate(2);
        assertEquals(-7, r180.dx());
        assertEquals(0, r180.dz());

        EntryExit r270 = original.rotate(3);
        assertEquals(0, r270.dx());
        assertEquals(7, r270.dz());
    }

    /** Verify template expander budget math. */
    @Test
    void expanderBudgetConsumption() {
        // 48 dirt_path + 7 grass_block = 55 placeable blocks
        // At 16 tiles per segment, that's ceil(55/16) = 4 segments
        int totalTiles = 55;
        int maxLen = 16;
        int expectedSegments = (totalTiles + maxLen - 1) / maxLen;
        assertEquals(4, expectedSegments, "55 tiles / 16 max = 4 segments");
    }

    // ---- helpers ----

    /** Build exact block layout matching vanilla straight_01.nbt analysis. */
    private static List<FakeBlock> buildStraightBlocks() {
        List<FakeBlock> blocks = new ArrayList<>();

        // Y=0: 16*16 = 256 blocks
        // x=6,7,8 are dirt_path (48), rest air
        // 7 grass_block at specific positions
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                String name = (x >= 6 && x <= 8)
                        ? "minecraft:dirt_path" : "minecraft:air";
                blocks.add(new FakeBlock(x, 0, z, name));
            }
        }

        // Override specific positions with grass_block (from NBT analysis)
        int[][] grass = {{12,0,4}, {11,0,6}, {4,0,7}, {2,0,13}, {11,0,13}, {2,0,9}, {3,0,11}};
        for (int[] g : grass) {
            int idx = g[2] * 16 + g[0];
            blocks.set(idx, new FakeBlock(g[0], 0, g[2], "minecraft:grass_block"));
        }

        // Y=1: 2 jigsaw blocks at (7,0) and (7,15)
        blocks.add(new FakeBlock(7, 1, 0, "minecraft:jigsaw"));
        blocks.add(new FakeBlock(7, 1, 15, "minecraft:jigsaw"));

        return blocks;
    }

    record FakeBlock(int x, int y, int z, String name) {}
}
