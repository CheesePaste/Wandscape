package com.wsteam.wandscape.content.magic.worldresponse;

import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 一个持续效果**借走**的方块表：移山填海与扶摇共用，规则只写这一份。
 *
 * <p>每一格记两样东西：**原位**（回滚要还原成什么）与**我们留下的样子**（判断有没有被第三方动过、
 * 以及每轮扫描拿什么去压实）。三条不变式：
 * <ul>
 *   <li>只写**已加载**的区块，绝不为回滚同步加载区块（那是主线程卡顿 + 把没人去的区块拉进内存）；</li>
 *   <li>还原/压实前先看那一格还是不是「我们留下的样子」或是空气/液体——真被**实心方块**盖进来就让位
 *       （别人的建造优先于我们的回滚）；</li>
 *   <li>每一轮扫描把被流体/空气顶回来的格子重新压实：{@code UPDATE_CLIENTS}（flag 2）只保证
 *       「我们不通知邻居」，拦不住别人引发的方块更新。</li>
 * </ul>
 *
 * <p>「借走的位置禁止第三方改动」这件事在 {@link WorldResponseEffects} 的事件门里做，判定入口是
 * {@link #holds(LevelAccessor, BlockPos)}。
 */
final class BorrowedBlocks {

    private static final String TAG = "WorldResponse";

    /** 借出去的一格：原位长什么样，以及我们把它改成了什么。 */
    record Held(BlockState original, BlockState left) {}

    private final ServerLevel level;
    private final Map<BlockPos, Held> map = new LinkedHashMap<>();

    BorrowedBlocks(ServerLevel level) {
        this.level = level;
    }

    boolean isEmpty() {
        return map.isEmpty();
    }

    int size() {
        return map.size();
    }

    /** 这一格是不是我们借走的（不带维度判断，调用方自己确认维度）。 */
    boolean contains(BlockPos pos) {
        return map.containsKey(pos);
    }

    /** 借走的一格记了什么；没借过返回 null。 */
    @Nullable
    Held held(BlockPos pos) {
        return map.get(pos);
    }

    /** 这一格是不是我们借走的——事件保护用，**带维度判断**（两个维度同坐标不能互相误判）。 */
    boolean holds(LevelAccessor other, BlockPos pos) {
        return other == this.level && map.containsKey(pos);
    }

    /**
     * 写一格并记账（先写成功再记账，免得记下从没被改过的方块）。
     *
     * @return false = 已经是我们借走的、区块没加载、或写失败。调用方据此决定要不要继续往下做。
     */
    boolean take(BlockPos pos, BlockState left) {
        if (map.containsKey(pos)) return false;
        if (!level.isLoaded(pos)) return false;
        BlockState original = level.getBlockState(pos);
        if (!level.setBlock(pos, left, Block.UPDATE_CLIENTS)) return false;
        map.put(pos.immutable(), new Held(original, left));
        return true;
    }

    /** 换掉「我们留下的样子」（原位不动）：例如踏上去之后的楼梯换成上半砖。 */
    boolean reshape(BlockPos pos, BlockState left) {
        Held held = map.get(pos);
        if (held == null) return false;
        if (!level.setBlock(pos, left, Block.UPDATE_CLIENTS)) return false;
        map.put(pos.immutable(), new Held(held.original(), left));
        return true;
    }

    /** 把借出去的位置重新压成我们要的样子；真被实心方块盖进来就让出这一格。 */
    void keepShaped() {
        for (Iterator<Map.Entry<BlockPos, Held>> it = map.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<BlockPos, Held> e = it.next();
            Held held = e.getValue();
            if (!level.isLoaded(e.getKey())) continue;            // 区块没加载：等它回来
            BlockState now = level.getBlockState(e.getKey());
            if (now.equals(held.left())) continue;                 // 还是我们留下的样子
            if (canSettle(now, held.left())) {
                level.setBlock(e.getKey(), held.left(), Block.UPDATE_CLIENTS);
                continue;
            }
            Log.info(TAG, "[WorldResponse] {} was built over — giving it up", e.getKey());
            it.remove();
        }
    }

    /** 离开范围的格子立刻还原（`limitSqr` 由调用方按自己的半径算好）。 */
    void restoreOutOfRange(Vec3 center, double limitSqr) {
        Iterator<Map.Entry<BlockPos, Held>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Held> e = it.next();
            if (e.getKey().distToCenterSqr(center.x, center.y, center.z) <= limitSqr) continue;
            if (!restore(e.getKey(), e.getValue(), false)) continue;   // 保留快照：等那一格所在区块回来
            it.remove();
        }
    }

    /**
     * 把「不在 {@code keep} 里、又离 {@code center} 超过 {@code graceSqr}」的格子还回去。
     *
     * <p>给形状每一轮都在变的效应用（扶摇的平台盘 + 楼梯段）：直接算「这一轮想要哪些格子」比调一个
     * 球形半径精确，台阶也就真的是**短暂**的——走开就散，只留一小段 grace 防抖。
     */
    void restoreNotIn(Set<BlockPos> keep, Vec3 center, double graceSqr) {
        Iterator<Map.Entry<BlockPos, Held>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Held> e = it.next();
            if (keep.contains(e.getKey())) continue;
            if (e.getKey().distToCenterSqr(center.x, center.y, center.z) <= graceSqr) continue;
            if (!restore(e.getKey(), e.getValue(), false)) continue;
            it.remove();
        }
    }

    /**
     * 全部还原。
     *
     * @param loadChunks 只给关服那一次收尾用（见 {@link WorldResponseEffect#stop}）
     * @return true = 已经完全还干净；false = 还有格子压在未加载的区块里，必须稍后重试
     */
    boolean restoreAll(boolean loadChunks) {
        int settled = 0;
        Iterator<Map.Entry<BlockPos, Held>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Held> e = it.next();
            if (!restore(e.getKey(), e.getValue(), loadChunks)) continue;
            it.remove();
            settled++;
        }
        if (settled > 0) {
            Log.info(TAG, "[WorldResponse] settled {} borrowed block(s)", settled);
        }
        return map.isEmpty();
    }

    /**
     * 还原一格。只在「这格还是我们留下的样子，或被空气/液体顶回来了」时才写；真被实心方块占了就不覆盖
     * （那是别人的建造），记一条日志并把这一格从表里丢掉——调用方随后会把它移除。
     *
     * @return false = 区块没加载，这一格留到下次
     */
    private boolean restore(BlockPos pos, Held held, boolean loadChunks) {
        if (!loadChunks && !level.isLoaded(pos)) return false;
        BlockState now = level.getBlockState(pos);
        if (!canSettle(now, held.left())) {
            Log.info(TAG, "[WorldResponse] {} is no longer ours — leaving it as it is", pos);
            return true;
        }
        level.setBlock(pos, held.original(), Block.UPDATE_ALL);
        return true;
    }

    /** 可以安全还原/压实吗：还是我们留下的样子，或者被空气/液体顶回来了；其余一律算别人的东西。 */
    private static boolean canSettle(BlockState now, BlockState left) {
        return now.equals(left) || now.isAir() || now.getBlock() instanceof LiquidBlock;
    }
}
