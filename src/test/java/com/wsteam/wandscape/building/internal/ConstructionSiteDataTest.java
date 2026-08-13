package com.wsteam.wandscape.building.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import com.wsteam.wandscape.building.data.BlockOffset;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.building.data.BuildingConfig.BoundaryBox;
import com.wsteam.wandscape.building.network.ConstructionSiteDataPacket;
import com.wsteam.wandscape.shared.api.ElementApi;
import com.wsteam.wandscape.shared.data.AtmConfig;
import com.wsteam.wandscape.shared.data.ElementType;
import com.wsteam.wandscape.shared.data.RelaxConfig;
import com.wsteam.wandscape.shared.data.ServiceConfig;
import com.wsteam.wandscape.shared.data.ShopConfig;
import com.wsteam.wandscape.shared.data.WonderConfig;
import com.wsteam.wandscape.shared.registry.WandscapeApis;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ConstructionSiteData: 材料计数与时间估算")
class ConstructionSiteDataTest {

    /** 只给 stone_bricks 注册元素映射；oak_log/air 视为免费材料。 */
    @BeforeAll
    static void registerElementApi() {
        WandscapeApis.setElementApi(new ElementApi() {
            @Override public ElementType fromId(String id) { return ElementType.fromId(id); }
            @Override public boolean hasElementMapping(String blockOrItemId) {
                return "minecraft:stone_bricks".equals(blockOrItemId);
            }
            @Override public Map<ElementType, Long> getBuildCost(BlockState block) { return Map.of(); }
            @Override public Map<ElementType, Long> getDecomposeYield(BlockState block) { return Map.of(); }
            @Override public boolean isDecomposable(BlockState block) { return false; }
            @Override public Map<ElementType, Long> getBuildCost(ItemStack stack) { return Map.of(); }
            @Override public Map<ElementType, Long> getDecomposeYield(ItemStack stack) { return Map.of(); }
            @Override public boolean isDecomposable(ItemStack stack) { return false; }
        });
    }

    private static BlockOffset off(int x, int y, int z) { return new BlockOffset(x, y, z); }

    @Test
    @DisplayName("computeMaterialCounts: 去重 + 剥 state + 跳过 air 与无映射方块")
    void materialCounts() {
        List<BlockOffset> pattern = List.of(off(0, 0, 0), off(0, 0, 1), off(1, 0, 0), off(1, 0, 1), off(0, 1, 0), off(0, 1, 1));
        BuildingConfig cfg = new BuildingConfig(
                "test_build", "Test", "", "basic",
                pattern,
                List.of("minecraft:stone_bricks", "minecraft:stone_bricks[facing=north]", "minecraft:oak_log", "minecraft:air"),
                List.of(0, 0, 1, 0, 2, 3),
                Map.of(),
                5, 3, 2,
                BuildingConfig.QueueDef.DEFAULT,
                BuildingConfig.UnlockRequirement.NONE,
                new BoundaryBox(off(0, 0, 0), off(1, 1, 1)),
                null, null, null,
                WonderConfig.NONE, ShopConfig.NONE,
                ServiceConfig.NONE, RelaxConfig.NONE, AtmConfig.NONE,
                null, List.of(), false, false, List.of());

        Map<String, Integer> counts = EnqueueHelper.computeMaterialCounts(cfg);
        assertEquals(1, counts.size(), "only element-mapped blocks are counted");
        assertEquals(4, counts.get("minecraft:stone_bricks"),
                "3 块纯 stone_bricks + 1 块带 state 去重 → 4；oak_log/air 被跳过");
    }

    @Test
    @DisplayName("Estimate.of: 全备齐 / 无工作站 / 并行工作站 / 剩余方块口径")
    void estimateBranches() {
        // 全备齐：开工即 0，完工 = placeCD × remainingBlocks（剩余 100 块）
        ConstructionSiteDataPacket.Estimate ready =
                ConstructionSiteDataPacket.Estimate.of(0, 100, 0, 10, 1);
        assertTrue(ready.canEstimate());
        assertEquals(0, ready.startTicks());
        assertEquals(100, ready.completeTicks());

        // 有缺口但无工作站在做 → 不可估算
        ConstructionSiteDataPacket.Estimate idle =
                ConstructionSiteDataPacket.Estimate.of(50, 100, 0, 10, 1);
        assertFalse(idle.canEstimate());

        // 缺口 50、2 个工作站：start = ceil(50×10/2)=250；complete = 250 + 1×100
        ConstructionSiteDataPacket.Estimate parallel =
                ConstructionSiteDataPacket.Estimate.of(50, 100, 2, 10, 1);
        assertTrue(parallel.canEstimate());
        assertEquals(250, parallel.startTicks());
        assertEquals(350, parallel.completeTicks());

        // 单站：start = ceil(50×10/1)=500；complete = 500 + 100
        ConstructionSiteDataPacket.Estimate single =
                ConstructionSiteDataPacket.Estimate.of(50, 100, 1, 10, 1);
        assertEquals(500, single.startTicks());
        assertEquals(600, single.completeTicks());

        // 已建 60 块、剩 40 块：完工按剩余算 → 500 + 40，而非总方块 100
        ConstructionSiteDataPacket.Estimate remaining =
                ConstructionSiteDataPacket.Estimate.of(50, 40, 1, 10, 1);
        assertEquals(500, remaining.startTicks());
        assertEquals(540, remaining.completeTicks());
    }
}
