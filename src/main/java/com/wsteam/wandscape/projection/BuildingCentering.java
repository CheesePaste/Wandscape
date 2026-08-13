package com.wsteam.wandscape.projection;

import java.util.List;

import com.wsteam.wandscape.building.data.BlockOffset;
import com.wsteam.wandscape.building.data.BuildingConfig;

/**
 * 放置辅助：把建筑在 x/z 方向居中到准心瞄准的方块上。
 *
 * <p>建筑的 pattern 偏移原点（0,0,0）常落在建筑一角甚至包围盒外（如 bakery），
 * 若直接把瞄准方块当作 anchor，建筑会从准心往一侧延伸，放置体验差。
 * 这里算出建筑旋转后 x/z 包围盒中心的整数偏移，从瞄准方块减去即得到
 * 使建筑居中的 anchor。y 方向不做偏移（建筑仍落在瞄准位置的地面上）。
 */
public final class BuildingCentering {

    private BuildingCentering() {}

    /**
     * 返回旋转后建筑 x/z 包围盒中心的整数偏移 {@code [offsetX, offsetZ]}。
     *
     * <p>调用方应把该偏移从瞄准方块坐标减去（x 减 offsetX、z 减 offsetZ），
     * y 保持瞄准位置的 y 不变。旋转为 90° CCW，与 {@link BuildingRotation#rotateOffset}
     * 同向；中心点随旋转同步变换。空 pattern 时返回 {@code [0, 0]}（不偏移）。
     */
    public static int[] rotatedCenterOffsets(BuildingConfig config, int rotationSteps) {
        List<BlockOffset> pattern = config.pattern();
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockOffset off : pattern) {
            minX = Math.min(minX, off.x());
            maxX = Math.max(maxX, off.x());
            minZ = Math.min(minZ, off.z());
            maxZ = Math.max(maxZ, off.z());
        }
        if (minX > maxX) {
            return new int[] { 0, 0 };
        }

        // 未旋转时包围盒的几何中心（可为 .5）
        float cx = (minX + maxX) / 2.0f;
        float cz = (minZ + maxZ) / 2.0f;

        // 旋转中心点：x' = -z, z' = x（与 rotateOffset 同向）
        for (int i = 0; i < (rotationSteps & 3); i++) {
            float nx = -cz;
            cz = cx;
            cx = nx;
        }

        return new int[] { Math.round(cx), Math.round(cz) };
    }
}
