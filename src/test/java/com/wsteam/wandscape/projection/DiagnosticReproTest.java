package com.wsteam.wandscape.projection;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.wsteam.wandscape.building.data.BlockOffset;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.shared.data.AtmConfig;
import com.wsteam.wandscape.shared.data.RelaxConfig;
import com.wsteam.wandscape.shared.data.ServiceConfig;
import com.wsteam.wandscape.shared.data.ShopConfig;
import com.wsteam.wandscape.shared.data.WonderConfig;

@DisplayName("DiagnosticRepro")
class DiagnosticReproTest {

    private static BuildingConfig cfg(int[][] offsets) {
        List<BlockOffset> pattern = new ArrayList<>();
        for (int[] o : offsets) {
            pattern.add(new BlockOffset(o[0], o[1], o[2]));
        }
        return new BuildingConfig(
                "test", "Test", "", "basic",
                pattern, List.of(), List.of(), Map.of(),
                0, 0, 0,
                BuildingConfig.QueueDef.DEFAULT,
                BuildingConfig.UnlockRequirement.NONE,
                null, null, null, null,
                WonderConfig.NONE, ShopConfig.NONE, ServiceConfig.NONE, RelaxConfig.NONE, AtmConfig.NONE,
                null, List.of(), false, false, List.of());
    }

    private static BlockOffset rotate(int x, int z, int steps) {
        for (int i = 0; i < (steps & 3); i++) {
            int nx = -z;
            z = x;
            x = nx;
        }
        return new BlockOffset(x, 0, z);
    }

    /** Compute the world-space bbox center of the rotated building, and its offset from the aim. */
    private static double[] centerOffset(BuildingConfig config, int anchorX, int anchorZ,
                                         int aimX, int aimZ, int steps) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockOffset off : config.pattern()) {
            BlockOffset r = rotate(off.x(), off.z(), steps);
            minX = Math.min(minX, anchorX + r.x());
            maxX = Math.max(maxX, anchorX + r.x());
            minZ = Math.min(minZ, anchorZ + r.z());
            maxZ = Math.max(maxZ, anchorZ + r.z());
        }
        return new double[] { (minX + maxX) / 2.0 - aimX, (minZ + maxZ) / 2.0 - aimZ };
    }

    @Test
    @DisplayName("偶宽建筑：旋转一圈中心始终贴近瞄准方块（修复前 -0.5 偏位）")
    void evenWidthCenterStaysOnAimAcrossRotation() {
        // bakery-like: x[1,10] z[1,9] — even width in x, odd in z
        BuildingConfig bakery = cfg(new int[][] {
                {1, 0, 1}, {10, 0, 1}, {1, 0, 9}, {10, 0, 9}
        });
        int aimX = 100, aimZ = 100;
        int[] c0 = BuildingCentering.rotatedCenterOffsets(bakery, 0);
        int anchorX = aimX - c0[0];
        int anchorZ = aimZ - c0[1];

        // 修复后 rot0：偶宽 x 轴中心落在瞄准方块中心（aim+0.5），不再偏 -0.5
        double[] off0 = centerOffset(bakery, anchorX, anchorZ, aimX, aimZ, 0);
        assertEquals(0.5, off0[0], 1e-6, "rot0 偶宽轴中心应在瞄准方块中心");
        assertEquals(0.0, off0[1], 1e-6, "rot0 奇宽轴中心方块应对准瞄准方块");

        // 模拟 rotate() 补偿：绕中心旋转一圈，中心应始终贴近瞄准方块（|offset|<=0.5）
        for (int steps = 1; steps <= 4; steps++) {
            int[] oldC = BuildingCentering.rotatedCenterOffsets(bakery, steps - 1);
            int[] newC = BuildingCentering.rotatedCenterOffsets(bakery, steps);
            anchorX += oldC[0] - newC[0];
            anchorZ += oldC[1] - newC[1];

            double[] off = centerOffset(bakery, anchorX, anchorZ, aimX, aimZ, steps & 3);
            assertTrue(Math.abs(off[0]) <= 0.5 && Math.abs(off[1]) <= 0.5,
                    "步骤 " + steps + " 中心偏移超出半格: " + off[0] + "," + off[1]);
        }
    }

    @Test
    @DisplayName("偶宽建筑：旋转一圈 anchor 回到起点")
    void anchorReturnsAfterFullRotation() {
        BuildingConfig bakery = cfg(new int[][] {
                {1, 0, 1}, {10, 0, 1}, {1, 0, 9}, {10, 0, 9}
        });
        int aimX = 100, aimZ = 100;
        int[] c0 = BuildingCentering.rotatedCenterOffsets(bakery, 0);
        int anchorX = aimX - c0[0];
        int anchorZ = aimZ - c0[1];
        int startX = anchorX, startZ = anchorZ;

        for (int steps = 1; steps <= 4; steps++) {
            int[] oldC = BuildingCentering.rotatedCenterOffsets(bakery, steps - 1);
            int[] newC = BuildingCentering.rotatedCenterOffsets(bakery, steps);
            anchorX += oldC[0] - newC[0];
            anchorZ += oldC[1] - newC[1];
        }
        assertEquals(startX, anchorX);
        assertEquals(startZ, anchorZ);
    }
}
