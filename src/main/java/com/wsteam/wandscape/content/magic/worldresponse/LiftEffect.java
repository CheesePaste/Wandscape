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
 * <p><b>方向</b>：用两次扫描之间的**服务端位置差**取主轴（服务端玩家的 {@code deltaMovement} 不可靠）；
 * 斜着走（两轴比值 &gt; {@value #AMBIGUOUS_RATIO}）时不猜主轴、直接看**视线**，免得楼梯铺成锯齿。
 * **视角朝下**（俯角超过 {@code worldResponseLiftMaxDownPitch}，默认 30 度）时不再往前铺楼梯——
 * 人朝下看是在下落 / 找落脚点，脚下平台照旧留着。
 *
 * <p><b>回收**留一轮缓冲**</b>：只有「这一轮和上一轮都不想要」的格子才还回（{@link #wantedPrev}），
 * 所以新旧交接时不会出现"先收后放"的真空期，也不会留下没人管的残块。
 * 停下时**不会立刻收回**：只要玩家还在借用范围里（{@code worldResponseLiftRestoreMargin} 格内还有我们
 * 铺的东西），就先留着当落脚点，等他走开再逐格还回（见 {@link #stop}）。
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
    /** 两轴位移比值超过它就当"斜着走"：这时用视线定方向，而不是猜主轴（免得楼梯铺成锯齿）。 */
    private static final double AMBIGUOUS_RATIO = 0.75;

    private final ServerLevel level;
    private final BorrowedBlocks borrowed;
    private int scanCooldown;
    /** 上一次扫描的位置：用服务端位置差判断前进方向。 */
    @Nullable private Vec3 lastPos;
    /** 当前的前进方向（停下之后会沿用一段时间，见 {@code worldResponseLiftHoldTicks}）。 */
    @Nullable private Direction climbDirection;
    /** 停下之后已经过了多少 tick：超过保持时间才把楼梯放掉。 */
    private int idleTicks;
    /** 这一轮「想要」的格子（平台盘 + 楼梯段）；与 {@link #wantedPrev} 一起决定回收。 */
    private Set<BlockPos> wanted = new HashSet<>();
    /** 上一轮「想要」的格子：留一轮缓冲，避免新旧交接时的真空期与残块。 */
    private Set<BlockPos> wantedPrev = new HashSet<>();

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
        boolean lookingDown = player.getXRot() > Math.max(10, BalanceValues.worldResponseLiftMaxDownPitch());
        Direction sampled = lookingDown ? null : sampleDirection(player);
        if (lookingDown) {
            // 视角明显朝下：不再往前铺楼梯，也不保留旧的（人显然在下落/找落脚点）；脚下平台照旧
            climbDirection = null;
            idleTicks = 0;
        } else if (sampled != null) {
            climbDirection = sampled;
            idleTicks = 0;
        } else if (climbDirection != null
                && ++idleTicks > Math.max(1, BalanceValues.worldResponseLiftHoldTicks())) {
            climbDirection = null;      // 停久了才放手
        }

        Set<BlockPos> now = wanted;
        now.clear();
        // 只在露天开工：头顶见不到天（屋顶下/洞里/水下）就整轮不铺——旧形状由下面的回收收走
        if (playerUnderOpenSky(player)) {
            layPlatform(feet, now);
            buildStairs(feet, now);
        }
        // 回收：**这一轮和上一轮都不想要**的才还（留一轮缓冲，避免"先收后放"的真空期与残块）
        Set<BlockPos> keep = new HashSet<>(now);
        keep.addAll(wantedPrev);
        borrowed.restoreNotIn(keep);
        borrowed.keepShaped();
        // 双缓冲交换：这一轮变成"上一轮"，旧的那份留给下一轮 clear 复用
        Set<BlockPos> spare = wantedPrev;
        wantedPrev = now;
        wanted = spare;
    }

    /**
     * 停下时**先不急着还**。
     *
     * <p>玩家还站在我们铺的东西上（或旁边）时就先留着当落脚点——法术一停脚下立刻空掉会把人摔下去。
     * 返回 {@code false} 让管理器过一会儿再问一次，等他走开（或者人走了/换维度了/关服收尾）再整批还回。
     */
    @Override
    public boolean stop(@Nullable ServerPlayer player, boolean loadChunks) {
        if (loadChunks || player == null) return borrowed.restoreAll(loadChunks);   // 关服收尾 / 人不在：直接还
        if (player.serverLevel() != level) return borrowed.restoreAll(false);       // 人已经在别的维度
        double r = Math.max(1, BalanceValues.worldResponseLiftRestoreMargin());
        if (borrowed.hasAnyWithin(player.position(), r * r)) return false;          // 还踩着：留着，等走开
        return borrowed.restoreAll(false);
    }

    // ── 脚下一层：半砖平台 ──

    /**
     * 脚下面那一层铺一大片上半砖：空气铺新的，已经是我们铺的楼梯就**收成上半砖**（玩家踩过的那一级
     * 就是这么薄下去的）。顶面都在玩家脚那一层，所以既不会被顶、也不会把他卡进方块里。
     */
    private void layPlatform(BlockPos feet, Set<BlockPos> now) {
        BlockPos layer = feet.below();
        int r = Math.max(1, BalanceValues.worldResponseLiftPlatformRadius());
        int rSqr = r * r;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > rSqr) continue;      // 圆形而不是方形
                BlockPos pos = layer.offset(dx, 0, dz);
                now.add(pos);
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
     * 只往**空气**里铺；已经是我们铺的楼梯**只改朝向**（转向之后旧朝向的台阶爬不上去）。
     */
    private void buildStairs(BlockPos feet, Set<BlockPos> now) {
        Direction dir = climbDirection;
        if (dir == null) return;                       // 没有方向（刚开/停太久/朝下看）：只留平台
        int steps = Math.max(1, BalanceValues.worldResponseLiftStairs());
        int half = Math.max(0, (Math.max(1, BalanceValues.worldResponseLiftStairWidth()) - 1) / 2);
        Direction side = dir.getClockWise();           // 宽度方向
        BlockState stair = stairState(dir);
        for (int i = 1; i <= steps; i++) {
            BlockPos base = feet.relative(dir, i).offset(0, i - 1, 0);
            for (int w = -half; w <= half; w++) {
                BlockPos pos = base.relative(side, w);
                now.add(pos);
                if (borrowed.contains(pos)) {
                    // 转向之后：把这一格已经铺好的楼梯改成新朝向，否则它会横在路中间爬不上去。
                    // 玩家自己那格不在楼梯段里（i 从 1 起），所以不会改到他正踩着的那一级。
                    BorrowedBlocks.Held held = borrowed.held(pos);
                    if (held != null && held.left().is(STAIR)
                            && held.left().getValue(StairBlock.FACING) != dir) {
                        borrowed.reshape(pos, stair);
                    }
                    continue;
                }
                // 楼梯逐格还要求"这一格见得到天"：免得楼梯长进山体、屋顶这些地方
                if (!canBuildAt(pos) || !level.canSeeSky(pos)) continue;
                borrowed.take(pos, stair);
            }
        }
    }

    private static BlockState stairState(Direction facing) {
        return STAIR.defaultBlockState().setValue(StairBlock.FACING, facing);
    }

    /**
     * 前进方向：用两次扫描之间的**位置差**取主轴（服务端玩家的 {@code deltaMovement} 不可靠）。
     *
     * <p>斜着走（两轴分量差不多）时不猜主轴，直接看**视线朝向**——那才是玩家想去的地方，
     * 也避免了「在两条轴之间来回跳、楼梯铺成锯齿」。正对某条轴走时才用位移主轴。
     *
     * @return null = 这次没在前进（站住了/被挡住了）
     */
    @Nullable
    private Direction sampleDirection(ServerPlayer player) {
        Vec3 now = player.position();
        if (lastPos == null) return null;
        double dx = now.x - lastPos.x;
        double dz = now.z - lastPos.z;
        double ax = Math.abs(dx);
        double az = Math.abs(dz);
        if (Math.max(ax, az) < MOVING_EPSILON) return null;
        if (Math.min(ax, az) > Math.max(ax, az) * AMBIGUOUS_RATIO) {
            Direction look = player.getDirection();      // 视线水平朝向（已是最近的四个方向之一）
            return look.getAxis().isHorizontal() ? look : null;
        }
        return ax >= az ? (dx > 0 ? Direction.EAST : Direction.WEST)
                : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
    }
}
