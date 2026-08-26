package com.wsteam.wandscape.tourist.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * {@link TouristMoveGoal#farPoisExcluding} 回归测试：POI 防卡死（A：作废失败目标）重选时排除失败的 POI，
 * 防止防卡死传送后反复重选同一个失败/屋顶 POI、再次经过同一个卡死点。
 */
class TouristMoveGoalPoiSelectTest {

    private static final BlockPos HERE = new BlockPos(0, 64, 0);

    /** 失败的远 POI 被排除，其余远 POI 保留为候选，近 POI 不作为优先候选。 */
    @Test
    void excludesFailedFarPoi() {
        List<BlockPos> pois = List.of(
                new BlockPos(10, 64, 0),  // far
                new BlockPos(-8, 64, 3),  // far
                new BlockPos(1, 64, 0));  // near
        List<BlockPos> far = TouristMoveGoal.farPoisExcluding(pois, HERE, 25, pois.get(0));
        assertFalse(far.contains(pois.get(0)), "失败的远 POI 必须被排除");
        assertTrue(far.contains(pois.get(1)), "其余远 POI 保留");
        assertFalse(far.contains(pois.get(2)), "近 POI 不作为优先候选");
    }

    /** 远距离候选全是被排除的失败目标 → 退回「排除失败目标后的全部 POI」。 */
    @Test
    void fallsBackToNonExcludedWhenAllFarExcluded() {
        BlockPos failed = new BlockPos(10, 64, 0);
        List<BlockPos> pois = List.of(failed, new BlockPos(1, 64, 0), new BlockPos(-1, 64, 0));
        List<BlockPos> far = TouristMoveGoal.farPoisExcluding(pois, HERE, 25, failed);
        assertFalse(far.contains(failed), "失败的 POI 仍被排除");
        assertEquals(2, far.size(), "退回排除失败 POI 后的全部 POI");
    }

    /** exclude 为 null（正常重选）→ 行为与旧实现一致：只取远 POI。 */
    @Test
    void noExcludeKeepsOriginalBehavior() {
        List<BlockPos> pois = List.of(new BlockPos(10, 64, 0), new BlockPos(-8, 64, 3), new BlockPos(1, 64, 0));
        List<BlockPos> far = TouristMoveGoal.farPoisExcluding(pois, HERE, 25, null);
        assertEquals(2, far.size(), "不排除时只取远 POI");
        assertTrue(far.contains(pois.get(0)) && far.contains(pois.get(1)));
    }

    /** 排除一个不在列表中的点 → 不影响远 POI 筛选。 */
    @Test
    void excludeNotInListIsNoOp() {
        List<BlockPos> pois = List.of(new BlockPos(10, 64, 0), new BlockPos(1, 64, 0));
        List<BlockPos> far = TouristMoveGoal.farPoisExcluding(pois, HERE, 25, new BlockPos(99, 64, 99));
        assertEquals(1, far.size(), "排除不存在的 POI 不影响筛选");
        assertTrue(far.contains(pois.get(0)));
    }

    /** 唯一 POI 就是失败目标 → 无候选（调用方回落 random 重选/闲逛，靠 B 传送兜底）。 */
    @Test
    void singleFailedPoiYieldsEmpty() {
        BlockPos failed = new BlockPos(10, 64, 0);
        List<BlockPos> far = TouristMoveGoal.farPoisExcluding(List.of(failed), HERE, 25, failed);
        assertTrue(far.isEmpty(), "唯一 POI 就是失败目标 → 无候选");
    }
}
