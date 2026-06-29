package com.wsteam.wandscape.engine.source.blueprint;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.wsteam.wandscape.building.data.BlockOffset;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.core.op.AtomicOp;
import com.wsteam.wandscape.shared.data.MaintenanceCostConfig;
import com.wsteam.wandscape.shared.data.ShopConfig;
import com.wsteam.wandscape.shared.data.WonderConfig;
import com.wsteam.wandscape.shared.data.ServiceConfig;
import com.wsteam.wandscape.core.task.BlueprintSteps;
import com.wsteam.wandscape.core.task.TaskSequence;
import com.wsteam.wandscape.core.types.BlockType;
import com.wsteam.wandscape.core.types.GridPos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DataDrivenSteps} — verify that
 * {@link BuildingConfig} pattern + block_mapping
 * correctly compiles to {@link TaskSequence}.
 */
class DataDrivenStepsTest {

    // ── Helpers ──

    private static BlockOffset off(int x, int y, int z) {
        return new BlockOffset(x, y, z);
    }

    private static Map<String, JsonElement> params(int x, int y, int z) {
        return Map.of("x", new JsonPrimitive(x), "y", new JsonPrimitive(y), "z", new JsonPrimitive(z));
    }

    // ── Test 1: single-block building ──

    @Test
    @DisplayName("single block building: one TransformOp.place at anchor")
    void singleBlockBuilding() {
        BuildingConfig cfg = new BuildingConfig(
                "earth_node", "大地节点", "node",
                List.of(off(0, 0, 0)),
                Map.of("0,0,0", "minecraft:lodestone"),
                1, 2, 0,
                BuildingConfig.QueueDef.DEFAULT,
                BuildingConfig.UnlockRequirement.NONE,
                null,  // no boundary
                null,  // no blueprint ref
                null,  // no nodeConfig
                MaintenanceCostConfig.NONE, null, WonderConfig.NONE, ShopConfig.NONE, ServiceConfig.NONE, 0, null, null
        );

        BlueprintSteps steps = DataDrivenSteps.fromConfig(cfg);
        TaskSequence seq = steps.generate(params(10, 64, 5));

        assertEquals(1, seq.size(), "single offset → 1 step");
        assertTrue(seq.get(0) instanceof AtomicOp.TransformOp);

        AtomicOp.TransformOp op = (AtomicOp.TransformOp) seq.get(0);
        assertEquals(new GridPos(10, 64, 5), op.target());
        assertEquals(new BlockType("minecraft:lodestone"), op.to());
        assertTrue(seq.label().contains("大地节点"));
    }

    // ── Test 2: multi-block pattern ──

    @Test
    @DisplayName("multi-block pattern: step per offset, coordinates correct")
    void multiBlockPattern() {
        BuildingConfig cfg = new BuildingConfig(
                "test_multi", "Test Multi", "basic",
                List.of(off(0, 0, 0), off(1, 0, 0), off(0, 1, 0)),
                Map.of(
                        "0,0,0", "minecraft:stone_bricks",
                        "1,0,0", "minecraft:oak_planks",
                        "0,1,0", "minecraft:glass"
                ),
                1, 0, 1,
                BuildingConfig.QueueDef.DEFAULT,
                BuildingConfig.UnlockRequirement.NONE,
                null,  // no boundary
                null,  // no blueprint ref
                null,  // no nodeConfig
                MaintenanceCostConfig.NONE, null, WonderConfig.NONE, ShopConfig.NONE, ServiceConfig.NONE, 0, null, null
        );

        BlueprintSteps steps = DataDrivenSteps.fromConfig(cfg);
        TaskSequence seq = steps.generate(params(20, 64, 30));

        assertEquals(3, seq.size());

        // offset (0,0,0) → stone_bricks at (20,64,30)
        AtomicOp.TransformOp op0 = (AtomicOp.TransformOp) seq.get(0);
        assertEquals(new GridPos(20, 64, 30), op0.target());
        assertEquals(new BlockType("minecraft:stone_bricks"), op0.to());

        // offset (1,0,0) → oak_planks at (21,64,30)
        AtomicOp.TransformOp op1 = (AtomicOp.TransformOp) seq.get(1);
        assertEquals(new GridPos(21, 64, 30), op1.target());
        assertEquals(new BlockType("minecraft:oak_planks"), op1.to());

        // offset (0,1,0) → glass at (20,65,30)
        AtomicOp.TransformOp op2 = (AtomicOp.TransformOp) seq.get(2);
        assertEquals(new GridPos(20, 65, 30), op2.target());
        assertEquals(new BlockType("minecraft:glass"), op2.to());
    }

    // ── Test 3: missing block_mapping key → warn + skip ──

    @Test
    @DisplayName("missing block_mapping: skipped, remaining steps still generated")
    void missingBlockMapping() {
        BuildingConfig cfg = new BuildingConfig(
                "test_missing", "Test Missing", "basic",
                List.of(off(0, 0, 0), off(1, 0, 0)),
                Map.of("1,0,0", "minecraft:stone"), // "0,0,0" intentionally missing
                1, 0, 0,
                BuildingConfig.QueueDef.DEFAULT,
                BuildingConfig.UnlockRequirement.NONE,
                null,  // no boundary
                null,  // no blueprint ref
                null,  // no nodeConfig
                MaintenanceCostConfig.NONE, null, WonderConfig.NONE, ShopConfig.NONE, ServiceConfig.NONE, 0, null, null
        );

        BlueprintSteps steps = DataDrivenSteps.fromConfig(cfg);
        TaskSequence seq = steps.generate(params(0, 70, 0));

        assertEquals(1, seq.size(), "missing offset skipped → only 1 valid step");
        AtomicOp.TransformOp op = (AtomicOp.TransformOp) seq.get(0);
        assertEquals(new GridPos(1, 70, 0), op.target());
        assertEquals(new BlockType("minecraft:stone"), op.to());
    }

    // ── Test 4: empty pattern ──

    @Test
    @DisplayName("empty pattern: zero steps")
    void emptyPattern() {
        BuildingConfig cfg = new BuildingConfig(
                "test_empty", "Test Empty", "basic",
                List.of(),
                Map.of(),
                0, 0, 0,
                BuildingConfig.QueueDef.DEFAULT,
                BuildingConfig.UnlockRequirement.NONE,
                null,  // no boundary
                null,  // no blueprint ref
                null,  // no nodeConfig
                MaintenanceCostConfig.NONE, null, WonderConfig.NONE, ShopConfig.NONE, ServiceConfig.NONE, 0, null, null
        );

        BlueprintSteps steps = DataDrivenSteps.fromConfig(cfg);
        TaskSequence seq = steps.generate(params(0, 70, 0));

        assertEquals(0, seq.size());
    }

    // ── Test 5: parsePos with missing keys → defaults to 0 ──

    @Test
    @DisplayName("parsePos missing keys: defaults to 0")
    void parsePosMissingKeys() {
        BuildingConfig cfg = singleBlockCfg();
        BlueprintSteps steps = DataDrivenSteps.fromConfig(cfg);

        TaskSequence seq = steps.generate(Map.of()); // no x/y/z at all

        assertEquals(1, seq.size());
        AtomicOp.TransformOp op = (AtomicOp.TransformOp) seq.get(0);
        assertEquals(GridPos.ORIGIN, op.target());
    }

    // ── Test 6: parsePos with NumberFormatException → ORIGIN ──

    @Test
    @DisplayName("parsePos bad input: fallback to ORIGIN")
    void parsePosBadInput() {
        BuildingConfig cfg = singleBlockCfg();
        BlueprintSteps steps = DataDrivenSteps.fromConfig(cfg);

        TaskSequence seq = steps.generate(Map.of("x", new JsonPrimitive("abc"), "y", new JsonPrimitive("64"), "z", new JsonPrimitive("5")));

        assertEquals(1, seq.size());
        AtomicOp.TransformOp op = (AtomicOp.TransformOp) seq.get(0);
        assertEquals(GridPos.ORIGIN, op.target());
    }

    // ── Test 7: label contains display name and anchor ──

    @Test
    @DisplayName("label includes display name and anchor coordinates")
    void labelContainsDisplayNameAndAnchor() {
        BuildingConfig cfg = singleBlockCfg();
        BlueprintSteps steps = DataDrivenSteps.fromConfig(cfg);

        TaskSequence seq = steps.generate(params(1, 2, 3));
        String label = seq.label();

        assertTrue(label.contains("Test Single"), "label should contain display name");
        assertTrue(label.contains("(1, 2, 3)"), "label should contain anchor toString");
    }

    // ── Test 8: parsePos with partial params ──

    @Test
    @DisplayName("parsePos partial params: missing keys default to 0")
    void parsePosPartialParams() {
        BuildingConfig cfg = singleBlockCfg();
        BlueprintSteps steps = DataDrivenSteps.fromConfig(cfg);

        // Only provide x, omit y and z
        TaskSequence seq = steps.generate(Map.of("x", new JsonPrimitive(42)));

        assertEquals(1, seq.size());
        AtomicOp.TransformOp op = (AtomicOp.TransformOp) seq.get(0);
        assertEquals(new GridPos(42, 0, 0), op.target());
    }

    // ── Test 9: bracket notation block states ──

    @Test
    @DisplayName("bracket notation: BlockType preserves state properties for engine")
    void bracketNotationBlockState() {
        BlockOffset off = off(0, 0, 0);
        Map<String, String> mapping = Map.of("0,0,0", "minecraft:oak_stairs[facing=east,half=top]");
        BuildingConfig cfg = new BuildingConfig(
                "test_stairs", "Stairs Test", "basic",
                List.of(off), mapping,
                1, 0, 0,
                BuildingConfig.QueueDef.DEFAULT,
                BuildingConfig.UnlockRequirement.NONE,
                null, null, null, MaintenanceCostConfig.NONE, null, WonderConfig.NONE, ShopConfig.NONE, ServiceConfig.NONE, 0, null, null  // no boundary, no blueprint ref, no nodeConfig
        );

        BlueprintSteps steps = DataDrivenSteps.fromConfig(cfg);
        TaskSequence seq = steps.generate(Map.of("x", new JsonPrimitive(10),
                "y", new JsonPrimitive(64), "z", new JsonPrimitive(10)));

        assertEquals(1, seq.size());
        AtomicOp.TransformOp op = (AtomicOp.TransformOp) seq.get(0);
        assertEquals(new GridPos(10, 64, 10), op.target());
        // BlockType must preserve full bracket notation for WandscapeBlockOps to parse
        assertEquals("minecraft:oak_stairs[facing=east,half=top]", op.to().id());
    }

    @Test
    @DisplayName("bracket notation: glass pane with connection states")
    void bracketNotationGlassPane() {
        BuildingConfig cfg = new BuildingConfig(
                "test_pane", "Pane Test", "basic",
                List.of(off(0, 0, 0)),
                Map.of("0,0,0", "minecraft:glass_pane[north=true,south=true,east=false,west=false]"),
                1, 0, 0,
                BuildingConfig.QueueDef.DEFAULT,
                BuildingConfig.UnlockRequirement.NONE,
                null, null, null, MaintenanceCostConfig.NONE, null, WonderConfig.NONE, ShopConfig.NONE, ServiceConfig.NONE, 0, null, null  // no boundary, no blueprint ref, no nodeConfig
        );

        BlueprintSteps steps = DataDrivenSteps.fromConfig(cfg);
        TaskSequence seq = steps.generate(Map.of("x", new JsonPrimitive(0),
                "y", new JsonPrimitive(70), "z", new JsonPrimitive(0)));

        assertEquals(1, seq.size());
        AtomicOp.TransformOp op = (AtomicOp.TransformOp) seq.get(0);
        assertEquals("minecraft:glass_pane[north=true,south=true,east=false,west=false]",
                op.to().id());
    }

    // ── helpers ──

    private static BuildingConfig singleBlockCfg() {
        return new BuildingConfig(
                "test_single", "Test Single", "basic",
                List.of(off(0, 0, 0)),
                Map.of("0,0,0", "minecraft:stone"),
                1, 0, 0,
                BuildingConfig.QueueDef.DEFAULT,
                BuildingConfig.UnlockRequirement.NONE,
                null,  // no boundary
                null,  // no blueprint ref
                null,  // no nodeConfig
                MaintenanceCostConfig.NONE, null, WonderConfig.NONE, ShopConfig.NONE, ServiceConfig.NONE, 0, null, null
        );
    }
}
