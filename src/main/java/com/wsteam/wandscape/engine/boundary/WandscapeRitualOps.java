package com.wsteam.wandscape.engine.boundary;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.wsteam.wandscape.core.boundary.RitualOps;
import com.wsteam.wandscape.core.component.NavigationState;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.types.GridPos;
import com.wsteam.wandscape.core.types.RitualId;
import com.wsteam.wandscape.magic.data.MagicCircleSpec;
import com.wsteam.wandscape.magic.internal.MagicCircleLoader;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.npc.internal.EntityComponentBridge;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.network.MagicCircleCastPacket;

import com.wsteam.wandscape.op.api.AtomicOp;
import com.wsteam.wandscape.engine.nav.LevelTerrainView;
import com.wsteam.wandscape.engine.nav.StandableTerrain;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

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
        // ── self_teleport 预引导安全门控 ──
        // 施法锁/魔力在进入引导前就已消耗，绝不能等 85 tick 引导结束才发现落点在墙里/悬空。
        // 进入引导前先找安全落点，找不到立即返回 failedFuture（不 startAsyncOp、不发法阵、
        // 不进 pending），让 TaskExecutionSystem 走异常路径把任务释放回池，避免原地坠亡/循环。
        if ("self_teleport".equals(ritual.id())) {
            ServerLevel casterLevel = casterServerLevel(casterId);
            if (casterLevel == null || findSafeLanding(casterLevel, target) == null) {
                Log.warn(TAG, "[RitualOps] self_teleport: no safe landing near {} — aborting teleport", target);
                return CompletableFuture.failedFuture(
                        new IllegalStateException("self_teleport: no safe landing near " + target));
            }
        }

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
                // 传送到安全落点，避免落进实体方块/建筑内部窒息。预引导门控已保证能找到，
                // 这里不再回退原目标（原目标可能在墙里——那正是传进矿洞/坠亡的根因）。
                Vec3 dest = null;
                if (npc.level() instanceof ServerLevel serverLevel) {
                    dest = findSafeLanding(serverLevel, target);
                }
                if (dest == null) {
                    // 极端兜底（世界在引导期间被改动）：不传送，把导航拨回 IDLE，
                    // 避免停在 TELEPORT_RITUAL 空转或原地重传送。
                    Log.warn(TAG, "[RitualOps] self_teleport: no safe landing near {} — cancelling teleport", target);
                    NavigationState nav = world.get(casterId, NavigationState.class);
                    if (nav != null) nav.reset();
                    return;
                }
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
                Log.warn(TAG, "[RitualOps] self_teleport: NPC not found for casterId {}", casterId);
            }
            return;
        }

        Log.warn(TAG, "[RitualOps] Unknown ritual '{}' at {} — no-op", ritual.id(), target);
    }

    /** 施法者所在 ServerLevel；实体已移除/非服务端返回 null。 */
    private static ServerLevel casterServerLevel(long casterId) {
        WandscapeNpc npc = EntityComponentBridge.INSTANCE.getNpc(casterId);
        return npc != null && npc.level() instanceof ServerLevel sl ? sl : null;
    }

    /**
     * 传送落点：在目标点附近搜索「真实可站立地面」。每个候选列吸附到它<strong>顶</strong>表面
     * （绝不进入洞腔），并要求脚下两格实心（排除台阶/单格薄板/悬崖假地面）。半径 0..4 收紧，
     * 0..6 放宽仍要求实心地面——不再有「允许悬空下落」的放宽路径，杜绝落到洞顶/悬空坠亡。
     * 返回 {@code null} 表示即便放宽半径也无真实地面。
     */
    private static Vec3 findSafeLanding(ServerLevel level, GridPos target) {
        double[] spot = StandableTerrain.findSafeLanding(new LevelTerrainView(level),
                target.x(), target.y(), target.z(), 0, 4);
        if (spot == null) {
            spot = StandableTerrain.findSafeLanding(new LevelTerrainView(level),
                    target.x(), target.y(), target.z(), 0, 6);
        }
        return spot != null ? new Vec3(spot[0], spot[1], spot[2]) : null;
    }

    /** 逃生搜索最大半径：保证传送后远离危险点（岩浆/窒息区域），又不会跳得过远。 */
    public static final int ESCAPE_SEARCH_RADIUS = 16;

    /**
     * 逃生传送目标：在 {@code origin} 周围 r=4..16 的方形外壳上找最近安全落点。
     * 从 r=4 起步（至少离开危险点 4 格，避免原地 no-op 传送）；先要求实心地面，
     * 失败后放宽为「仅不窒息」（仍排除液体，避免岩浆/洞顶悬空），保证能从岩浆湖逃离。
     * 返回 {@code null} 表示附近无可逃处。
     */
    public static Vec3 findSafeEscapeLanding(ServerLevel level, GridPos origin) {
        double[] spot = StandableTerrain.findSafeEscapeLanding(new LevelTerrainView(level),
                origin.x(), origin.y(), origin.z(), 4, ESCAPE_SEARCH_RADIUS);
        return spot != null ? new Vec3(spot[0], spot[1], spot[2]) : null;
    }

    /** 末影人传送式 PORTAL 爆点（环绕身体，16 粒）。 */
    private static void spawnPortalBurst(Level level, double x, double y, double z) {
        for (int i = 0; i < 16; i++) {
            double ox = (level.random.nextDouble() - 0.5) * 1.0;
            double oy = level.random.nextDouble() * 2.0;
            double oz = (level.random.nextDouble() - 0.5) * 1.0;
            level.addParticle(ParticleTypes.PORTAL, x + ox, y + oy, z + oz, 0, 0, 0);
        }
    }
}
