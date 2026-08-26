package com.wsteam.wandscape.projection;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link BuildPlacement#resolve(BlockPos, Direction, BuildPlacement.SupportTest, int)}
 * 的纯逻辑单测：给定命中方块、命中面方向与可立足判定，断言解析出的建筑锚点。
 * 不依赖 MC 运行时（BlockPos/Direction 仅作为数据值传入）。
 *
 * <p>顶面命中的方向约定与 {@code VoxelShape#clip} 一致：自上而下的射线命中顶面时
 * {@code getDirection()} 返回 {@code Direction.UP}，{@code hitPos.relative(UP)} = 命中方块上方。
 */
class BuildPlacementTest {

    /** 只在指定 y 高度可立足（模拟「草方块地面」）。 */
    private static BuildPlacement.SupportTest groundAt(int y) {
        return pos -> pos.getY() == y;
    }

    @Test
    void hitStandableBlockKeepsAdjacentFacePlacement() {
        // 命中合规方块（草方块顶面）→ 贴面放置到其上方，行为与修复前一致
        BlockPos hit = new BlockPos(5, 60, 5);
        BlockPos result = BuildPlacement.resolve(hit, Direction.UP, groundAt(60), -64);
        assertEquals(new BlockPos(5, 61, 5), result);
    }

    @Test
    void hitStandableBlockHorizontalFaceKeepsWallPlacement() {
        // 命中墙的侧面 → 贴着侧面放置（保留贴墙/侧面放置）
        BlockPos hit = new BlockPos(5, 60, 5);
        BlockPos result = BuildPlacement.resolve(hit, Direction.NORTH, groundAt(60), -64);
        assertEquals(new BlockPos(5, 60, 4), result);
    }

    @Test
    void hitPlantOnGroundSnapsDownOneBlock() {
        // 花/草/蘑菇占 y=61，草方块在 y=60 → 锚点应落回草方块上方 y=61（花被建造时替换）
        BlockPos hit = new BlockPos(5, 61, 5);
        BlockPos result = BuildPlacement.resolve(hit, Direction.UP, groundAt(60), -64);
        assertEquals(new BlockPos(5, 61, 5), result);
    }

    @Test
    void hitPlantAboveGroundSnapsThroughAirToGround() {
        // 双层植物（y=62, y=61），地面 y=60 → 向下穿透空气到草方块上方 y=61
        BlockPos hit = new BlockPos(5, 62, 5);
        BlockPos result = BuildPlacement.resolve(hit, Direction.UP, groundAt(60), -64);
        assertEquals(new BlockPos(5, 61, 5), result);
    }

    @Test
    void hitLeavesAboveGroundSnapsToGroundBelow() {
        // 树叶（合规判定为不可立足）下有空气，地面在 y=60 → 向下吸附到地面
        BlockPos hit = new BlockPos(5, 90, 5);
        BlockPos result = BuildPlacement.resolve(hit, Direction.UP, groundAt(60), -64);
        assertEquals(new BlockPos(5, 61, 5), result);
    }

    @Test
    void noSupportFoundWithinBoundsFallsBackToAdjacentFace() {
        // 从命中位置向下直到 minY 都没有立足点 → 回退为原行为（贴面放置）
        BlockPos hit = new BlockPos(5, 63, 5);
        BuildPlacement.SupportTest none = pos -> false;
        BlockPos result = BuildPlacement.resolve(hit, Direction.UP, none, 63);
        assertEquals(new BlockPos(5, 64, 5), result);
    }

    @Test
    void supportAtWorldFloorIsFound() {
        // 立足点正好在世界最低建造高度（minY 含）也能被找到
        BlockPos hit = new BlockPos(5, 12, 5);
        BlockPos result = BuildPlacement.resolve(hit, Direction.UP, groundAt(10), 10);
        assertEquals(new BlockPos(5, 11, 5), result);
    }
}
