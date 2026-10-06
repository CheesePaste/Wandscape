package com.wsteam.wandscape.content.task.boundary;
import com.wsteam.wandscape.content.task.component.ColonyMember;
import com.wsteam.wandscape.content.task.component.Position;
import com.wsteam.wandscape.content.task.ecs.World;

import com.wsteam.wandscape.content.task.types.EffectId;
import com.wsteam.wandscape.content.task.types.EntityId;
import com.wsteam.wandscape.content.task.types.GridPos;

import javax.annotation.Nullable;
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

    /** Get the effective work speed of an NPC by ECS entity id. */
    float getWorkSpeed(long npcId);

    /**
     * 该工作者能否承担**需要施放殖民地法术**的任务（守卫 {@code guard:attack} / 祭坛施法）。
     *
     * <p>这类任务的 MC 执行器（{@code GuardAttackExecutor} / {@code AltarCastExecutor}）只认本模组
     * 法师，拿不到法师时会把任务立刻判为完成——不具备该能力的工作者（第三方实体）若接取，
     * 会变成"接了不动、威胁没处理、任务源再发布"的空转，故调度侧据此提前挡掉候选。
     */
    boolean canCastColonyMagic(long npcId);

    /**
     * Whether the NPC is in follow mode (following a player). A following NPC
     * must not be assigned colony tasks, and any in-hand global task is
     * released so only personal behavior (e.g. self-defense) continues.
     */
    boolean isFollowing(long npcId);

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
     * Whether the NPC is alive in the world (not dead and not discarded).
     * Returns true even if the NPC's chunk is currently unloaded, as long as
     * the NPC still exists in the colony and can be summoned.
     */
    boolean isNpcAlive(long npcId);

    /**
     * Whether the NPC's Minecraft entity is currently loaded in memory and usable
     * (present in a loaded chunk, not removed).
     */
    boolean isNpcLoaded(long npcId);

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

    /**
     * 建筑委派（见 {@code BuildingDelegation}）的读侧：该工作者被委派到的建筑。
     *
     * <p>调度器据此保证「被委派的法师只做它那座建筑的任务」——委派是非 MC 概念，
     * 而建筑数据在 MC 侧，故由本边界提供这两个查询，调度器保持零 MC 依赖。
     *
     * @return 委派到的建筑 id；未委派返回 null
     */
    @Nullable
    java.util.UUID delegatedBuildingOf(long npcId);

    /**
     * 建筑委派的读侧：该建筑被委派给的工作者（ECS id）。
     *
     * @return 被委派工作者的 ECS id；未委派或该工作者已不在世返回 -1
     */
    long delegatedNpcOf(java.util.UUID buildingId);
}
