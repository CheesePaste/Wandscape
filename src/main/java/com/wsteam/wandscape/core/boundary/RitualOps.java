package com.wsteam.wandscape.core.boundary;

import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.types.GridPos;
import com.wsteam.wandscape.core.types.RitualId;

import java.util.concurrent.CompletableFuture;

/**
 * Core-layer boundary for ritual execution.
 *
 * <p>V2.5 async model: {@link #beginRitual} returns a {@link CompletableFuture}.
 * Sync rituals (e.g. self_teleport) return {@link CompletableFuture#completedFuture completedFuture(null)}.
 * Channeled rituals return an incomplete future that the MC adapter completes when the ritual finishes.
 */
public interface RitualOps {

    /**
     * Begin a ritual. Returns a future that completes when the ritual is done.
     * Sync: {@code CompletableFuture.completedFuture(null)}
     * Async: incomplete future → MC adapter completes it when channeling finishes
     */
    CompletableFuture<Void> beginRitual(RitualId ritual, GridPos target, World world, long casterId);
}
