package com.wsteam.wandscape.content.npc.guard;

/**
 * 守卫区域：建筑包围盒水平（X/Z）扩展 {@code horizontalExpand} 格、Y 保持原包围盒高度的轴对齐盒。
 * 纯数据，无 MC 依赖（对应 MC 包围盒按 6 个 int 传入），可单测。
 *
 * <p>Y 不扩展是刻意设计：水平扩展用于索敌，Y 若上下扩展会锁到地下洞穴的怪物，光束打不到。
 */
public record GuardZone(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    /**
     * 从建筑包围盒生成守卫区域：仅 X/Z 各外扩 {@code horizontalExpand} 格，Y 不变。
     *
     * @param minX/minY/minZ/maxX/maxY/maxZ 建筑包围盒坐标（含边界）
     * @param horizontalExpand              水平外扩格数（≥0）
     */
    public static GuardZone of(int minX, int minY, int minZ,
                               int maxX, int maxY, int maxZ,
                               int horizontalExpand) {
        return new GuardZone(minX - horizontalExpand, minY, minZ - horizontalExpand,
                maxX + horizontalExpand, maxY, maxZ + horizontalExpand);
    }

    /** 世界坐标（实数为方便实体 AABB 判定）是否位于区域内（含边界）。 */
    public boolean contains(double x, double y, double z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ && y >= minY && y <= maxY;
    }
}
