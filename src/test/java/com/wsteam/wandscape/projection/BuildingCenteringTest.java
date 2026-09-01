package com.wsteam.wandscape.projection;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.wsteam.wandscape.content.building.projection.BuildingCentering;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.wsteam.wandscape.content.building.data.BlockOffset;
import com.wsteam.wandscape.content.building.data.BuildingConfig;
import com.wsteam.wandscape.shared.data.AtmConfig;
import com.wsteam.wandscape.shared.data.RelaxConfig;
import com.wsteam.wandscape.shared.data.ServiceConfig;
import com.wsteam.wandscape.shared.data.ShopConfig;
import com.wsteam.wandscape.shared.data.WonderConfig;

@DisplayName("BuildingCentering")
class BuildingCenteringTest {

    private static BuildingConfig cfg(int[][] offsets) {
        List<BlockOffset> pattern = new ArrayList<>();
        for (int[] o : offsets) {
            pattern.add(new BlockOffset(o[0], o[1], o[2]));
        }
        return new BuildingConfig(
                "test", "Test", "", "basic",
                pattern, List.of(), List.of(), Map.of(),
                0, 0, 0,
                BuildingConfig.UnlockRequirement.NONE,
                null, null, null, null,
                WonderConfig.NONE, ShopConfig.NONE, ServiceConfig.NONE, RelaxConfig.NONE, AtmConfig.NONE,
                null, List.of(), false, false, List.of());
    }

    @Test
    @DisplayName("角点原点建筑：偏移使包围盒中心对准瞄准方块中心")
    void cornerOriginBuildingCentered() {
        // 类似 bakery：x[1,10] z[1,9]，原点在包围盒外的一角
        BuildingConfig bakery = cfg(new int[][] {
                {1, 0, 1}, {10, 0, 1}, {1, 0, 9}, {10, 0, 9}
        });
        assertArrayEquals(new int[] {5, 5},
                BuildingCentering.rotatedCenterOffsets(bakery, 0),
                "偶宽 x 中心 5.5 向下取整 5，使包围盒中心落在瞄准方块中心（aim+0.5），建筑围绕瞄准方块对称");
    }

    @Test
    @DisplayName("对称建筑（原点在中心）：无需偏移")
    void symmetricBuildingNoOffset() {
        // 类似 warehouse：x[-1,1] z[-1,1]，原点即中心
        BuildingConfig warehouse = cfg(new int[][] {
                {-1, 0, -1}, {1, 0, -1}, {-1, 0, 1}, {1, 0, 1}, {0, 0, 0}
        });
        assertArrayEquals(new int[] {0, 0},
                BuildingCentering.rotatedCenterOffsets(warehouse, 0));
    }

    @Test
    @DisplayName("旋转 90° CCW 后中心随旋转变换（x'=-z, z'=x）")
    void centerRotatesWithBuilding() {
        BuildingConfig bakery = cfg(new int[][] {
                {1, 0, 1}, {10, 0, 1}, {1, 0, 9}, {10, 0, 9}
        });
        // 未旋转中心 (5.5, 5.0)；1 步 → (-5.0, 5.5)；2 步 → (-5.5, -5.0)。floor 取整。
        assertArrayEquals(new int[] {-5, 5},
                BuildingCentering.rotatedCenterOffsets(bakery, 1));
        assertArrayEquals(new int[] {-6, -5},
                BuildingCentering.rotatedCenterOffsets(bakery, 2));
        // 4 步回到原位
        assertArrayEquals(new int[] {5, 5},
                BuildingCentering.rotatedCenterOffsets(bakery, 4));
    }

    @Test
    @DisplayName("全负偏移：中心取负值且正确取整")
    void negativeOnlyOffsets() {
        BuildingConfig neg = cfg(new int[][] {
                {-3, 0, -2}, {-1, 0, -2}, {-3, 0, 0}, {-1, 0, 0}
        });
        assertArrayEquals(new int[] {-2, -1},
                BuildingCentering.rotatedCenterOffsets(neg, 0));
    }

    @Test
    @DisplayName("空 pattern 兜底返回 [0,0]")
    void emptyPatternNoOffset() {
        assertArrayEquals(new int[] {0, 0},
                BuildingCentering.rotatedCenterOffsets(cfg(new int[0][0]), 3));
    }

    @Test
    @DisplayName("绕中心旋转：4 次旋转后建筑中心始终锚定在原瞄准方块")
    void rotateAroundCenterKeepsCenterFixed() {
        BuildingConfig bakery = cfg(new int[][] {
                {1, 0, 1}, {10, 0, 1}, {1, 0, 9}, {10, 0, 9}
        });
        int aimX = 10, aimZ = 20;
        int[] c0 = BuildingCentering.rotatedCenterOffsets(bakery, 0);
        int anchorX = aimX - c0[0];
        int anchorZ = aimZ - c0[1];

        for (int steps = 1; steps <= 4; steps++) {
            int[] oldC = BuildingCentering.rotatedCenterOffsets(bakery, steps - 1);
            int[] newC = BuildingCentering.rotatedCenterOffsets(bakery, steps);
            anchorX += oldC[0] - newC[0];
            anchorZ += oldC[1] - newC[1];

            int[] curC = BuildingCentering.rotatedCenterOffsets(bakery, steps & 3);
            assertEquals(aimX, anchorX + curC[0], "步骤 " + steps + "：建筑中心 x 必须保持瞄准方块");
            assertEquals(aimZ, anchorZ + curC[1], "步骤 " + steps + "：建筑中心 z 必须保持瞄准方块");
        }
        // 转满一圈 anchor 回到起点
        assertEquals(aimX - c0[0], anchorX);
        assertEquals(aimZ - c0[1], anchorZ);
    }

    @Test
    @DisplayName("偶宽建筑中心落在瞄准方块中心（修复偶宽偏移）")
    void evenWidthCenterAtAimBlockCenter() {
        // warehouse1 型：x[0,9] 宽 10（偶），z[0,8] 宽 9（奇）
        BuildingConfig warehouse = cfg(new int[][] {
                {0, 0, 0}, {9, 0, 0}, {0, 0, 8}, {9, 0, 8}
        });
        int aimX = 100, aimZ = 100;
        int[] c = BuildingCentering.rotatedCenterOffsets(warehouse, 0);
        int anchorX = aimX - c[0];
        int anchorZ = aimZ - c[1];

        // 偶宽轴：包围盒中心 = anchor + 4.5 必须等于 aim + 0.5（瞄准方块中心），建筑对称
        assertEquals(aimX + 0.5, anchorX + 4.5, "偶宽 x 轴包围盒中心应落在瞄准方块中心");
        // 奇宽轴：中心 = anchor + 4.0 = aim（瞄准方块即中心方块）
        assertEquals((double) aimZ, anchorZ + 4.0, "奇宽 z 轴中心方块应对准瞄准方块");
    }
}
