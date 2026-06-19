package com.wsteam.wandscape.core.boundary;

import com.wsteam.wandscape.core.types.EffectId;
import com.wsteam.wandscape.core.types.EntityId;
import com.wsteam.wandscape.core.types.GridPos;

/**
 * Core-layer boundary for entity-level operations.
 * Implemented by the Minecraft adapter layer.
 */
public interface EntityOps {

    /** Apply an effect to a non-NPC entity (managed by the adapter layer). */
    void applyEffect(EntityId target, EffectId effect, int strength, int duration);

    /** Get the position of an external entity. */
    GridPos getPosition(EntityId entity);
}
