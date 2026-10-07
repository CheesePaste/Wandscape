package com.wsteam.wandscape.content.magic.worldresponse;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.content.colony.guard.ColonyLandProtectionHandler;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.util.BalanceValues;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.Set;

/**
 * 「移山填海」：把玩家周围一圈里**会挡住移动**的方块临时移开，人走过去之后原样放回。
 *
 * <p>清理范围是玩家脚下那一层与身体那一层（{@code dy = 0..1}）的圆形区域——
 * **脚下那一格不抽**（只有它是液体时才垫），头上那格也不碰，所以不会把人脚下的地板抽掉、
 * 也不会削掉头顶的天花板。
 *
 * <p><b>踏水而行</b>：脚那一格如果是**液体方块本身**（水/岩浆），就临时换成一个**液面替身**
 * （{@link SolidFluidBlock}：贴图/高度/颜色都与原版液面一致，但有完整碰撞）。于是水面和岩浆面上
 * 会跟着人铺出一条看不见的路：走上去不落水、不陷进岩浆，走开就还回液体。
 * 垫的是**脚那一格**而不是它下面那格：人被顶到液面上站着，于是不必在水里挖坑，水面只少掉
 * 「他正踩着的那一层」；脚那格已经是空气（说明他已经在液面上走）时才看下面那格。
 * 只有「本身就是液体」的方块才垫（{@link LiquidBlock}），含水台阶/含水楼梯那种本来就站得住，不碰；
 * 整合包的自定义液体没有替身，退回屏障方块。
 * 配合管理器里的热伤害免疫（{@link #wardsHeat()}），路过岩浆池不会掉血。
 *
 * <p><b>重力方块、液体与「靠支撑的方块」都靠写入标志解决</b>：改写用 {@link BorrowedBlocks} 的
 * {@code WRITE_FLAGS}（{@code UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE | UPDATE_SUPPRESS_DROPS}）——
 * 它跳过**邻居方块更新**（沙砾不塌、水浆不灌）**也跳过邻居形状更新**（雪层/火把/花草/铁轨不会
 * 因为底下被抽空而当场碎掉；这条是实测抓到的 bug：只用 flag 2 时 {@code updateNeighbourShapes}
 * 照样跑，靠支撑的方块会碎，而且它们不在我们的快照里、回滚也还不了）；
 * 回放时用 {@link Block#UPDATE_ALL}，物理照常回归（该落的落、该流的流）。
 * 这比"在边界额外放临时封堵"干净得多：不留任何非原版方块，也就不需要第二轮还原。
 *
 * <p><b>永远不动的方块分五类</b>：① **玩家自己列的名单**
 * （{@link WorldResponseProtectionSavedData}，`/wandscape response protect`）；② 传送门本体
 * （{@link BlockTags#PORTALS}）；③ **与传送门相邻一圈的方块**——门框就是紧贴门的那一圈
 * （含斜角的四个角，所以要看 3×3×3 而不是只看上下左右），拆了框的门会在下一次方块更新里自己熄灭；
 * ④ 不可破坏（{@code getDestroySpeed < 0}）与带方块实体（容器/告示牌，直接清会把内容物吞掉）；
 * ⑤ 属于任何建筑的地皮（{@link ColonyLandProtectionHandler#isProtected}——世界让路不该拆别人的房子，
 * 包括施法者自己的；这条对垫脚层同样生效）。
 *
 * <p>借走的位置、回滚、未加载区块、以及「借用期间禁止第三方改动」这几件事统一在
 * {@link BorrowedBlocks} 里（扶摇共用同一份规则），本类只管「怎么改」。
 */
public final class TerraformEffect implements WorldResponseEffect {

    public static final String ID = "terraform";
    private static final String TAG = "WorldResponse";

    private final ServerLevel level;
    /** 借走的位置表（规则见 {@link BorrowedBlocks}）：移开的方块、被垫脚层顶掉的液体。 */
    private final BorrowedBlocks borrowed;
    private int scanCooldown;

    public TerraformEffect(ServerLevel level) {
        this.level = level;
        this.borrowed = new BorrowedBlocks(level, PRIORITY_TERRAFORM);
    }

    @Override
    public String id() {
        return ID;
    }

    /** 「世界为你让路」包含不被自己的路烫伤：岩浆/火/岩浆块的环境热伤害在生效期间一律免掉。 */
    @Override
    public boolean wardsHeat() {
        return true;
    }

    /** 借走的位置禁止第三方改动：管理器在破坏/放置/活塞事件里据此取消（见 {@link WorldResponseEffects}）。 */
    @Override
    public boolean holds(LevelAccessor level, BlockPos pos) {
        return borrowed.holds(level, pos);
    }

    /**
     * **世界让路的权限最高**：一格同一时刻只归一个效果，但移山填海可以先手——低优先级的回应
     * （扶摇的平台/台阶）得先把那一格还回原位，再由这里接管。反过来扶摇抢不走这里借的格子。
     */
    @Override
    public int borrowPriority() {
        return PRIORITY_TERRAFORM;
    }

    /** 让位：把这一格还回原位。移山填海已经是最高的，正常不会被点到；留着是为了将来加更高的。 */
    @Override
    public void release(LevelAccessor level, BlockPos pos) {
        if (level != this.level) return;
        borrowed.release(pos);
    }

    @Override
    public void tick(ServerPlayer player) {
        if (--scanCooldown > 0) return;
        scanCooldown = Math.max(1, BalanceValues.worldResponseTerraformScanInterval());
        if (level != player.serverLevel()) return;   // 换维度后管理器会停掉本效果，这里只是兜底
        Set<Block> blacklist = WorldResponseProtectionSavedData.blacklist(level, player.getUUID());
        double limit = Math.max(1, BalanceValues.worldResponseTerraformRadius())
                + Math.max(1, BalanceValues.worldResponseTerraformRestoreMargin()) + 0.5;
        borrowed.restoreOutOfRange(player.position(), limit * limit);
        borrowed.keepShaped();
        reshapeAround(player, blacklist);
    }

    @Override
    public boolean stop(@Nullable ServerPlayer player, boolean loadChunks) {
        // 还剩下的由管理器挂起重试（它在那儿记一条日志），这里不再逐次刷屏
        return borrowed.restoreAll(loadChunks);
    }

    // ── 改 ──

    private void reshapeAround(ServerPlayer player, Set<Block> blacklist) {
        BlockPos feet = player.blockPosition();
        int r = Math.max(1, BalanceValues.worldResponseTerraformRadius());
        int rSqr = r * r;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > rSqr) continue;      // 圆形而不是方形范围
                // 垫脚层：先看玩家脚那一格——它是液体就把那一格垫实（人会被顶到液面上站着，
                // 这样不必在水里挖坑，水面只少掉「他踩着的那一层」）；脚那格是空气就说明他已经在
                // 液面上走了，再看下面一格。垫脚层含玩家自己那一列：那正是他马上要踩上去的地方。
                BlockPos atFeet = feet.offset(dx, 0, dz);
                if (!pave(atFeet, blacklist)) {
                    pave(feet.offset(dx, -1, dz), blacklist);
                }
                if (dx == 0 && dz == 0) continue;            // 玩家身上那根柱子：脚下不抽、身上本来就是空气
                for (int dy = 0; dy <= 1; dy++) {            // 只有身体这两层；dy=2（头上）不管
                    carve(feet.offset(dx, dy, dz), blacklist);
                }
            }
        }
    }

    /** 移开一格挡路的东西：记好原位再写空气。 */
    private void carve(BlockPos pos, Set<Block> blacklist) {
        if (borrowed.contains(pos)) return;
        if (!level.isLoaded(pos)) return;                    // 不在未加载的区块里动土
        if (!blocksMovement(pos)) return;
        if (!canTouch(pos, blacklist)) return;
        borrowed.take(pos, Blocks.AIR.defaultBlockState());   // 写失败就不记账，快照里不会留没改过的方块
    }

    /**
     * 垫脚层：这一格是液体就临时换成它的**液面替身**（{@link SolidFluidBlock}），让人能踩着水面/岩浆面走。
     *
     * @return true = 这一格已经归我们管（刚垫上、或本来就是我们的垫脚层），不必再看下面一格；
     *         false = 这一格不是液体（空气/普通方块），请调用方去看下面那一格
     */
    private boolean pave(BlockPos pos, Set<Block> blacklist) {
        if (borrowed.contains(pos)) return true;             // 已经是我们改过的（垫过或移开过）
        if (!level.isLoaded(pos)) return true;               // 未加载的区块不去碰，也不再往下看
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof LiquidBlock)) return false;   // 只垫"本身就是液体"的格子
        if (!canTouch(pos, blacklist)) return true;          // 名单/地皮护着：这一格不垫
        borrowed.take(pos, floorFor(state).defaultBlockState());
        return true;
    }

    /**
     * 液体 → 它的**液面替身**（{@link SolidFluidBlock}：外表与真液体一样、但有支撑）。
     * 整合包的自定义液体没有替身，退回屏障方块——不好看，但照样能站，功能不断。
     */
    private static Block floorFor(BlockState liquid) {
        if (liquid.is(Blocks.WATER)) return Wandscape.WORLD_RESPONSE_WATER.get();
        if (liquid.is(Blocks.LAVA)) return Wandscape.WORLD_RESPONSE_LAVA.get();
        return Blocks.BARRIER;
    }

    /**
     * 「碍事」的判定：有碰撞箱、是液体、或者**有形状**。
     *
     * <p>第三条是实测补的：雪层 / 花草 / 火把 / 铁轨 / 地毯 / 按钮这些**薄片与植物**碰撞箱要么为空、
     * 要么小得可怜，但一样挡视线、一样该被让开——只按碰撞箱判会让它们原地不动（实测反馈"范围内的雪不会消失"）。
     * 真正空形状的东西（光方块、火）仍然不碰。
     */
    private boolean blocksMovement(BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return false;
        if (!state.getFluidState().isEmpty()) return true;                    // 水会推人，算挡路
        if (!state.getCollisionShape(level, pos).isEmpty()) return true;
        return !state.getShape(level, pos).isEmpty();                          // 薄片/植物：没有（或几乎没有）碰撞箱，但有形状
    }

    /** 这一格能不能动：玩家名单、传送门（本体与门框）、不可破坏、带方块实体、建筑地皮，五道门。 */
    private boolean canTouch(BlockPos pos, Set<Block> blacklist) {
        BlockState state = level.getBlockState(pos);
        if (blacklist.contains(state.getBlock())) return false;    // 玩家自己列的名单
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
}
