package com.wsteam.wandscape.engine.boundary;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.core.boundary.RitualOps;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.op.OpResult;
import com.wsteam.wandscape.core.types.GridPos;
import com.wsteam.wandscape.core.types.RitualId;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.npc.internal.EntityComponentBridge;

/**
 * MC implementation of {@link RitualOps}.
 *
 * <p>Stage 2: synchronous self_teleport (instant teleport). All other rituals
 * return DONE immediately (stub). Stage 3+ will add item_transport, channeled
 * rituals with CompletableFuture support, and ritual JSON loading.
 */
public class WandscapeRitualOps implements RitualOps {

    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public void beginRitual(RitualId ritual, GridPos target, World world, long casterId) {
        if ("self_teleport".equals(ritual.id())) {
            WandscapeNpc npc = EntityComponentBridge.INSTANCE.getNpc(casterId);
            if (npc != null && !npc.isRemoved()) {
                npc.teleportTo(target.x() + 0.5, target.y(), target.z() + 0.5);
                LOGGER.debug("self_teleport: NPC {} → {}", casterId, target);
            } else {
                LOGGER.warn("self_teleport failed: NPC not found for casterId {}", casterId);
            }
        }
        // Stage 3+: item_transport, channeled rituals
    }

    @Override
    public OpResult pollRitual(RitualId ritual, GridPos target, World world, long casterId) {
        // Stage 2: all rituals are instant → always DONE
        // Stage 3+ channeled rituals: return WAITING while in progress
        return OpResult.DONE;
    }
}
