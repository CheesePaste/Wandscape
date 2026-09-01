package com.wsteam.wandscape.tourist.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wsteam.wandscape.content.tourist.internal.TouristMoveGoal;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * {@link TouristMoveGoal#sameHorizontal} 回归测试：防卡死判定只看 x/z 是否推进，y 上下跳动不计。
 *
 * <p>旧实现用三维 {@code pos.distSqr(lastPos) < 1.0} 判「未移动」——游客贴墙反复跳时 y 每跳
 * 跨越整块（distSqr=1）会被误判为「在移动」，防卡死永不触发、一直弹跳。修复后 y 波动不再影响判定。
 */
class TouristMoveGoalStuckTest {

    /** x/z 不动、仅 y 变化（原地跳跃）→ 视为卡死（回归：旧实现会因 y 波动判定为移动而漏报）。 */
    @Test
    void yOnlyChangeIsStillStuck() {
        assertTrue(TouristMoveGoal.sameHorizontal(new BlockPos(1, 5, 2), new BlockPos(1, 6, 2)));
        assertTrue(TouristMoveGoal.sameHorizontal(new BlockPos(1, 5, 2), new BlockPos(1, 9, 2)));
    }

    /** x/z 完全不动且 y 也不动 → 卡死。 */
    @Test
    void identicalPositionIsStuck() {
        assertTrue(TouristMoveGoal.sameHorizontal(new BlockPos(3, 1, 7), new BlockPos(3, 1, 7)));
    }

    /** x 前进一格 → 视为移动（重置卡死计数）。 */
    @Test
    void xMovementResetsStuck() {
        assertFalse(TouristMoveGoal.sameHorizontal(new BlockPos(3, 4, 7), new BlockPos(4, 4, 7)));
        assertFalse(TouristMoveGoal.sameHorizontal(new BlockPos(3, 4, 7), new BlockPos(2, 5, 7)));
    }

    /** z 前进一格 → 视为移动。 */
    @Test
    void zMovementResetsStuck() {
        assertFalse(TouristMoveGoal.sameHorizontal(new BlockPos(3, 4, 7), new BlockPos(3, 4, 8)));
    }

    /** 对角移动（x/z 都变，哪怕 y 不变）→ 视为移动。 */
    @Test
    void diagonalMovementResetsStuck() {
        assertFalse(TouristMoveGoal.sameHorizontal(new BlockPos(3, 4, 7), new BlockPos(4, 4, 8)));
    }
}