package com.wsteam.wandscape.engine.boundary;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.wsteam.wandscape.core.boundary.RitualOps;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.types.GridPos;
import com.wsteam.wandscape.core.types.RitualId;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.npc.internal.EntityComponentBridge;

import com.wsteam.wandscape.op.api.AtomicOp;
import net.minecraft.core.particles.ParticleTypes;
import com.wsteam.wandscape.shared.log.Log;

/**
 * MC implementation of {@link RitualOps} with async channeling.
 *
 * <p>Rituals with channelTicks > 0 (e.g. self_teleport at 600 ticks) return an
 * incomplete future. The NPC channels for the duration, then the effect fires
 * via {@code thenRun}. {@link #tickAll()} is called every MC tick from
 * {@link com.wsteam.wandscape.Wandscape#onServerTick} to decrement countdowns.
 *
 * <p>Sync rituals (channelTicks = 0) execute immediately and return a completed
 * future — backward-compat with existing callers.
 *
 * <p>Channeling durations are hardcoded per ritual type, matching
 * {@link AtomicOp.RitualOp#channelTicks()}.
 */
public class WandscapeRitualOps implements RitualOps {

    private static final String TAG = "WandscapeRitualOps";

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

        Log.info(TAG, "[RitualOps] NPC {} — {} channeling {} ticks at {}",
                casterId, ritual.id(), ticks, target);
        return future;
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

    // ── Hardcoded channel ticks per ritual type ──

    static int channelTicks(RitualId ritual) {
        return switch (ritual.id()) {
            case "self_teleport", "item_teleport", "player_summon" -> 1; // TODO: restore to 600 after testing
            case "warding" -> 200;
            case "group_vigor" -> 400;
            case "rain_call", "clear_weather" -> 1200;
            case "portal_gate" -> 1800;
            default -> 0;
        };
    }

    // ── Ritual execution ──

    private void executeRitual(RitualId ritual, GridPos target, World world, long casterId) {
        if ("self_teleport".equals(ritual.id())) {
            WandscapeNpc npc = EntityComponentBridge.INSTANCE.getNpc(casterId);
            if (npc != null && !npc.isRemoved()) {
                npc.teleportTo(target.x() + 0.5, target.y(), target.z() + 0.5);
                for (int i = 0; i < 20; i++) {
                    double ox = (npc.getRandom().nextDouble() - 0.5) * 1.5;
                    double oy = npc.getRandom().nextDouble() * 2.0;
                    double oz = (npc.getRandom().nextDouble() - 0.5) * 1.5;
                    npc.level().addParticle(ParticleTypes.PORTAL,
                            npc.getX() + ox, npc.getY() + oy, npc.getZ() + oz,
                            0, 0, 0);
                }
                Log.info(TAG, "[RitualOps] self_teleport: NPC {} → {}", casterId, target);
            } else {
                Log.warn(TAG, "[RitualOps] self_teleport: NPC not found for casterId {}", casterId);
            }
            return;
        }

        Log.warn(TAG, "[RitualOps] Unknown ritual '{}' at {} — no-op", ritual.id(), target);
    }
}
