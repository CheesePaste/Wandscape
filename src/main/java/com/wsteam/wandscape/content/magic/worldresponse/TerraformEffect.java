package com.wsteam.wandscape.content.magic.worldresponse;

import com.wsteam.wandscape.content.colony.guard.ColonyLandProtectionHandler;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.util.BalanceValues;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
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
 * <p><b>永远不动的方块分四类</b>：① 传送门本体（{@link BlockTags#PORTALS}，原版的门方块虽然本身
 * 不可破坏，但整合包的自定义门不一定）；② **与传送门相邻一圈的方块**——门框就是紧贴门的那一圈
 * （含斜角的四个角，所以要看 3×3×3 而不是只看上下左右），拆了框的门会在下一次方块更新里自己熄灭；
 * ③ 不可破坏（{@code getDestroySpeed < 0}，基岩这类，用原版语义而不是维护名单）与带方块实体
 * （容器/告示牌，直接清会把内容物吞掉）；④ 属于任何建筑的地皮
 * （{@link ColonyLandProtectionHandler#isProtected}——世界让路不该拆别人的房子，包括施法者自己的）。
 *
 * <p><b>未加载的区块不写也不丢</b>：清理只发生在玩家身边（必然是已加载的），回放时若某一格所在区块
 * 已经卸载，就**留着快照等它回来**——绝不为回放同步加载区块，也绝不把还不了的记录删掉
 * （{@link #stop} 会返回 false，由 {@link WorldResponseEffects} 挂起重试）。
 *
 * <p>回滚触发有四种：走出范围（逐格还）、主动停止/断线、传送与换维度、关服；四条都汇到
 * {@link #stop}。快照只存内存、按维度绑定：换维度前管理器会先停掉本效果，所以不存在"跨维度还错地方"。
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
    public boolean stop(@Nullable ServerPlayer player, boolean loadChunks) {
        int restored = 0;
        Iterator<Map.Entry<BlockPos, BlockState>> it = removed.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, BlockState> e = it.next();
            // 区块没加载就留着（除非是关服那一次收尾）：同步加载区块会卡主线程，也会把没人去的区块拉进内存
            if (!loadChunks && !level.isLoaded(e.getKey())) continue;
            level.setBlock(e.getKey(), e.getValue(), Block.UPDATE_ALL);
            it.remove();
            restored++;
        }
        if (restored > 0) {
            Log.info(TAG, "[WorldResponse] Terraform restored {} block(s)", restored);
        }
        // 还剩下的由管理器挂起重试（它在那儿记一条日志），这里不再逐次刷屏
        return removed.isEmpty();
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
                    if (!level.isLoaded(pos)) continue;      // 不在未加载的区块里动土
                    if (!blocksMovement(pos)) continue;
                    if (!canClear(pos)) continue;
                    BlockState original = level.getBlockState(pos);
                    // 写失败（超世界高度、debug 世界）就当没这回事：先写再记账，免得快照里留着从没被移开的方块
                    if (!level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS)) continue;
                    removed.put(pos.immutable(), original);
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
        if (state.is(BlockTags.PORTALS)) return false;             // 传送门本体
        if (portalNearby(pos)) return false;                       // 门框（含斜角）
        if (state.getDestroySpeed(level, pos) < 0) return false;   // 不可破坏（基岩/屏障这类）
        if (state.hasBlockEntity()) return false;                  // 容器/告示牌：清了会吞内容物
        return !ColonyLandProtectionHandler.isProtected(level, pos);
    }

    /**
     * 3×3×3 邻域里只要有传送门方块就整块跳过。
     *
     * <p>为什么要连斜角一起看：门框是一整圈，门的四个**角**上的框块与门方块只是斜邻——只看上下左右
     * 会漏掉它们，而拆掉任何一个角框都会让 {@code PortalShape} 判定为不完整，门在下一次方块更新里熄灭。
     */
    private boolean portalNearby(BlockPos pos) {
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    probe.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    if (!level.isLoaded(probe)) continue;          // 边界外那一格可能落在没加载的区块里
                    if (level.getBlockState(probe).is(BlockTags.PORTALS)) return true;
                }
            }
        }
        return false;
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
            if (!level.isLoaded(e.getKey())) continue;   // 保留快照：这一格等它所在的区块回来再还
            level.setBlock(e.getKey(), e.getValue(), Block.UPDATE_ALL);
            it.remove();
        }
    }
}
