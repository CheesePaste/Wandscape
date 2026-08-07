package com.wsteam.wandscape.engine.boundary;

import com.wsteam.wandscape.core.boundary.EntityOps;
import com.wsteam.wandscape.core.types.EffectId;
import com.wsteam.wandscape.core.types.EntityId;
import com.wsteam.wandscape.core.types.GridPos;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.npc.internal.EntityComponentBridge;
/**
 * MC implementation of {@link EntityOps}.
 *
 * <p>Stage 2: stub. EntityInteractOp is not used yet. When NPCs need to
 * interact with non-NPC entities (stage 3+), this will look up the MC entity
 * via {@code EntityComponentBridge.INSTANCE.getNpc()} for NPC targets, and
 * via {@code MinecraftServer.getLevel().getEntity()} for other entities.
 */
public class WandscapeEntityOps implements EntityOps {

    @Override
    public void applyEffect(EntityId target, EffectId effect, int strength, int duration) {
        // Stage 3+: look up target entity and apply MobEffect
    }

    @Override
    public GridPos getPosition(EntityId entity) {
        // Stage 3+: look up entity and return real position
        return GridPos.ORIGIN;
    }

    @Override
    public float getCurrentMana(long npcId) {
        WandscapeNpc npc = EntityComponentBridge.INSTANCE.getNpc(npcId);
        return npc != null ? npc.getCurrentMana() : 0f;
    }
}
