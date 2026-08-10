package com.wsteam.wandscape.engine.boundary;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.wsteam.wandscape.core.boundary.RitualOps;
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
    private static final int SELF_TELEPORT_FALLBACK_TICKS = 170;

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
                npc.teleportTo(target.x() + 0.5, target.y(), target.z() + 0.5);
                // 末影人式传送爆点：起点（消失）+ 终点（出现）
                spawnPortalBurst(npc.level(), fromX, fromY, fromZ);
                spawnPortalBurst(npc.level(), npc.getX(), npc.getY(), npc.getZ());
                Log.info(TAG, "[RitualOps] self_teleport: NPC {} → {}", casterId, target);
            } else {
                Log.warn(TAG, "[RitualOps] self_teleport: NPC not found for casterId {}", casterId);
            }
            return;
        }

        Log.warn(TAG, "[RitualOps] Unknown ritual '{}' at {} — no-op", ritual.id(), target);
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
