package com.wsteam.wandscape.content.magic.worldresponse;

import com.wsteam.wandscape.content.colony.guard.ColonyLandProtectionHandler;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.util.BalanceValues;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 「移山填海」：把玩家周围一圈里**会挡住移动**的方块临时移开，人走过去之后原样放回。
 *
 * <p>清理范围是玩家脚下那一层与身体那一层（{@code dy = 0..1}）的圆形区域——
 * **脚下那格与头上那格都不碰**，所以不会把人脚下的地板抽掉、也不会削掉头顶的天花板。
 *
 * <p><b>重力方块与液体靠写入标志解决</b>：移开时用 {@link Block#UPDATE_CLIENTS}（只同步客户端、
 * 不给邻居发更新），于是正上方的沙/砾**不会立刻塌进来**、旁边的水/岩浆**不会立刻灌进来**；
 * 回放时用 {@link Block#UPDATE_ALL}，物理照常回归（该落的落、该流的流）。
 * 这比"在边界额外放临时封堵"干净得多：不留任何非原版方块，也就不需要第二轮还原。
 *
 * <p><b>三类方块永远不动</b>：不可破坏（{@code getDestroySpeed < 0}，基岩这类，用原版语义而不是
 * 维护名单）、带方块实体（容器/告示牌，直接清会把内容物吞掉）、属于任何建筑的地皮
 * （{@link ColonyLandProtectionHandler#isProtected}——世界让路不该拆别人的房子，包括施法者自己的）。
 *
 * <p>回滚有三条触发：走出范围（逐格还）、主动停止/断线/换维度/关服（一次还干净）。
 * 快照只存内存、按维度绑定：换维度时管理器会先停掉本效果，所以不存在"跨维度还错地方"。
 */
public final class TerraformEffect implements WorldResponseEffect {

    public static final String ID = "terraform";
    private static final String TAG = "WorldResponse";

    private final ServerLevel level;
    /** 被移开的方块 → 原位快照。只记一次；同一格重复扫到不会覆盖原状。 */
    private final Map<BlockPos, BlockState> removed = new LinkedHashMap<>();
    private int scanCooldown;

    public TerraformEffect(ServerLevel level) {
        this.level = level;
    }

    @Override
    public String id() {
        return ID;
    }

    /** 当前被临时移开的方块数（调试/反馈用）。 */
    public int removedCount() {
        return removed.size();
    }

    @Override
    public void tick(ServerPlayer player) {
        if (--scanCooldown > 0) return;
        scanCooldown = Math.max(1, BalanceValues.worldResponseTerraformScanInterval());
        if (level != player.serverLevel()) return;   // 换维度后管理器会停掉本效果，这里只是兜底
        restoreOutOfRange(player);
        carveAround(player);
    }

    @Override
    public void stop(ServerPlayer player) {
        int n = removed.size();
        for (Map.Entry<BlockPos, BlockState> e : removed.entrySet()) {
            level.setBlock(e.getKey(), e.getValue(), Block.UPDATE_ALL);
        }
        removed.clear();
        if (n > 0) {
            Log.info(TAG, "[WorldResponse] Terraform restored {} block(s)", n);
        }
    }

    // ── 清 ──

    private void carveAround(ServerPlayer player) {
        BlockPos feet = player.blockPosition();
        int r = Math.max(1, BalanceValues.worldResponseTerraformRadius());
        int rSqr = r * r;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx == 0 && dz == 0) continue;            // 玩家自己那根柱子：脚下不抽、身上本来就是空气
                if (dx * dx + dz * dz > rSqr) continue;      // 圆形而不是方形范围
                for (int dy = 0; dy <= 1; dy++) {            // 只有身体这两层；dy=-1（脚下）与 dy=2（头上）不管
                    BlockPos pos = feet.offset(dx, dy, dz);
                    if (removed.containsKey(pos)) continue;
                    if (!blocksMovement(pos)) continue;
                    if (!canClear(pos)) continue;
                    removed.put(pos.immutable(), level.getBlockState(pos));
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    /** 「会阻挡移动」：有碰撞箱，或者是液体（水会推人，同样算挡路）。 */
    private boolean blocksMovement(BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return false;
        if (!state.getFluidState().isEmpty()) return true;
        return !state.getCollisionShape(level, pos).isEmpty();
    }

    private boolean canClear(BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getDestroySpeed(level, pos) < 0) return false;   // 不可破坏（基岩/屏障这类）
        if (state.hasBlockEntity()) return false;                  // 容器/告示牌：清了会吞内容物
        return !ColonyLandProtectionHandler.isProtected(level, pos);
    }

    // ── 还 ──

    /** 离开「清理半径 + 余量」的格子立刻放回：效果跟着人走，人走过之后地形不留疤。 */
    private void restoreOutOfRange(ServerPlayer player) {
        int r = Math.max(1, BalanceValues.worldResponseTerraformRadius());
        double limit = r + Math.max(1, BalanceValues.worldResponseTerraformRestoreMargin()) + 0.5;
        double limitSqr = limit * limit;
        Vec3 center = player.position();
        Iterator<Map.Entry<BlockPos, BlockState>> it = removed.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, BlockState> e = it.next();
            if (e.getKey().distToCenterSqr(center.x, center.y, center.z) <= limitSqr) continue;
            level.setBlock(e.getKey(), e.getValue(), Block.UPDATE_ALL);
            it.remove();
        }
    }
}
