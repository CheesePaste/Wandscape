package com.wsteam.wandscape.engine.boundary;

import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.core.boundary.RitualOps;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.types.GridPos;
import com.wsteam.wandscape.core.types.RitualId;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.npc.internal.EntityComponentBridge;

/**
 * MC implementation of {@link RitualOps}.
 *
 * <p>Stage 2: synchronous self_teleport (instant teleport → completed future).
 * Stage 3+ will add channeled rituals with CompletableFuture support.
 */
public class WandscapeRitualOps implements RitualOps {

    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public CompletableFuture<Void> beginRitual(RitualId ritual, GridPos target, World world, long casterId) {
        if ("self_teleport".equals(ritual.id())) {
            WandscapeNpc npc = EntityComponentBridge.INSTANCE.getNpc(casterId);
            if (npc != null && !npc.isRemoved()) {
                npc.teleportTo(target.x() + 0.5, target.y(), target.z() + 0.5);
                LOGGER.debug("self_teleport: NPC {} → {}", casterId, target);
            } else {
                LOGGER.warn("self_teleport: NPC not found for casterId {}", casterId);
            }
        }
        // Stage 2: all rituals are sync → return completed future
        return CompletableFuture.completedFuture(null);
    }
}
