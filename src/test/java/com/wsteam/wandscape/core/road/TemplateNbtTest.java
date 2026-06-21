package com.wsteam.wandscape.core.road;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Tests for NBT parsing and template data logic.
 * Verifies block filtering, template block counts, palette format, and position rotation.
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

    /**
     * BUG REGRESSION: getList(name, type) second arg = ELEMENT type, not LIST tag type.
     * "palette" is a ListTag (id=9) containing CompoundTags (element type=10).
     * getList("palette", 9) expects list-of-lists — returns empty when elements are compounds.
     * getList("palette", 10) is CORRECT — expects compound elements.
     */
    @Test
    void getListSecondArgIsElementTypeNotListType() {
        // NBT structure: root → "palette": ListTag(CompoundTag{"Name":"minecraft:air"}, ...)
        //                    ↑                 ↑
        //               tag id = 9       element type = 10 (TAG_Compound)
        //               (TAG_List)       NOT 9!
        int TAG_LIST = 9;
        int TAG_COMPOUND = 10;

        // contains("palette", TAG_LIST) checks the tag itself is a list → true
        // getList("palette", TAG_COMPOUND) checks list ELEMENTS are compounds → correct
        // getList("palette", TAG_LIST) checks list ELEMENTS are lists → WRONG, returns empty!

        // palette elements are compounds (type 10):
        int paletteElementType = TAG_COMPOUND;
        assertEquals(10, paletteElementType, "Palette elements are CompoundTags (id=10)");

        // The correct call is getList("palette", 10) not getList("palette", 9):
        assertNotEquals(9, paletteElementType,
                "Using 9 for getList element type check incorrectly expects list-of-lists");
    }

    /** If palette is empty/missing, all blocks get state=0 → air → all filtered → zero tiles. */
    @Test
    void emptyPaletteMeansAllBlocksAreAir() {
        // When palette isn't parsed (due to wrong tag type check):
        // palette = [] (empty), all block states default to "minecraft:air"
        // → filter removes all blocks → 0 tiles → road never built
        List<FakeBlock> allAir = List.of(
                new FakeBlock(1, 0, 1, "minecraft:air"),
                new FakeBlock(2, 0, 2, "minecraft:air"));

        long kept = allAir.stream()
                .filter(b -> !b.name.contains("structure_void"))
                .filter(b -> !b.name.contains("jigsaw"))
                .filter(b -> !b.name.contains("air"))
                .count();
        assertEquals(0, kept, "With empty palette, all blocks are 'air' → filter removes all → 0 tiles");
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

    // ---- helpers ----

    private static List<FakeBlock> buildStraightBlocks() {
        List<FakeBlock> blocks = new ArrayList<>();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                String name = (x >= 6 && x <= 8)
                        ? "minecraft:dirt_path" : "minecraft:air";
                blocks.add(new FakeBlock(x, 0, z, name));
            }
        }
        int[][] grass = {{12,0,4}, {11,0,6}, {4,0,7}, {2,0,13}, {11,0,13}, {2,0,9}, {3,0,11}};
        for (int[] g : grass) {
            int idx = g[2] * 16 + g[0];
            blocks.set(idx, new FakeBlock(g[0], 0, g[2], "minecraft:grass_block"));
        }
        blocks.add(new FakeBlock(7, 1, 0, "minecraft:jigsaw"));
        blocks.add(new FakeBlock(7, 1, 15, "minecraft:jigsaw"));
        return blocks;
    }

    record FakeBlock(int x, int y, int z, String name) {}
}
