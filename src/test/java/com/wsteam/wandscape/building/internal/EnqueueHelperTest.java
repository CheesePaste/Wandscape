package com.wsteam.wandscape.building.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wsteam.wandscape.building.data.BlockOffset;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.building.data.BuildingConfig.BlueprintRef;
import com.wsteam.wandscape.building.data.BuildingConfig.BoundaryBox;
import com.wsteam.wandscape.building.data.BuildingConfig.DecorationEntity;
import com.wsteam.wandscape.shared.api.ElementApi;
import com.wsteam.wandscape.shared.data.AtmConfig;
import com.wsteam.wandscape.shared.data.ElementType;
import com.wsteam.wandscape.shared.data.RelaxConfig;
import com.wsteam.wandscape.shared.data.ShopConfig;
import com.wsteam.wandscape.shared.data.WonderConfig;
import com.wsteam.wandscape.shared.data.ServiceConfig;
import com.wsteam.wandscape.shared.data.WorkItem;
import com.wsteam.wandscape.shared.registry.WandscapeApis;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("EnqueueHelper.computeClearOffsets")
class EnqueueHelperTest {

    /** buildWorkItem 解析 material_list 需要 ElementApi；测试环境注册一个全空映射。 */
    @BeforeAll
    static void registerElementApi() {
        WandscapeApis.setElementApi(new ElementApi() {
            @Override public ElementType fromId(String id) { return ElementType.fromId(id); }
            @Override public boolean hasElementMapping(String blockOrItemId) { return false; }
            @Override public Map<ElementType, Long> getBuildCost(BlockState block) { return Map.of(); }
            @Override public Map<ElementType, Long> getDecomposeYield(BlockState block) { return Map.of(); }
            @Override public boolean isDecomposable(BlockState block) { return false; }
            @Override public Map<ElementType, Long> getBuildCost(ItemStack stack) { return Map.of(); }
            @Override public Map<ElementType, Long> getDecomposeYield(ItemStack stack) { return Map.of(); }
            @Override public boolean isDecomposable(ItemStack stack) { return false; }
        });
    }

    private static BlockOffset off(int x, int y, int z) {
        return new BlockOffset(x, y, z);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> makeBlockMapping(String... kvPairs) {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kvPairs.length; i += 2) {
            m.put(kvPairs[i], kvPairs[i + 1]);
        }
        return m;
    }

    private record PaletteParts(List<String> palette, List<Integer> indices) {}

    /** Convert a legacy offset→blockstate map to palette + pattern-aligned indices. */
    private static PaletteParts toPalette(List<BlockOffset> pattern, Map<String, String> mapping) {
        List<String> palette = new java.util.ArrayList<>();
        Map<String, Integer> idx = new java.util.HashMap<>();
        List<Integer> indices = new java.util.ArrayList<>();
        for (BlockOffset off : pattern) {
            String bid = mapping.get(off.toKey());
            if (bid == null) throw new AssertionError("no block mapped for " + off.toKey());
            Integer i = idx.get(bid);
            if (i == null) {
                i = palette.size();
                palette.add(bid);
                idx.put(bid, i);
            }
            indices.add(i);
        }
        return new PaletteParts(palette, indices);
    }

    @Test
    @DisplayName("town_hall: clear entire boundary box (18 positions), anchor included")
    void townHallFullBox() {
        List<BlockOffset> pattern = List.of(
                off(-1, 0, -1), off(-1, 0, 0), off(-1, 0, 1),
                off(0, 0, -1),                     off(0, 0, 1),
                off(1, 0, -1),  off(1, 0, 0),  off(1, 0, 1),
                off(-1, 1, -1), off(-1, 1, 1), off(1, 1, -1), off(1, 1, 1));
        PaletteParts pal = toPalette(pattern, makeBlockMapping(
                "-1,0,-1", "minecraft:stone_bricks",
                "-1,0,0", "minecraft:stone_bricks",
                "-1,0,1", "minecraft:stone_bricks",
                "0,0,-1", "minecraft:stone_bricks",
                "0,0,1", "minecraft:stone_bricks",
                "1,0,-1", "minecraft:stone_bricks",
                "1,0,0", "minecraft:stone_bricks",
                "1,0,1", "minecraft:stone_bricks",
                "-1,1,-1", "minecraft:oak_log",
                "-1,1,1", "minecraft:oak_log",
                "1,1,-1", "minecraft:oak_log",
                "1,1,1", "minecraft:oak_log"));
        BuildingConfig cfg = new BuildingConfig(
                "town_hall", "Test", "", "basic",
                pattern, pal.palette(), pal.indices(),
                Map.of(), /* blockNbt */
                5, 3, 2,
                BuildingConfig.QueueDef.DEFAULT,
                BuildingConfig.UnlockRequirement.NONE,
                new BoundaryBox(off(-1, 0, -1), off(1, 1, 1)),
                null,
                null, null, WonderConfig.NONE, ShopConfig.NONE, ServiceConfig.NONE, RelaxConfig.NONE, AtmConfig.NONE, null, List.of(), false, false, List.of()  // nodeConfig, firstFree, deprecated, entities
        );

        JsonElement result = EnqueueHelper.computeClearOffsets(cfg);
        assertTrue(result.isJsonArray());
        JsonArray arr = result.getAsJsonArray();

        // Boundary: 3×2×3 = 18 — anchor is vanilla now, included in clear
        assertEquals(18, arr.size(), "clear entire boundary box");

        java.util.Set<String> clearKeys = collectKeys(arr);

        for (int x = -1; x <= 1; x++) {
            for (int y = 0; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    String key = x + "," + y + "," + z;
                    assertTrue(clearKeys.contains(key),
                            "boundary position " + key + " must be in clear_offsets");
                }
            }
        }
    }

    @Test
    @DisplayName("single-block building: anchor-only boundary → clear anchor")
    void singleBlockClear() {
        List<BlockOffset> pattern = List.of(off(0, 0, 0));
        PaletteParts pal = toPalette(pattern, makeBlockMapping("0,0,0", "minecraft:lodestone"));
        BuildingConfig cfg = new BuildingConfig(
                "earth_node", "Test", "", "node",
                pattern, pal.palette(), pal.indices(),
                Map.of(), /* blockNbt */
                1, 2, 0,
                BuildingConfig.QueueDef.DEFAULT,
                BuildingConfig.UnlockRequirement.NONE,
                new BoundaryBox(off(0, 0, 0), off(0, 0, 0)),
                null,
                null, null, WonderConfig.NONE, ShopConfig.NONE, ServiceConfig.NONE, RelaxConfig.NONE, AtmConfig.NONE, null, List.of(), false, false, List.of()  // nodeConfig, firstFree, deprecated, entities
        );

        JsonElement result = EnqueueHelper.computeClearOffsets(cfg);
        JsonArray arr = result.getAsJsonArray();
        assertEquals(1, arr.size(),
                "single-block: anchor is vanilla → included in clear");
    }

    @Test
    @DisplayName("large 3×3×3 boundary: all 27 positions cleared")
    void largeBoundaryFullBox() {
        List<BlockOffset> pattern = List.of(off(0, 0, 0));
        PaletteParts pal = toPalette(pattern, makeBlockMapping("0,0,0", "minecraft:stone"));
        BuildingConfig cfg = new BuildingConfig(
                "test_large", "Test", "", "basic",
                pattern, pal.palette(), pal.indices(),
                Map.of(), /* blockNbt */
                1, 0, 0,
                BuildingConfig.QueueDef.DEFAULT,
                BuildingConfig.UnlockRequirement.NONE,
                new BoundaryBox(off(-1, -1, -1), off(1, 1, 1)),
                null,
                null, null, WonderConfig.NONE, ShopConfig.NONE, ServiceConfig.NONE, RelaxConfig.NONE, AtmConfig.NONE, null, List.of(), false, false, List.of()  // nodeConfig, firstFree, deprecated, entities
        );

        JsonElement result = EnqueueHelper.computeClearOffsets(cfg);
        JsonArray arr = result.getAsJsonArray();

        assertEquals(27, arr.size(), "clear entire 27-cell box");

        java.util.Set<String> clearKeys = collectKeys(arr);

        for (int x = -1; x <= 1; x++)
            for (int y = -1; y <= 1; y++)
                for (int z = -1; z <= 1; z++) {
                    String key = x + "," + y + "," + z;
                    assertTrue(clearKeys.contains(key), key + " must be in clear_offsets");
                }
    }

    private static java.util.Set<String> collectKeys(JsonArray arr) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (JsonElement el : arr) {
            JsonArray pos = el.getAsJsonArray();
            keys.add(pos.get(0).getAsInt() + "," + pos.get(1).getAsInt() + "," + pos.get(2).getAsInt());
        }
        return keys;
    }

    @Test
    @DisplayName("blocksFromPalette: 按 pattern 顺序配对索引——旋转后每格仍是自己的方块")
    void blocksFromPalettePairsPatternParallel() {
        // pattern 顺序故意不打乱成排序序（y=1 排在 y=0 前）：若把 blockIndices
        // 配给排序后 offsets 会把方块张冠李戴。调色板索引必须与 pattern 原始顺序平行。
        List<BlockOffset> pattern = List.of(off(0, 1, 0), off(0, 0, 0), off(1, 0, 0));
        List<String> palette = List.of("minecraft:stone", "minecraft:oak_log");
        List<Integer> indices = List.of(0, 1, 0); // (0,1,0)=stone, (0,0,0)=oak_log, (1,0,0)=stone

        // 旋转 1 步 CCW: (x,y,z) → (-z,y,x)
        JsonObject m = EnqueueHelper.blocksFromPalette(pattern, palette, indices, 1);

        assertEquals(3, m.size());
        assertEquals("minecraft:stone", m.get("0,1,0").getAsString());   // (0,1,0)→(0,1,0)
        assertEquals("minecraft:oak_log", m.get("0,0,0").getAsString());  // (0,0,0)→(0,0,0)
        assertEquals("minecraft:stone", m.get("0,0,1").getAsString());    // (1,0,0)→(0,0,1)

        // 0 步旋转：原样返回，索引仍与 pattern 平行
        JsonObject m0 = EnqueueHelper.blocksFromPalette(pattern, palette, indices, 0);
        assertEquals("minecraft:stone", m0.get("0,1,0").getAsString());
        assertEquals("minecraft:oak_log", m0.get("0,0,0").getAsString());
        assertEquals("minecraft:stone", m0.get("1,0,0").getAsString());
    }

    @Test
    @DisplayName("buildWorkItem: entities 参数透传 + 旋转")
    @org.junit.jupiter.api.Disabled("buildWorkItem 全流程触发 BuiltInRegistries（rotateBlockStateString），需要 MC Bootstrap，纯 JUnit 环境不可跑——留待集成测试")
    void entitiesPassThroughAndRotate() {
        List<BlockOffset> pattern = List.of(off(0, 0, 0));
        PaletteParts pal = toPalette(pattern, makeBlockMapping("0,0,0", "minecraft:stone"));
        BuildingConfig cfg = new BuildingConfig(
                "gallery", "Gallery", "", "custom",
                pattern, pal.palette(), pal.indices(),
                Map.of(), /* blockNbt */
                0, 0, 0,
                BuildingConfig.QueueDef.DEFAULT,
                BuildingConfig.UnlockRequirement.NONE,
                new BoundaryBox(off(0, 0, 0), off(0, 0, 0)),
                new BlueprintRef("build:clear_and_build",
                        Map.of("offsets", "$pattern", "blocks", "$block_mapping", "name", "$display_name")),
                null, null, WonderConfig.NONE, ShopConfig.NONE, ServiceConfig.NONE, RelaxConfig.NONE, AtmConfig.NONE, null, List.of(), false, false,
                List.of(new DecorationEntity(off(1, 2, 0), "minecraft:item_frame", "north", "b64"))
        );

        // 无旋转：entities 原样透传
        WorkItem w0 = EnqueueHelper.buildWorkItem(cfg, new BlockPos(10, 64, 10), "custom", 1, null, null, 0);
        JsonArray e0 = w0.params().get("entities").getAsJsonArray();
        assertEquals(1, e0.size());
        JsonObject ent0 = e0.get(0).getAsJsonObject();
        assertEquals(1, ent0.get("offset").getAsJsonArray().get(0).getAsInt());
        assertEquals(2, ent0.get("offset").getAsJsonArray().get(1).getAsInt());
        assertEquals(0, ent0.get("offset").getAsJsonArray().get(2).getAsInt());
        assertEquals("minecraft:item_frame", ent0.get("type").getAsString());
        assertEquals("north", ent0.get("facing").getAsString());
        assertEquals("b64", ent0.get("nbt").getAsString());

        // 旋转 1 步：offset [1,2,0] → [0,2,1]，facing north → east
        WorkItem w1 = EnqueueHelper.buildWorkItem(cfg, new BlockPos(10, 64, 10), "custom", 1, null, null, 1);
        JsonArray e1 = w1.params().get("entities").getAsJsonArray();
        JsonObject ent1 = e1.get(0).getAsJsonObject();
        assertEquals(0, ent1.get("offset").getAsJsonArray().get(0).getAsInt());
        assertEquals(2, ent1.get("offset").getAsJsonArray().get(1).getAsInt());
        assertEquals(1, ent1.get("offset").getAsJsonArray().get(2).getAsInt());
        assertEquals("east", ent1.get("facing").getAsString());
        assertEquals("b64", ent1.get("nbt").getAsString());
    }
}
