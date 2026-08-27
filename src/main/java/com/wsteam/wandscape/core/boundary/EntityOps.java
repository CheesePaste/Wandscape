package com.wsteam.wandscape.core.boundary;

import java.util.UUID;

import javax.annotation.Nullable;

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

    /** Get the current mana of an NPC by ECS entity id (scheduler mana gate). */
    float getCurrentMana(long npcId);

    /**
     * Whether the NPC is in follow mode (following a player). A following NPC
     * must not be assigned colony tasks, and any in-hand global task is
     * released so only personal behavior (e.g. self-defense) continues.
     */
    boolean isFollowing(long npcId);

    /**
     * Whether the NPC is resting (in a mage hut). A resting NPC must not be
     * assigned colony tasks, and any in-hand global task is released (mirrors
     * {@link #isFollowing}).
     */
    boolean isResting(long npcId);

    /**
     * Whether a colony's autonomous simulation should currently run.
     * Implemented by the MC adapter: when the colony's founding player is
     * offline and offline-running is disabled, returns false so NPC task
     * scheduling/execution for that colony freezes in place.
     */
    boolean isColonyActive(java.util.UUID colonyId);

    /**
     * Whether the colony is a <em>real, registered</em> colony — present in the
     * colony registry (opposite of the placeholder colony and of stale ids left
     * behind by a deleted colony). NPCs whose {@code ColonyMember} points at an
     * unregistered colony must not be assigned colony work: there is no warehouse
     * or building to serve, and the all-zero placeholder would otherwise look
     * "active" (no founder → treated online) and pull tasks into an endless
     * fail→release→reassign loop.
     */
    boolean isColonyRegistered(java.util.UUID colonyId);

    /**
     * Whether the NPC's MC entity is present and usable — not unloaded
     * (chunk unload) and not destroyed (death/discard). A phantom NPC
     * (components in the ECS world but no live MC entity) must not be
     * assigned new work or driven by the task executor.
     */
    boolean isNpcAlive(long npcId);

    /**
     * Spawn a decoration entity (item frame, painting) from trimmed NBT during
     * building construction.
     *
     * @param pos         the block cell the entity occupies
     * @param entityType  entity registry id (e.g. "minecraft:item_frame")
     * @param facing      Direction name (e.g. "north"), empty string keeps the NBT's embedded facing
     * @param nbtBase64   base64-encoded compressed entity NBT (position-rebased, relative to anchor)
     */
    void spawnDecoration(GridPos pos, String entityType, String facing, @Nullable String nbtBase64);
}
