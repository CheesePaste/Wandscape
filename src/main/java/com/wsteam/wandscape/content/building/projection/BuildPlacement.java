package com.wsteam.wandscape.content.building.projection;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 建造模式落点解析：把准星射线命中的方块解析为建筑放置的锚点。
 *
 * <p>射线命中草本/树叶等不能作为立足点的方块时（草、花、蘑菇、树叶，以及任何
 * 可替换或没有碰撞箱的方块），沿该列向下找第一个真正可立足的方块，让建筑落在
 * 草方块/泥土上，而不是被植物垫高一层（否则整栋建筑高度虚高）。命中的是合规
 * 支撑（实心方块、墙体等）时保持原行为——贴着命中面放置，保留贴墙/侧面放置。
 *
 * <p>立足判定与 {@code road/network/DestroyFillPacket} 找真实地面的口径一致：
 * 跳过可替换方块（草、花、蘑菇、雪、水等）；额外显式排除树叶（树叶碰撞箱完整、
 * 不可替换，但显然不是合规的建筑落脚点）。
 */
public final class BuildPlacement {

    private BuildPlacement() {}

    /** 世界查询接口：判断 pos 处的方块是否可作为建筑立足点。 */
    public interface SupportTest {
        boolean test(BlockPos pos);
    }

    /**
     * 便捷入口（MC 世界实现）：命中方块合法则贴着命中面放置；否则向下找到第一个
     * 可立足方块，返回其上方一格作为锚点。
     */
    public static BlockPos resolve(Level level, BlockPos hitPos, Direction hitDir) {
        return resolve(hitPos, hitDir, pos -> isStandable(level, pos), level.getMinBuildHeight());
    }

    /**
     * 纯逻辑核心（可单元测试，不依赖 MC 运行时）：从射线命中方块解析建筑锚点。
     *
     * @param hitPos  射线命中的方块
     * @param hitDir  命中面方向
     * @param support 判断某方块是否可立足（由调用方注入世界查询）
     * @param minY    世界最低建造高度（含），向下搜索的边界
     * @return 建筑锚点方块位置；找不到立足点时回退为原行为 {@code hitPos.relative(hitDir)}
     */
    public static BlockPos resolve(BlockPos hitPos, Direction hitDir, SupportTest support, int minY) {
        if (support.test(hitPos)) {
            return hitPos.relative(hitDir);
        }
        BlockPos.MutableBlockPos cursor = hitPos.mutable();
        while (cursor.getY() > minY) {
            cursor.move(Direction.DOWN);
            if (support.test(cursor.immutable())) {
                return cursor.above();
            }
        }
        return hitPos.relative(hitDir);
    }

    /**
     * 判断某方块是否可作为建筑立足点。
     * 合规 = 不是树叶（碰撞箱完整但非建筑落脚点）、不是可替换方块
     * （草/花/蘑菇/雪/水等）、且有碰撞箱可站立。
     */
    public static boolean isStandable(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.is(BlockTags.LEAVES)) return false;
        if (state.canBeReplaced()) return false;
        return !state.getCollisionShape(level, pos).isEmpty();
    }
}
