package com.wsteam.wandscape.content.magic.worldresponse;

import com.wsteam.wandscape.content.colony.guard.ColonyLandProtectionHandler;
import com.wsteam.wandscape.foundation.util.BalanceValues;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Set;

/**
 * 「扶摇」：世界在玩家周围一圈里凭空铺出**一大片半砖平台**当下脚点，并沿前进方向长出一段**楼梯**，
 * 把人一级级托上去。铺法与移山填海同一套口径（每一轮扫描改一遍身边的区域、借用位置、出圈还回），
 * 只是这里是**加**东西而不是拿东西。
 *
 * <p><b>脚下一层是平台</b>：玩家脚下面那一层（{@code feet.below()}）里，半径
 * {@code worldResponseLiftPlatformRadius}（默认 4）的圆盘内，**空气**一律变成**上半砖**
 * （{@code stone_slab[type=top]}，顶面正好在玩家脚那一层）。于是：
 * <ul>
 *   <li>站在平地上时这一层本来就是实地，什么都不会发生；</li>
 *   <li>一旦脚下是空气（爬楼梯、跳、掉下去），宽平台立刻出现——这就是「凭空托住」，
 *       也是**转身踩空**的兜底：脚下永远是一大片，而不是一格；</li>
 *   <li>玩家完整踩上一级楼梯（脚已经站到楼梯上面那一格）时，那一级正好落在这个平台层里，
 *       于是被换成上半砖——顶面高度不变所以不会被顶，只是身后那一级薄下去。</li>
 * </ul>
 *
 * <p><b>楼梯</b>：沿前进方向、从玩家脚那一层起步，铺 {@code worldResponseLiftStairs}（默认 6）级、
 * 每级 {@code worldResponseLiftStairWidth}（默认 3）格宽，第 i 级在「前面第 i 格、抬高 i-1 格」。
 * 用真楼梯方块是因为它的碰撞天生是「前半格 0.5 + 后半格 1.0」——一级正好抬 1 格而玩家**不用跳**。
 *
 * <p><b>只有「空旷地带」铺得出来</b>——扶摇的用途是露天攀升，不是拆家：
 * ① 每一格都必须是**空气**（绝不替换任何已有方块）；② **人得在露天**（{@code canSeeSky(头那一格)}：
 * 屋顶下、洞里、水下整轮不铺）；③ 楼梯**逐格**再要求见得到天（免得长进山体/屋顶）；
 * ④ 不在**建筑地皮**上（{@link ColonyLandProtectionHandler#isProtected}）。于是玩家既不会拿它覆盖
 * 自己的红石/机器，也不会在别人的结构里凭空长出平台来。
 *
 * <p><b>方向与位置</b>：方向用两次扫描之间的**服务端位置差**取主轴（服务端玩家的 {@code deltaMovement}
 * 不可靠），两轴之间做 1.2 倍消抖，斜着走不会东西/南北来回跳；位置永远锚在玩家身上。
 * 每一轮扫描算出「这一轮想要的格子」（平台盘 + 楼梯段），不在其中的旧格子离玩家超过
 * {@code worldResponseLiftRestoreMargin} 就逐格还回——所以台阶是**短暂**的，走开就散。
 *
 * <p>借走的位置、回滚、未加载区块、以及「借用期间禁止第三方改动」统一交给 {@link BorrowedBlocks}
 * （与移山填海同一份规则）。
 */
public final class LiftEffect implements WorldResponseEffect {

    public static final String ID = "lift";
    private static final String TAG = "WorldResponse";

    /** 铺路用的方块：先用原版石质，想换观感改这两行（也可以照液面替身那样做成模组方块）。 */
    private static final Block STAIR = Blocks.STONE_STAIRS;
    private static final Block SLAB = Blocks.STONE_SLAB;
    /** 一次扫描里水平位移小于这个数（格）就算「没在前进」，不铺新楼梯。要放得过潜行（约 0.26 格/4t）。 */
    private static final double MOVING_EPSILON = 0.1;

    private final ServerLevel level;
    private final BorrowedBlocks borrowed;
    private int scanCooldown;
    /** 上一次扫描的位置：用服务端位置差判断前进方向。 */
    @Nullable private Vec3 lastPos;
    /** 上一次定下来的前进轴：斜着走时用它消抖。 */
    @Nullable private Direction climbDirection;
    /** 这一轮「想要」的格子（平台盘 + 楼梯段）：不在里面又走远了的旧格子会被还回去。 */
    private final Set<BlockPos> wanted = new HashSet<>();

    public LiftEffect(ServerLevel level) {
        this.level = level;
        this.borrowed = new BorrowedBlocks(level, PRIORITY_DEFAULT);
    }

    @Override
    public String id() {
        return ID;
    }

    /** 借走的位置禁止第三方改动：管理器在破坏/放置/活塞事件里据此取消（见 {@link WorldResponseEffects}）。 */
    @Override
    public boolean holds(LevelAccessor level, BlockPos pos) {
        return borrowed.holds(level, pos);
    }

    /** 让位：移山填海优先级更高，它要清开的格子（包括我们刚铺的台阶/平台）得先还回原位再交给它。 */
    @Override
    public void release(LevelAccessor level, BlockPos pos) {
        if (level != this.level) return;
        borrowed.release(pos);
    }

    @Override
    public void tick(ServerPlayer player) {
        if (--scanCooldown > 0) return;
        scanCooldown = Math.max(1, BalanceValues.worldResponseLiftScanInterval());
        if (level != player.serverLevel()) return;   // 换维度后管理器会停掉本效果，这里只是兜底

        BlockPos feet = player.blockPosition();
        wanted.clear();
        // 只在露天开工：头顶见不到天（屋顶下/洞里/水下）就整轮不铺——上一轮的形状由下面的
        // restoreNotIn 按"这一轮想要什么"收回去。
        if (playerUnderOpenSky(player)) {
            layPlatform(feet);
            buildStairs(player, feet);
        }
        double grace = Math.max(1, BalanceValues.worldResponseLiftRestoreMargin());
        borrowed.restoreNotIn(wanted, player.position(), grace * grace);
        borrowed.keepShaped();
        lastPos = player.position();
    }

    @Override
    public boolean stop(@Nullable ServerPlayer player, boolean loadChunks) {
        // 还剩下的由管理器挂起重试（它在那儿记一条日志），这里不再逐次刷屏
        return borrowed.restoreAll(loadChunks);
    }

    // ── 脚下一层：半砖平台 ──

    /**
     * 脚下面那一层铺一大片上半砖：空气铺新的，已经是我们铺的楼梯就**收成上半砖**（玩家踩过的那一级
     * 就是这么薄下去的）。顶面都在玩家脚那一层，所以既不会被顶、也不会把他卡进方块里。
     */
    private void layPlatform(BlockPos feet) {
        BlockPos layer = feet.below();
        int r = Math.max(1, BalanceValues.worldResponseLiftPlatformRadius());
        int rSqr = r * r;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > rSqr) continue;      // 圆形而不是方形
                BlockPos pos = layer.offset(dx, 0, dz);
                wanted.add(pos);
                if (borrowed.contains(pos)) {
                    BorrowedBlocks.Held held = borrowed.held(pos);
                    if (held != null && held.left().is(STAIR)) {
                        borrowed.reshape(pos, topSlab());    // 踩过的楼梯收薄（顶面不变）
                    }
                    continue;
                }
                if (!canBuildAt(pos)) continue;
                borrowed.take(pos, topSlab());
            }
        }
    }

    /**
     * 这一格能不能凭空铺东西：**已加载 + 空气 + 不在建筑地皮上**。
     *
     * <p>「只铺空气」就是「绝不替换任何已有方块」——扶摇的用途是**在空旷地带攀升**，不是让玩家拿它
     * 去改自己的红石、机器或别人的房子。想放宽就改这里。
     */
    private boolean canBuildAt(BlockPos pos) {
        if (!level.isLoaded(pos)) return false;
        if (!level.getBlockState(pos).isAir()) return false;       // 只铺空气：绝不替换已有方块
        return !ColonyLandProtectionHandler.isProtected(level, pos);
    }

    /**
     * 玩家自己在不在露天。
     *
     * <p>看**头那一格**而不是脚那格：脚那格常被我们自己铺的台阶/平台占着，而"人是不是露天"问的是
     * 他所处的这片空间。屋顶下、洞里、水下都返回 false——扶摇在那儿整轮不铺。
     */
    private boolean playerUnderOpenSky(ServerPlayer player) {
        return level.canSeeSky(player.blockPosition().above());
    }

    private static BlockState topSlab() {
        return SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP);
    }

    // ── 前进方向：一段楼梯 ──

    /**
     * 沿前进方向铺一段楼梯：第 i 级在「脚那一层前面第 i 格、抬高 i-1 格」，宽度居中于玩家。
     * 只往**空气**里铺；已经有我们的方块就不重铺（保留它原本的朝向）。
     */
    private void buildStairs(ServerPlayer player, BlockPos feet) {
        Direction dir = climbDirection(player);
        if (dir == null) return;                       // 没在前进：不铺新的（旧格子按 keep 集合保留/收回）
        int steps = Math.max(1, BalanceValues.worldResponseLiftStairs());
        int half = Math.max(0, (Math.max(1, BalanceValues.worldResponseLiftStairWidth()) - 1) / 2);
        Direction side = dir.getClockWise();           // 宽度方向
        BlockState stair = STAIR.defaultBlockState().setValue(StairBlock.FACING, dir);
        for (int i = 1; i <= steps; i++) {
            BlockPos base = feet.relative(dir, i).offset(0, i - 1, 0);
            for (int w = -half; w <= half; w++) {
                BlockPos pos = base.relative(side, w);
                wanted.add(pos);
                if (borrowed.contains(pos)) continue;          // 已经铺过：不重铺，也不改它的朝向
                // 楼梯逐格还要求"这一格见得到天"：免得楼梯长进山体、屋顶这些地方
                if (!canBuildAt(pos) || !level.canSeeSky(pos)) continue;
                borrowed.take(pos, stair);
            }
        }
    }

    /**
     * 前进方向：用两次扫描之间的**位置差**取主轴（服务端玩家的 deltaMovement 不可靠）。
     *
     * <p>消抖：新方向与上一次不是同一条轴时，必须在这一轴上明显更"正"（1.2 倍）才换——斜着走、
     * 或者转身到一半，都不会让楼梯在两条轴之间来回跳。
     *
     * @return null = 这次没在前进（站住了/被挡住了），不铺新楼梯
     */
    @Nullable
    private Direction climbDirection(ServerPlayer player) {
        Vec3 now = player.position();
        if (lastPos == null) return null;
        double dx = now.x - lastPos.x;
        double dz = now.z - lastPos.z;
        if (Math.max(Math.abs(dx), Math.abs(dz)) < MOVING_EPSILON) return null;
        Direction candidate = Math.abs(dx) >= Math.abs(dz)
                ? (dx > 0 ? Direction.EAST : Direction.WEST)
                : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
        if (climbDirection != null && candidate.getAxis() != climbDirection.getAxis()) {
            double onNewAxis = Math.abs(candidate.getAxis() == Direction.Axis.X ? dx : dz);
            double onOldAxis = Math.abs(climbDirection.getAxis() == Direction.Axis.X ? dx : dz);
            if (onNewAxis < onOldAxis * 1.2) return climbDirection;
        }
        climbDirection = candidate;
        return candidate;
    }
}
