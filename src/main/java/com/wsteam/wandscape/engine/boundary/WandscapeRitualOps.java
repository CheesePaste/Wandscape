package com.wsteam.wandscape.engine.boundary;

import com.wsteam.wandscape.core.boundary.RitualOps;
import com.wsteam.wandscape.core.component.NavigationState;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.types.GridPos;
import com.wsteam.wandscape.core.types.RitualId;
import com.wsteam.wandscape.content.magic.data.MagicCircleSpec;
import com.wsteam.wandscape.content.magic.internal.MagicCircleLoader;
import com.wsteam.wandscape.content.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.content.npc.internal.EntityComponentBridge;
import com.wsteam.wandscape.content.task.op.api.AtomicOp;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.network.MagicCircleCastPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * MC implementation of {@link RitualOps} with async channeling.
 *
 * <p>Rituals with channelTicks > 0 (e.g. self_teleport at its bound magic-circle
 * duration) return an incomplete future. The NPC channels for the duration,
 * then the effect fires via {@code thenRun}. {@link #tickAll()} is called every
 * MC tick from {@link com.wsteam.wandscape.Wandscape#onServerTick} to decrement
 * countdowns.
 *
 * <p>Sync rituals (channelTicks = 0) execute immediately and return a completed
 * future — backward-compat with existing callers.
 *
 * <p>Channeling durations are hardcoded per ritual type, matching
 * {@link AtomicOp.RitualOp#channelTicks()}.
 */
public class WandscapeRitualOps implements RitualOps {

    private static final String TAG = "WandscapeRitualOps";

    /** 传送法阵 spec id（data/wandscape/magic_circles/self_teleport.json）。 */
    private static final String SELF_TELEPORT_CIRCLE = "self_teleport";
    /** spec 缺失时 self_teleport 的引导兜底时长（tick）。 */
    private static final int SELF_TELEPORT_FALLBACK_TICKS = 85;

    record PendingRitual(CompletableFuture<Void> future, RitualId ritual, GridPos target,
                         World world, long casterId, int remainingTicks) {}

    private final List<PendingRitual> pending = new ArrayList<>();

    @Override
    public CompletableFuture<Void> beginRitual(RitualId ritual, GridPos target, World world,
                                               long casterId, Map<String, String> params) {
        int ticks = channelTicks(ritual);

        if (ticks <= 0) {
            executeRitual(ritual, target, world, casterId);
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> future = world.startAsyncOp(
                "ritual_" + ritual.id() + "_" + casterId);
        pending.add(new PendingRitual(future, ritual, target, world, casterId, ticks));

        future.thenRun(() -> executeRitual(ritual, target, world, casterId));

        // 引导开始即在脚底与目标点生成传送法阵，法阵时长 = 引导时长，展开到最大后触发传送
        if ("self_teleport".equals(ritual.id())) {
            sendTeleportCircles(target, casterId);
        }

        Log.info(TAG, "[RitualOps] NPC {} — {} channeling {} ticks at {}",
                casterId, ritual.id(), ticks, target);
        return future;
    }

    /**
     * 引导开始时在脚底与目标点同时生成地面传送法阵（spec 驱动）。
     * 法阵时长即引导时长（见 {@link #teleportChannelTicks()}），引导结束触发传送。
     */
    private void sendTeleportCircles(GridPos target, long casterId) {
        WandscapeNpc npc = EntityComponentBridge.INSTANCE.getNpc(casterId);
        if (npc == null || npc.isRemoved() || !(npc.level() instanceof ServerLevel level)) return;

        MagicCircleSpec spec = MagicCircleLoader.getSpec(SELF_TELEPORT_CIRCLE);
        if (spec == null) {
            Log.warn(TAG, "[RitualOps] self_teleport: spec '{}' not found — no circle", SELF_TELEPORT_CIRCLE);
            return;
        }

        double h = spec.height;
        Vec3 axis = new Vec3(0, 1, 0); // 水平地面法阵
        Vec3 origin = new Vec3(npc.getX(), npc.getY() + h, npc.getZ());
        Vec3 dest = new Vec3(target.x() + 0.5, target.y() + h, target.z() + 0.5);

        PacketDistributor.sendToPlayersTrackingEntity(npc,
                new MagicCircleCastPacket(UUID.randomUUID(), origin, axis, SELF_TELEPORT_CIRCLE));
        PacketDistributor.sendToPlayersTrackingChunk(level, new ChunkPos(BlockPos.containing(dest)),
                new MagicCircleCastPacket(UUID.randomUUID(), dest, axis, SELF_TELEPORT_CIRCLE));
    }

    /** Called every MC tick. Decrements countdowns and completes futures. */
    public void tickAll() {
        if (pending.isEmpty()) return;

        List<CompletableFuture<Void>> toComplete = new ArrayList<>();

        for (int i = 0; i < pending.size(); i++) {
            PendingRitual p = pending.get(i);
            int remaining = p.remainingTicks() - 1;
            if (remaining <= 0) {
                toComplete.add(p.future());
            } else {
                pending.set(i, new PendingRitual(p.future(), p.ritual(), p.target(),
                        p.world(), p.casterId(), remaining));
            }
        }

        for (CompletableFuture<Void> f : toComplete) {
            f.complete(null); // → triggers thenRun → executeRitual
        }

        pending.removeIf(p -> p.future().isDone());

        if (!toComplete.isEmpty()) {
            Log.info(TAG, "[RitualOps] tickAll: {} completed, {} remaining",
                    toComplete.size(), pending.size());
        }
    }

    public boolean hasPendingOps() {
        return !pending.isEmpty();
    }

    // ── Channel ticks per ritual type ──

    /** 各仪式引导时长（tick）；self_teleport 取绑定法阵时长。NavigationSystem 用它同步施法锁。 */
    public static int channelTicks(RitualId ritual) {
        return switch (ritual.id()) {
            case "self_teleport" -> teleportChannelTicks();
            case "item_teleport", "player_summon" -> 1; // TODO: restore to 600 after testing
            case "warding" -> 200;
            case "group_vigor" -> 400;
            case "rain_call", "clear_weather" -> 1200;
            case "portal_gate" -> 1800;
            default -> 0;
        };
    }

    /** self_teleport 引导时长 = 绑定的传送法阵时长，使法阵完整展开后触发传送。 */
    public static int teleportChannelTicks() {
        MagicCircleSpec spec = MagicCircleLoader.getSpec(SELF_TELEPORT_CIRCLE);
        return spec != null ? spec.durationTicks : SELF_TELEPORT_FALLBACK_TICKS;
    }

    // ── Ritual execution ──

    private void executeRitual(RitualId ritual, GridPos target, World world, long casterId) {
        if ("self_teleport".equals(ritual.id())) {
            WandscapeNpc npc = EntityComponentBridge.INSTANCE.getNpc(casterId);
            if (npc != null && !npc.isRemoved()) {
                double fromX = npc.getX();
                double fromY = npc.getY();
                double fromZ = npc.getZ();
                // 传送到安全落点，避免落进实体方块/建筑内部窒息；找不到安全落点则放弃传送，避免进墙窒息或高空坠亡
                Vec3 dest = null;
                if (npc.level() instanceof ServerLevel serverLevel) {
                    dest = findSafeLanding(serverLevel, target);
                }
                if (dest != null) {
                    npc.teleportTo(dest.x, dest.y, dest.z);
                    // 落点可能超出到达半径，把导航状态拨回 PATHFINDING，让 NavigationSystem 走完剩余距离
                    //（已到则下一 tick 判到），避免停在 TELEPORT_RITUAL 空转
                    NavigationState nav = world.get(casterId, NavigationState.class);
                    if (nav != null && nav.mode == NavigationState.Mode.TELEPORT_RITUAL) {
                        nav.mode = NavigationState.Mode.PATHFINDING;
                        nav.startTick = 0;
                    }
                    // 末影人式传送爆点：起点（消失）+ 终点（出现）
                    spawnPortalBurst(npc.level(), fromX, fromY, fromZ);
                    spawnPortalBurst(npc.level(), npc.getX(), npc.getY(), npc.getZ());
                    Log.info(TAG, "[RitualOps] self_teleport: NPC {} → {} (dest {},{},{})",
                            casterId, target, dest.x, dest.y, dest.z);
                } else {
                    Log.warn(TAG, "[RitualOps] self_teleport: no safe landing near {} — aborting teleport to prevent suffocation/fall", target);
                    NavigationState nav = world.get(casterId, NavigationState.class);
                    if (nav != null) {
                        nav.reset();
                    }
                }
            } else {
                Log.warn(TAG, "[RitualOps] self_teleport: NPC not found for casterId {}", casterId);
            }
            return;
        }

        Log.warn(TAG, "[RitualOps] Unknown ritual '{}' at {} — no-op", ritual.id(), target);
    }

    /**
     * 在目标点附近搜索可安全落地的位置：NPC 脚/头两格无碰撞、不落入液体、脚下有坚实地面支撑且非危险方块。
     * 两遍搜索：先要求严格双层实心地面（首选，落地即站稳）；失败后放宽为单层实心/台阶支撑面（但绝不悬空）。
     * 返回 {@code null} 表示附近无可立足处（调用方取消传送，防止悬空坠亡或卡墙窒息）。
     */
    private static Vec3 findSafeLanding(ServerLevel level, GridPos target) {
        for (int r = 0; r <= 4; r++) {
            Vec3 spot = scanShell(level, target, r, true);
            if (spot != null) return spot;
        }
        for (int r = 0; r <= 6; r++) {
            Vec3 spot = scanShell(level, target, r, false);
            if (spot != null) return spot;
        }
        return null;
    }

    /** 逃生搜索最大半径：保证传送后远离危险点（岩浆/窒息区域），又不会跳得过远。 */
    public static final int ESCAPE_SEARCH_RADIUS = 16;

    /**
     * 逃生传送目标：在 {@code origin} 周围 r=4..16 的方形外壳上找最近安全落点。
     * 从 r=4 起步（至少离开危险点 4 格，避免原地 no-op 传送）；先要求实心地面，
     * 失败后放宽为单层支撑面（绝不悬空）。返回 {@code null} 表示附近无可逃处（如超大岩浆湖）。
     */
    public static Vec3 findSafeEscapeLanding(ServerLevel level, GridPos origin) {
        for (int r = 4; r <= ESCAPE_SEARCH_RADIUS; r++) {
            Vec3 spot = scanShell(level, origin, r, true);
            if (spot != null) return spot;
        }
        for (int r = 4; r <= ESCAPE_SEARCH_RADIUS; r++) {
            Vec3 spot = scanShell(level, origin, r, false);
            if (spot != null) return spot;
        }
        return null;
    }

    /** 搜索半径 {@code r} 的方形外壳，按 Y 轴垂直距离由近及远搜索安全地面。 */
    private static Vec3 scanShell(ServerLevel level, GridPos target, int r, boolean requireSolidGround) {
        // Y 轴相对偏移由近及远尝试：0, +1, -1, +2, -2, +3, -3, +4
        int[] dyOrder = {0, 1, -1, 2, -2, 3, -3, 4};

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                int x = target.x() + dx;
                int z = target.z() + dz;
                for (int dy : dyOrder) {
                    int y = target.y() + dy;
                    if (isSafeLanding(level, x, y, z, requireSolidGround)) {
                        return new Vec3(x + 0.5, y, z + 0.5);
                    }
                }
            }
        }
        return null;
    }

    /** 危险方块检查：岩浆、火焰、仙人掌、细雪、营火、凋灵玫瑰等造成伤害或窒息下陷的方块。 */
    private static boolean isHazardBlock(BlockState state) {
        return state.is(Blocks.LAVA)
                || state.is(Blocks.FIRE)
                || state.is(Blocks.SOUL_FIRE)
                || state.is(Blocks.CAMPFIRE)
                || state.is(Blocks.SOUL_CAMPFIRE)
                || state.is(Blocks.CACTUS)
                || state.is(Blocks.POWDER_SNOW)
                || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.WITHER_ROSE);
    }

    /** 立足点 {@code (x,y,z)}（y 为脚底 Y）是否安全：所在区块已加载、脚/头两格均无碰撞且非液体、脚下有坚实地面支撑（绝不悬空）。 */
    private static boolean isSafeLanding(ServerLevel level, int x, int y, int z, boolean requireSolidGround) {
        BlockPos feet = new BlockPos(x, y, z);
        BlockPos head = new BlockPos(x, y + 1, z);
        BlockPos ground = new BlockPos(x, y - 1, z);
        if (!level.isLoaded(feet) || !level.isLoaded(head) || !level.isLoaded(ground)) {
            return false;
        }
        BlockState feetState = level.getBlockState(feet);
        BlockState headState = level.getBlockState(head);
        if (!feetState.getFluidState().isEmpty() || !headState.getFluidState().isEmpty()) {
            return false;
        }
        if (!feetState.getCollisionShape(level, feet).isEmpty()
                || !headState.getCollisionShape(level, head).isEmpty()) {
            return false;
        }

        BlockState groundState = level.getBlockState(ground);
        // 脚下不能是流体、不能是危险伤害方块、也不能是空气/无碰撞悬空
        if (!groundState.getFluidState().isEmpty() || isHazardBlock(groundState)) {
            return false;
        }

        if (requireSolidGround) {
            // 严格模式：脚下与下方第二格都须为实心立方块——排除台阶/楼梯/单格薄板等假地面
            return groundState.isSolid() && level.getBlockState(ground.below()).isSolid();
        } else {
            // 宽松模式：允许单层实心方块或台阶等，但必须有碰撞箱支撑，绝不允许悬空（空气/无碰撞）
            return !groundState.getCollisionShape(level, ground).isEmpty();
        }
    }

    /** 末影人传送式 PORTAL 爆点（环绕身体，16 粒）。 */
    private static void spawnPortalBurst(Level level, double x, double y, double z) {
        for (int i = 0; i < 16; i++) {
            double ox = (level.random.nextDouble() - 0.5);
            double oy = level.random.nextDouble() * 2.0;
            double oz = (level.random.nextDouble() - 0.5);
            level.addParticle(ParticleTypes.PORTAL, x + ox, y + oy, z + oz, 0, 0, 0);
        }
    }
}
