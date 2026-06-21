package com.wsteam.wandscape.engine.boundary;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.core.boundary.ColonyResourceAccess;
import com.wsteam.wandscape.core.boundary.RitualOps;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.types.GridPos;
import com.wsteam.wandscape.core.types.ResourceId;
import com.wsteam.wandscape.core.types.RitualId;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.npc.internal.EntityComponentBridge;

import net.minecraft.core.particles.ParticleTypes;

/**
 * MC implementation of {@link RitualOps}.
 *
 * <p>Sync rituals (e.g. self_teleport) return {@link CompletableFuture#completedFuture completedFuture(null)}.
 *
 * <p>Channeled rituals (e.g. node_gathering) return an incomplete future.
 * A {@link #tickAll()} countdown decrements each tick; when it reaches zero
 * the future completes and triggers the side effect (e.g. warehouse injection).
 * The engine stores the incomplete future in TaskExecutor.pendingFuture and
 * does NOT re-invoke execute() until it resolves.
 */
public class WandscapeRitualOps implements RitualOps {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** A channeled ritual waiting to complete. */
    record PendingRitual(
            CompletableFuture<Void> future,
            String element,
            int amount,
            int remainingTicks,
            long npcId,
            World world
    ) {}

    private final List<PendingRitual> pending = new ArrayList<>();

    @Override
    public CompletableFuture<Void> beginRitual(RitualId ritual, GridPos target, World world,
                                               long casterId, int channelTicks,
                                               Map<String, String> params) {
        if ("self_teleport".equals(ritual.id())) {
            WandscapeNpc npc = EntityComponentBridge.INSTANCE.getNpc(casterId);
            if (npc != null && !npc.isRemoved()) {
                npc.teleportTo(target.x() + 0.5, target.y() + 1, target.z() + 0.5);
                for (int i = 0; i < 20; i++) {
                    double ox = (npc.getRandom().nextDouble() - 0.5) * 1.5;
                    double oy = npc.getRandom().nextDouble() * 2.0;
                    double oz = (npc.getRandom().nextDouble() - 0.5) * 1.5;
                    npc.level().addParticle(ParticleTypes.PORTAL,
                            npc.getX() + ox, npc.getY() + oy, npc.getZ() + oz,
                            0, 0, 0);
                }
                LOGGER.debug("self_teleport: NPC {} → {}", casterId, target);
            } else {
                LOGGER.warn("self_teleport: NPC not found for casterId {}", casterId);
            }
            return CompletableFuture.completedFuture(null);
        }

        if ("node_gathering".equals(ritual.id())) {
            final String element = params != null ? params.getOrDefault("element", "wood") : "wood";
            final int amount = parseAmount(params);

            // Get colonyId from the NPC entity
            WandscapeNpc npc = EntityComponentBridge.INSTANCE.getNpc(casterId);
            final UUID colonyId = npc != null ? npc.colonyId : null;

            CompletableFuture<Void> future = world.startAsyncOp("node_gather_" + element + "_" + target);
            pending.add(new PendingRitual(future, element, amount, channelTicks, casterId, world));

            future.thenRun(() -> {
                ColonyResourceAccess resources = world.colonyResources;
                if (resources == null) {
                    LOGGER.warn("node_gathering: colonyResources is null, cannot inject {}", element);
                    return;
                }
                resources.addResource(new ResourceId(element), amount);
                // Visual feedback on the NPC
                WandscapeNpc npc2 = EntityComponentBridge.INSTANCE.getNpc(casterId);
                if (npc2 != null && !npc2.isRemoved()) {
                    for (int i = 0; i < 15; i++) {
                        double ox = (npc2.getRandom().nextDouble() - 0.5) * 1.0;
                        double oy = npc2.getRandom().nextDouble() * 2.0;
                        double oz = (npc2.getRandom().nextDouble() - 0.5) * 1.0;
                        npc2.level().addParticle(ParticleTypes.HAPPY_VILLAGER,
                                npc2.getX() + ox, npc2.getY() + oy, npc2.getZ() + oz,
                                0, 0, 0);
                    }
                }
                LOGGER.info("node_gathering complete: {} x{} → colony {} warehouse",
                        element, amount, colonyId);
            });

            LOGGER.debug("node_gathering: NPC {} channeling {} x{} at {} ({} ticks)",
                    casterId, element, amount, target, channelTicks);
            return future;
        }

        // Unknown ritual — log and complete immediately
        LOGGER.warn("Unknown ritual '{}' at {} — completing immediately", ritual.id(), target);
        return CompletableFuture.completedFuture(null);
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
                pending.set(i, new PendingRitual(
                        p.future(), p.element(), p.amount(),
                        remaining, p.npcId(), p.world()));
            }
        }

        for (CompletableFuture<Void> f : toComplete) {
            f.complete(null); // → triggers thenRun → addResource
        }

        // Remove completed from pending (futures that were completed this tick)
        pending.removeIf(p -> p.future().isDone());

        if (!toComplete.isEmpty()) {
            LOGGER.debug("ritual tickAll: {} completed, {} remaining",
                    toComplete.size(), pending.size());
        }
    }

    /** Whether any channeled rituals are still in progress. */
    public boolean hasPendingOps() {
        return !pending.isEmpty();
    }

    /** Parse amount from params, defaulting to 10 on null or parse error. */
    private static int parseAmount(Map<String, String> params) {
        if (params == null) return 10;
        try {
            String raw = params.get("amount");
            return raw != null ? Integer.parseInt(raw) : 10;
        } catch (NumberFormatException e) {
            return 10;
        }
    }
}
