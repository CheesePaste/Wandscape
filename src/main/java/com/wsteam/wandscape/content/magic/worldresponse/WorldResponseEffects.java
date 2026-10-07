package com.wsteam.wandscape.content.magic.worldresponse;

import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.ui.I18n;
import com.wsteam.wandscape.foundation.util.BalanceValues;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.PistonEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 持续型世界回应的生命周期管理（每个玩家一份「正在生效的效果」表）。
 *
 * <p>它同时是**所有回滚的唯一入口**：正常停止、玩家断线、**传送 / 换维度**、关服，五条路都汇到
 * {@link #stopAll} —— 因为效果改的是真实地形，「效果没了地形没还」是不能接受的孤儿状态。
 * 这也是企划案里「持续时间 infinity 但必须有主动关闭」的落地方式：{@link WorldResponseExecutors}
 * 负责开，配套魔法《平息》（{@code world_response_calm}）与这里负责收。
 *
 * <p><b>传送要在传送发生之前停</b>：快照记的是**原地**的方块坐标，人被传到很远之后那些区块会卸载，
 * 回滚就得回头去碰已经不在内存里的区块。所以监听的是 `EntityTravelToDimensionEvent`（换维度之前）与
 * `EntityTeleportEvent`（/tp、末影珍珠、紫颂果、spreadplayers 等），而不是事后事件。
 * 整合包里有些传送（传送石碑之类）不走这两个事件，于是再加一条兜底：**每 tick 位移超过
 * {@code worldResponseTeleportJumpDistance} 格**（正常跑跳/鞘翅/激流都到不了）就按传送处理。
 * 停下之后玩家重新施放即可——比起"顺手把某台机器的核心方块留在地上一个大洞"，宁可少维持一会儿。
 *
 * <p><b>还不了的方块不许丢</b>：回滚遇到未加载的区块时效果会返回 false，这里把它挂进
 * {@link #PENDING_ROLLBACK} 每 {@value #ROLLBACK_RETRY_TICKS} tick 重试一次（区块回来了就还上）；
 * 关服那一次是最后机会，允许为此把区块读回来——`ServerStoppingEvent` 在存档之前触发
 * （`MinecraftServer.runServer` 的 finally 里先 handleServerStopping 再 stopServer），所以这一次
 * 写回去的方块会落盘。非正常关服（崩溃/断电）仍可能留下坑，这是已知取舍。
 */
public final class WorldResponseEffects {

    private static final String TAG = "WorldResponse";
    /** 回滚重试间隔（tick）：给未加载区块留出"回来"的时间，也避免每 tick 遍历。 */
    private static final int ROLLBACK_RETRY_TICKS = 20;
    /** 借用点提示的节流（tick）：玩家对着借用点猛按时不至于刷屏。 */
    private static final int BORROW_NOTICE_TICKS = 10;
    /** 活塞推程要扫的格数：原版最多推 12 格，外加活塞头占的那一格。 */
    private static final int PISTON_LINE = 13;

    private static final Map<UUID, Map<String, WorldResponseEffect>> ACTIVE = new ConcurrentHashMap<>();

    /**
     * 还没还干净的效果：两种来源——① 有方块压在**未加载区块**里（等区块回来）；
     * ② 扶摇停下时玩家还踩在上面，刻意先留着当落脚点（等他自己走开）。
     * 两者都带上玩家 UUID，重试时必须把玩家找回来问一声（人不在就直接还）。
     */
    private record PendingRollback(UUID playerId, WorldResponseEffect effect) {}

    private static final List<PendingRollback> PENDING_ROLLBACK = new CopyOnWriteArrayList<>();
    /** 上一 tick 的位置，用来识别"没有事件的那种传送"。 */
    private static final Map<UUID, Vec3> LAST_POS = new ConcurrentHashMap<>();
    /** 「这一格正被借用」的提示节流表（防刷屏）。 */
    private static final Map<UUID, Long> BORROW_NOTICE_AT = new ConcurrentHashMap<>();
    private static int retryCooldown;
    private static boolean registered;

    private WorldResponseEffects() {}

    /** 注册全局钩子；重复调用无效（单机反复进出世界会重复走这里）。 */
    public static void register() {
        if (registered) return;
        registered = true;
        var bus = NeoForge.EVENT_BUS;
        bus.addListener(ServerTickEvent.Post.class, WorldResponseEffects::onServerTick);
        bus.addListener(PlayerEvent.PlayerLoggedOutEvent.class, WorldResponseEffects::onLoggedOut);
        // 传送/换维度一律用「之前」的事件：这两条在传送发生前触发，回滚还来得及在原地区块里做完
        bus.addListener(EntityTravelToDimensionEvent.class, WorldResponseEffects::onTravelToDimension);
        bus.addListener(EntityTeleportEvent.class, WorldResponseEffects::onTeleport);
        bus.addListener(PlayerEvent.PlayerChangedDimensionEvent.class, WorldResponseEffects::onChangedDimension);
        bus.addListener(LivingIncomingDamageEvent.class, WorldResponseEffects::onIncomingDamage);
        // 借出去的位置禁止第三方改动：破坏 / 放置 / 流体在那一格造方块（黑曜石、石头之类）
        bus.addListener(BlockEvent.BreakEvent.class, WorldResponseEffects::onBreakBorrowed);
        bus.addListener(BlockEvent.EntityPlaceEvent.class, WorldResponseEffects::onPlaceBorrowed);
        bus.addListener(BlockEvent.FluidPlaceBlockEvent.class, WorldResponseEffects::onFluidPlaceBorrowed);
        bus.addListener(PlayerInteractEvent.LeftClickBlock.class, WorldResponseEffects::onLeftClickBorrowed);
        // 活塞推东西也不许推到这个范围里（方块被推进来、或活塞头伸进来都算改动）
        bus.addListener(PistonEvent.Pre.class, WorldResponseEffects::onPistonPre);
        bus.addListener(ServerStoppingEvent.class, e -> stopAll(e.getServer()));
        Log.info(TAG, "[WorldResponse] Persistent effect manager registered");
    }

    /** 激活一个持续效果；同 id 已生效时返回 false（不重复叠加）。 */
    public static boolean activate(ServerPlayer player, WorldResponseEffect effect) {
        if (player == null || effect == null) return false;
        Map<String, WorldResponseEffect> mine =
                ACTIVE.computeIfAbsent(player.getUUID(), k -> new ConcurrentHashMap<>());
        if (mine.containsKey(effect.id())) return false;
        mine.put(effect.id(), effect);
        Log.info(TAG, "[WorldResponse] '{}' activated for {}", effect.id(), player.getGameProfile().getName());
        return true;
    }

    public static boolean isActive(ServerPlayer player, String effectId) {
        Map<String, WorldResponseEffect> mine = ACTIVE.get(player.getUUID());
        return mine != null && mine.containsKey(effectId);
    }

    public static boolean hasAny(ServerPlayer player) {
        Map<String, WorldResponseEffect> mine = ACTIVE.get(player.getUUID());
        return mine != null && !mine.isEmpty();
    }

    /** 停止该玩家全部持续效果并回滚；返回停掉的数量。 */
    public static int stopAll(ServerPlayer player) {
        if (player == null) return 0;
        Map<String, WorldResponseEffect> mine = ACTIVE.remove(player.getUUID());
        LAST_POS.remove(player.getUUID());
        BORROW_NOTICE_AT.remove(player.getUUID());
        if (mine == null || mine.isEmpty()) return 0;
        for (WorldResponseEffect effect : mine.values()) {
            rollback(player, effect);
        }
        Log.info(TAG, "[WorldResponse] Stopped {} effect(s) for {}",
                mine.size(), player.getGameProfile().getName());
        return mine.size();
    }

    /**
     * 回滚一个效果；没还干净就挂进重试表，**不丢快照**。
     *
     * <p>没还干净的两种情形：方块压在未加载区块里（等区块回来），或扶摇刻意留着落脚点（等人走开）。
     */
    private static void rollback(@Nullable ServerPlayer player, WorldResponseEffect effect) {
        try {
            if (!effect.stop(player, false)) {
                PENDING_ROLLBACK.add(new PendingRollback(player != null ? player.getUUID() : null, effect));
                Log.info(TAG, "[WorldResponse] '{}' not fully restored yet — queued for retry", effect.id());
            }
        } catch (RuntimeException ex) {
            // 回滚失败必须看得见：宁可留下日志也不要静默把地形留在半途
            Log.warn(TAG, "[WorldResponse] Failed to roll back '{}': {}", effect.id(), ex.toString());
        }
    }

    private static void stopAll(MinecraftServer server) {
        if (server == null) return;
        for (ServerPlayer player : new ArrayList<>(server.getPlayerList().getPlayers())) {
            stopAll(player);
        }
        ACTIVE.clear();
        LAST_POS.clear();
        BORROW_NOTICE_AT.clear();
        forceFinalRollback(server);
    }

    /** 关服收尾：这是最后一次机会，允许为回滚把未加载的区块读回来。 */
    private static void forceFinalRollback(MinecraftServer server) {
        if (PENDING_ROLLBACK.isEmpty()) return;
        retryPending(server, true);
        if (!PENDING_ROLLBACK.isEmpty()) {
            Log.warn(TAG, "[WorldResponse] {} effect(s) still hold un-restored block(s) — the world may keep a hole",
                    PENDING_ROLLBACK.size());
            PENDING_ROLLBACK.clear();
        }
    }

    /**
     * 重试回滚。
     *
     * <p>必须把**玩家**找回来一起问：扶摇停下之后是"玩家还踩着就先留着"，拿不到玩家就只能直接还。
     * 玩家已离线时自然拿到 null，那时直接还正是我们要的。
     */
    private static void retryPending(MinecraftServer server, boolean loadChunks) {
        for (PendingRollback pending : PENDING_ROLLBACK) {
            ServerPlayer player = server != null && pending.playerId() != null
                    ? server.getPlayerList().getPlayer(pending.playerId()) : null;
            boolean done;
            try {
                done = pending.effect().stop(player, loadChunks);
            } catch (RuntimeException ex) {
                Log.warn(TAG, "[WorldResponse] Retried rollback of '{}' failed: {}", pending.effect().id(), ex.toString());
                done = true;   // 一直抛异常的效果不该每 20 tick 刷一次屏
            }
            if (done) PENDING_ROLLBACK.remove(pending);
        }
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        if (--retryCooldown <= 0) {
            retryCooldown = ROLLBACK_RETRY_TICKS;
            if (!PENDING_ROLLBACK.isEmpty()) retryPending(event.getServer(), false);
        }
        if (ACTIVE.isEmpty()) {
            if (!LAST_POS.isEmpty()) LAST_POS.clear();
            return;
        }
        MinecraftServer server = event.getServer();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Map<String, WorldResponseEffect> mine = ACTIVE.get(player.getUUID());
            if (mine == null || mine.isEmpty()) {
                LAST_POS.remove(player.getUUID());
                continue;
            }
            if (jumpedTooFar(player)) {   // 没有事件的那种传送（整合包传送石碑等）的兜底
                Log.info(TAG, "[WorldResponse] {} moved too far in one tick — treating it as a teleport",
                        player.getGameProfile().getName());
                stopForTeleport(player);
                continue;
            }
            for (WorldResponseEffect effect : mine.values()) {
                try {
                    effect.tick(player);
                } catch (RuntimeException ex) {
                    Log.warn(TAG, "[WorldResponse] '{}' tick failed for {}: {} — stopping it",
                            effect.id(), player.getGameProfile().getName(), ex.toString());
                    stopEffect(player, effect.id());
                }
            }
        }
    }

    /**
     * 单 tick 位移突变检测。阈值默认 16 格：玩家自由落体终速约 4 格/tick、鞘翅火箭与激流远低于它，
     * 所以正常的"跑得快"不会误伤；而整合包传送石碑这类没有事件的传送通常是几十到上千格。
     */
    private static boolean jumpedTooFar(ServerPlayer player) {
        Vec3 now = player.position();
        Vec3 last = LAST_POS.put(player.getUUID(), now);
        if (last == null) return false;
        double limit = Math.max(4, BalanceValues.worldResponseTeleportJumpDistance());
        return now.distanceToSqr(last) > limit * limit;
    }

    private static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            stopAll(player);   // 带着效果断线会让地形留坑：登出即回滚
            WorldResponseManager.cancelPending(player.getUUID());   // 人都走了，留着选择窗口没意义
        }
    }

    /**
     * 环境热伤害免疫：效果自己声明要不要（{@link WorldResponseEffect#wardsHeat()}），
     * 「让路顺便别把人烫伤」这条判断只有一份。
     */
    private static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!isEnvironmentalHeat(event.getSource())) return;
        Map<String, WorldResponseEffect> mine = ACTIVE.get(player.getUUID());
        if (mine == null) return;
        for (WorldResponseEffect effect : mine.values()) {
            if (effect.wardsHeat()) {
                event.setCanceled(true);
                return;
            }
        }
    }

    /**
     * 「环境热」：岩浆、岩浆块、站在火里、身上着火。
     *
     * <p>刻意**不**用 {@code DamageTypeTags.IS_FIRE}：那个标签连火球一起收（见原版
     * {@code DamageTypeTagsProvider}），拿它挡伤害等于顺手给了玩家一份火焰免疫。
     */
    private static boolean isEnvironmentalHeat(DamageSource source) {
        return source.is(DamageTypes.LAVA) || source.is(DamageTypes.HOT_FLOOR)
                || source.is(DamageTypes.IN_FIRE) || source.is(DamageTypes.ON_FIRE);
    }

    // ── 借用点：禁止第三方改动 ──

    /**
     * 这个位置是不是正被某个效果借用（借出去的位置禁止改动，理由见 WorldResponseEffect#holds）。
     *
     * <p>**已经停下、但还有方块压在未加载区块里等重试的那些效果也算**：它们手里照样握着这些格子，
     * 不护着的话，别人一改，那次补做的回滚就只能放弃这一格（地形永久回不去）。
     */
    public static boolean isBorrowed(LevelAccessor level, BlockPos pos) {
        if (level == null || pos == null) return false;
        if (ACTIVE.isEmpty() && PENDING_ROLLBACK.isEmpty()) return false;
        for (Map<String, WorldResponseEffect> mine : ACTIVE.values()) {
            for (WorldResponseEffect effect : mine.values()) {
                if (effect.holds(level, pos)) return true;
            }
        }
        for (PendingRollback pending : PENDING_ROLLBACK) {
            if (pending.effect().holds(level, pos)) return true;
        }
        return false;
    }

    /**
     * 让**优先级严格更低**的效果把这一格先还回原位，给更高的腾位置（当前口径：移山填海 > 扶摇）。
     *
     * <p>为什么不是「直接抢」：抢来的话，高优先级那边读到的当前状态是低优先级留下的方块，会被当成
     * **原位**记下来，最后回滚出一个谁都没见过的方块。让低的先还回原位，高的读到的才是真原位。
     *
     * <p>有待重试的（{@link #PENDING_ROLLBACK}）也算持有者——它们手里照样握着格子。
     */
    public static void releaseFor(LevelAccessor level, BlockPos pos, int priority) {
        if (level == null || pos == null) return;
        if (ACTIVE.isEmpty() && PENDING_ROLLBACK.isEmpty()) return;
        for (Map<String, WorldResponseEffect> mine : ACTIVE.values()) {
            for (WorldResponseEffect effect : mine.values()) {
                releaseOne(level, pos, priority, effect);
            }
        }
        for (PendingRollback pending : PENDING_ROLLBACK) {
            releaseOne(level, pos, priority, pending.effect());
        }
    }

    private static void releaseOne(LevelAccessor level, BlockPos pos, int priority, WorldResponseEffect effect) {
        if (effect.borrowPriority() >= priority) return;      // 同级 / 更高优先级：不让
        if (!effect.holds(level, pos)) return;
        try {
            effect.release(level, pos);
        } catch (RuntimeException ex) {
            Log.warn(TAG, "[WorldResponse] '{}' failed to release {}: {}", effect.id(), pos, ex.toString());
        }
    }

    private static void onBreakBorrowed(BlockEvent.BreakEvent event) {
        if (!isBorrowed(event.getLevel(), event.getPos())) return;
        event.setCanceled(true);
        if (event.getPlayer() instanceof ServerPlayer player) borrowNotice(player);
    }

    private static void onPlaceBorrowed(BlockEvent.EntityPlaceEvent event) {
        // 挂在父类上：EntityMultiPlaceEvent（床/门那种一下放多格）也会进来
        if (!isBorrowed(event.getLevel(), event.getPos())) return;
        event.setCanceled(true);
        if (event.getEntity() instanceof ServerPlayer player) {
            borrowNotice(player);
        } else {
            // 非玩家实体（别的模组的机器之类）同样拦下，只记一条日志
            Log.info(TAG, "[WorldResponse] Blocked a placement by {} at a borrowed spot {}",
                    event.getEntity() == null ? "?" : event.getEntity().getType(), event.getPos());
        }
    }

    private static void onFluidPlaceBorrowed(BlockEvent.FluidPlaceBlockEvent event) {
        // 流体想在借用点造方块（水+岩浆=石头/黑曜石这类）：拦掉，否则那一格就永久变了样
        if (isBorrowed(event.getLevel(), event.getPos())) event.setCanceled(true);
    }

    private static void onLeftClickBorrowed(PlayerInteractEvent.LeftClickBlock event) {
        // 垫脚替身本来就不可破坏（挖不动、也没有破坏事件），所以这里主要是给玩家一个解释
        if (event.getLevel().isClientSide()) return;
        if (!isBorrowed(event.getLevel(), event.getPos())) return;
        event.setCanceled(true);
        if (event.getEntity() instanceof ServerPlayer player) borrowNotice(player);
    }

    /** 借用点**及其外面一圈**（上下左右前后 6 格）：活塞推程扫到它就不许动。 */
    private static boolean isBorrowedOrBeside(LevelAccessor level, BlockPos pos) {
        if (isBorrowed(level, pos)) return true;
        for (Direction direction : Direction.values()) {
            if (isBorrowed(level, pos.relative(direction))) return true;
        }
        return false;
    }

    /**
     * 活塞：只要它的推程线扫到借用点**或借用点外面那一圈**，这个活塞这一下就不动。
     *
     * <p>借用点本身靠这条线就够挡（方块被推进来的话，目的地必然是借用点，落在线上）；外面那一圈是
     * 按「这一带在借用期间冻结」的口径一起挡的——宁可让机械停一下，也不要在借用期间改动这一带。
     * 原版最多推 12 格，加上活塞头占的那一格，所以扫脸前 {@value #PISTON_LINE} 格。
     */
    private static void onPistonPre(PistonEvent.Pre event) {
        Direction direction = event.getDirection();
        BlockPos piston = event.getPos();
        for (int i = 1; i <= PISTON_LINE; i++) {
            if (isBorrowedOrBeside(event.getLevel(), piston.relative(direction, i))) {
                event.setCanceled(true);
                Log.info(TAG, "[WorldResponse] Blocked a piston at {} — its push line hits a borrowed spot", piston);
                return;
            }
        }
    }

    /** 玩家在借用点动手时给一次解释；同一人 {@value #BORROW_NOTICE_TICKS} tick 内只提示一次。 */
    private static void borrowNotice(ServerPlayer player) {
        long now = player.serverLevel().getGameTime();
        Long last = BORROW_NOTICE_AT.get(player.getUUID());
        if (last != null && now - last < BORROW_NOTICE_TICKS) return;
        BORROW_NOTICE_AT.put(player.getUUID(), now);
        player.displayClientMessage(I18n.name("message.wandscape.world_response.borrowed",
                "§7这一处正被【世界应答】借用，暂时改不了（用【平息】可以收回）"), true);
    }

    private static void onTravelToDimension(EntityTravelToDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            stopForTeleport(player);   // 换维度之前：这时人还在原维度，快照所在区块必然加载着
        }
    }

    private static void onTeleport(EntityTeleportEvent event) {
        // 这个监听器挂在父类上，子类（/tp、spreadplayers、末影珍珠、紫颂果、末影人瞬移）都会进来；
        // 非玩家实体的瞬移会被下面的 instanceof 挡掉
        if (event.getEntity() instanceof ServerPlayer player) {
            stopForTeleport(player);
        }
    }

    private static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            stopForTeleport(player);   // 上面那条 pre 事件没兜住时的补网（已经停过就不会重复提示）
        }
    }

    private static void stopForTeleport(ServerPlayer player) {
        // 还没选完的轮盘窗口也一并作废：开着轮盘被传走再确认，效果会落在新位置
        WorldResponseManager.cancelPending(player.getUUID());
        if (stopAll(player) <= 0) return;
        // 传送把它打断这件事得说一声：不然玩家只会看到地形还回去了、魔法无声消失
        player.displayClientMessage(I18n.name("message.wandscape.world_response.teleport_stop",
                "传送打断了世界应答，需要时再施放一次"), true);
    }

    private static void stopEffect(ServerPlayer player, String effectId) {
        Map<String, WorldResponseEffect> mine = ACTIVE.get(player.getUUID());
        if (mine == null) return;
        WorldResponseEffect effect = mine.remove(effectId);
        if (effect == null) return;
        if (mine.isEmpty()) ACTIVE.remove(player.getUUID());
        rollback(player, effect);
    }

    /** 只读快照，供调试/测试命令查询（不要拿它改状态）。 */
    public static List<String> activeIds(ServerPlayer player) {
        Map<String, WorldResponseEffect> mine = ACTIVE.get(player.getUUID());
        if (mine == null) return List.of();
        return Collections.unmodifiableList(new ArrayList<>(mine.keySet()));
    }
}
