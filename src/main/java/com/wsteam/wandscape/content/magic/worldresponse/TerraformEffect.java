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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
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
 * <p><b>重力方块与液体靠写入标志解决</b>：改动时用 {@link Block#UPDATE_CLIENTS}（只同步客户端、
 * 不给邻居发更新），于是正上方的沙/砾**不会立刻塌进来**、旁边的水/岩浆**不会立刻灌进来**；
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
 * <p><b>未加载的区块不写也不丢</b>：清理只发生在玩家身边（必然是已加载的），回放时若某一格所在区块
 * 已经卸载，就**留着快照等它回来**——绝不为回放同步加载区块，也绝不把还不了的记录删掉
 * （{@link #stop} 会返回 false，由 {@link WorldResponseEffects} 挂起重试）。
 *
 * <p><b>借出去的位置不许第三方改动</b>：效果借走的每一格都记在 {@link #holds} 里，管理器据此
 * 取消玩家（以及其它实体）对它的破坏/放置（含流体自己造方块），并在每一轮扫描时**重新压实**一次
 * ——因为 flag 2 只保证「我们不通知邻居」，邻居被别的原因更新时水照样能流回我们挖开的空位。
 * 万一真的有实心方块盖进来了（命令、别的模组绕过事件），就让出这一格并记一条日志：那是别人的建造，
 * 比我们的回滚重要。理由很实在：借出去的位置一旦被改写，回滚时就分不清「该还原成什么」——
 * 轻则水位永久回不去，重则把别人的东西覆盖掉。
 *
 * <p>回滚触发有四种：走出范围（逐格还）、主动停止/断线、传送与换维度、关服；四条都汇到
 * {@link #stop}。快照只存内存、按维度绑定：换维度前管理器会先停掉本效果，所以不存在"跨维度还错地方"。
 */
public final class TerraformEffect implements WorldResponseEffect {

    public static final String ID = "terraform";
    private static final String TAG = "WorldResponse";

    /** 借出去的一格：原位长什么样，以及我们把它改成了什么（用于持续压实与判断有没有被第三方动过）。 */
    private record Held(BlockState original, BlockState left) {}

    private final ServerLevel level;
    /** 被借走的位置 → 原位与"我们留下的样子"。只记一次，重复扫到不覆盖原状。 */
    private final Map<BlockPos, Held> borrowed = new LinkedHashMap<>();
    private int scanCooldown;

    public TerraformEffect(ServerLevel level) {
        this.level = level;
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

    /** 借走的位置禁止第三方改动：管理器在破坏/放置事件里据此取消（见 {@link WorldResponseEffects}）。 */
    @Override
    public boolean holds(LevelAccessor level, BlockPos pos) {
        return level == this.level && borrowed.containsKey(pos);
    }

    @Override
    public void tick(ServerPlayer player) {
        if (--scanCooldown > 0) return;
        scanCooldown = Math.max(1, BalanceValues.worldResponseTerraformScanInterval());
        if (level != player.serverLevel()) return;   // 换维度后管理器会停掉本效果，这里只是兜底
        Set<Block> blacklist = WorldResponseProtectionSavedData.blacklist(level, player.getUUID());
        restoreOutOfRange(player);
        keepBorrowed();
        reshapeAround(player, blacklist);
    }

    @Override
    public boolean stop(@Nullable ServerPlayer player, boolean loadChunks) {
        int settled = 0;
        Iterator<Map.Entry<BlockPos, Held>> it = borrowed.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Held> e = it.next();
            if (!restore(e.getKey(), e.getValue().original(), loadChunks)) continue;   // 区块没加载：留着下次
            it.remove();
            settled++;
        }
        if (settled > 0) {
            Log.info(TAG, "[WorldResponse] Terraform settled {} block(s)", settled);
        }
        // 还剩下的由管理器挂起重试（它在那儿记一条日志），这里不再逐次刷屏
        return borrowed.isEmpty();
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
        if (borrowed.containsKey(pos)) return;
        if (!level.isLoaded(pos)) return;                    // 不在未加载的区块里动土
        if (!blocksMovement(pos)) return;
        if (!canTouch(pos, blacklist)) return;
        BlockState original = level.getBlockState(pos);
        // 写失败（超世界高度、debug 世界）就当没这回事：先写再记账，免得快照里留着从没被改过的方块
        if (!level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS)) return;
        borrowed.put(pos.immutable(), new Held(original, Blocks.AIR.defaultBlockState()));
    }

    /**
     * 垫脚层：这一格是液体就临时换成它的**液面替身**（{@link SolidFluidBlock}），让人能踩着水面/岩浆面走。
     *
     * @return true = 这一格已经归我们管（刚垫上、或本来就是我们的垫脚层），不必再看下面一格；
     *         false = 这一格不是液体（空气/普通方块），请调用方去看下面那一格
     */
    private boolean pave(BlockPos pos, Set<Block> blacklist) {
        if (borrowed.containsKey(pos)) return true;          // 已经是我们改过的（垫过或移开过）
        if (!level.isLoaded(pos)) return true;               // 未加载的区块不去碰，也不再往下看
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof LiquidBlock)) return false;   // 只垫"本身就是液体"的格子
        if (!canTouch(pos, blacklist)) return true;          // 名单/地皮护着：这一格不垫
        BlockState floor = floorFor(state).defaultBlockState();
        if (!level.setBlock(pos, floor, Block.UPDATE_CLIENTS)) return true;
        borrowed.put(pos.immutable(), new Held(state, floor));
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
     * 把借出去的位置重新压成我们要的样子。
     *
     * <p>为什么需要这一遍：我们用 flag 2 写入、**不通知邻居**，所以水不会主动灌回来；但只要附近
     * 有**任何**其它方块更新（别人放东西、活塞动作、流体自己流），水就可能重新流进我们挖开的空位，
     * 路就断了。每一轮扫描重新压实一次，顺带处理绕过事件保护的改写（命令、别的模组）。
     *
     * <p>真被**实心方块**盖进来（那是别人的建造）就不动它、把这一格让出去并记一条日志——
     * 与 {@link #restore} 同一口径。
     */
    private void keepBorrowed() {
        for (Iterator<Map.Entry<BlockPos, Held>> it = borrowed.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<BlockPos, Held> e = it.next();
            Held held = e.getValue();
            if (!level.isLoaded(e.getKey())) continue;               // 区块没加载：等它回来
            BlockState now = level.getBlockState(e.getKey());
            if (now.equals(held.left())) continue;                    // 还是我们留下的样子
            if (isOursOrFluid(now)) {
                // 空气/流体/我们的替身：按我们的样子压实（流体又流回来了就再挖掉）
                level.setBlock(e.getKey(), held.left(), Block.UPDATE_CLIENTS);
                continue;
            }
            Log.info(TAG, "[WorldResponse] Terraform: {} was built over — giving it up", e.getKey());
            it.remove();
        }
    }

    /** 可以安全按快照还原/压实的样子：空气、液体、或我们垫的替身/屏障。 */
    private static boolean isOursOrFluid(BlockState state) {
        return state.isAir()
                || state.getBlock() instanceof LiquidBlock
                || state.is(Blocks.BARRIER)
                || state.is(Wandscape.WORLD_RESPONSE_WATER.get())
                || state.is(Wandscape.WORLD_RESPONSE_LAVA.get());
    }

    /** 「会阻挡移动」：有碰撞箱，或者是液体（水会推人，同样算挡路）。 */
    private boolean blocksMovement(BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return false;
        if (!state.getFluidState().isEmpty()) return true;
        return !state.getCollisionShape(level, pos).isEmpty();
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

    // ── 还 ──

    /** 离开「清理半径 + 余量」的格子立刻放回：效果跟着人走，人走过之后地形不留疤。 */
    private void restoreOutOfRange(ServerPlayer player) {
        int r = Math.max(1, BalanceValues.worldResponseTerraformRadius());
        double limit = r + Math.max(1, BalanceValues.worldResponseTerraformRestoreMargin()) + 0.5;
        double limitSqr = limit * limit;
        Vec3 center = player.position();
        Iterator<Map.Entry<BlockPos, Held>> it = borrowed.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Held> e = it.next();
            if (e.getKey().distToCenterSqr(center.x, center.y, center.z) <= limitSqr) continue;
            if (!restore(e.getKey(), e.getValue().original(), false)) continue;   // 保留快照：等那一格所在的区块回来
            it.remove();
        }
    }

    /**
     * 还原一格。
     *
     * <p>只在「这一格还是我们留下的样子（或本来就该有的液体）」时才写：空气（我们移开留下的）、
     * 液体（水/岩浆可能已经流回来）、或者我们垫的替身/屏障。**真被实心方块盖进来就不覆盖**——
     * 那是别人的建造，比我们的回滚重要；遇到这种格子就放弃这一格（记一条日志）并把快照丢掉。
     *
     * @return false = 区块没加载，这一格必须留到下次（绝不为回滚同步加载区块）
     */
    private boolean restore(BlockPos pos, BlockState original, boolean loadChunks) {
        if (!loadChunks && !level.isLoaded(pos)) return false;
        BlockState now = level.getBlockState(pos);
        if (!isOursOrFluid(now)) {
            Log.info(TAG, "[WorldResponse] Terraform: {} is no longer ours — leaving it as it is", pos);
            return true;
        }
        level.setBlock(pos, original, Block.UPDATE_ALL);
        return true;
    }
}
