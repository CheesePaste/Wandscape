package com.wsteam.wandscape.npc.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.core.CoreBootstrap;
import com.wsteam.wandscape.core.component.ManaPool;
import com.wsteam.wandscape.core.component.WandCarrier;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.types.GridPos;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;

/**
 * Singleton bridge between MC {@link WandscapeNpc} entities and the ECS World.
 *
 * <p>Maintains bidirectional mapping so that:
 * <ul>
 *   <li>The engine SchedulerSystem finds NPCs via ECS component queries.</li>
 *   <li>MC boundary implementations (EntityOps, RitualOps) find the MC entity
 *       from an ECS entity ID.</li>
 *   <li>NpcApiImpl resolves UUID → ECS entity → NPC data.</li>
 * </ul>
 *
 * <p>Calling convention:
 * <ul>
 *   <li>{@link #onNpcJoinWorld} — called from WandscapeNpc.onAddedToLevel</li>
 *   <li>{@link #onNpcLeaveWorld} — called from WandscapeNpc.onRemovedFromLevel
 *       (KILLED / DISCARDED only; UNLOADED_TO_CHUNK is skipped)</li>
 *   <li>{@link #syncPositions} — called from Wandscape.onServerTick, before the
 *       engine tick gate</li>
 * </ul>
 */
public final class EntityComponentBridge {

    public static final EntityComponentBridge INSTANCE = new EntityComponentBridge();
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Stage 2 placeholder colony — allows engine scheduling without real colonies. */
    public static final UUID PLACEHOLDER_COLONY =
            UUID.fromString("00000000-0000-0000-0000-000000000000");

    // ecsEntityId → MC entity
    private final Map<Long, WandscapeNpc> npcByEcsId = new ConcurrentHashMap<>();
    // MC entity UUID → ecsEntityId
    private final Map<UUID, Long> ecsIdByUuid = new ConcurrentHashMap<>();

    /** NPCs that loaded before the engine was bootstrapped — flush on next tick. */
    private final List<WandscapeNpc> deferredJoins = new ArrayList<>();

    /** ECS component types that make up an NPC. */
    private static final Class<?>[] NPC_COMPONENTS = {
            com.wsteam.wandscape.core.component.Position.class,
            ManaPool.class,
            com.wsteam.wandscape.core.component.TaskExecutor.class,
            WandCarrier.class,
            com.wsteam.wandscape.core.component.Inventory.class,
            com.wsteam.wandscape.core.component.ColonyMember.class,
    };

    private EntityComponentBridge() {}

    // ================================================================
    // Deferred join (for NPCs loaded before engine bootstrap)
    // ================================================================

    /**
     * Queue an NPC for ECS registration once the engine is ready.
     * Called from {@code WandscapeNpc.onAddedToLevel} when
     * {@code WandscapeEngine.getWorld()} returns null.
     */
    public void deferJoin(WandscapeNpc npc) {
        synchronized (deferredJoins) {
            deferredJoins.add(npc);
        }
    }

    /**
     * Flush all deferred NPC registrations.
     * Called from the engine tick once per frame when the world is live.
     */
    public void flushDeferredJoins(World world) {
        List<WandscapeNpc> pending;
        synchronized (deferredJoins) {
            if (deferredJoins.isEmpty()) return;
            pending = new ArrayList<>(deferredJoins);
            deferredJoins.clear();
        }
        for (WandscapeNpc npc : pending) {
            if (npc.isRemoved()) continue;
            LOGGER.info("Deferred NPC {} now joining ECS", npc.getUUID().toString().substring(0, 8));
            onNpcJoinWorld(npc, world);
        }
    }

    // ================================================================
    // Lifecycle
    // ================================================================

    /**
     * Register this NPC in the ECS World. Handles both fresh creation and
     * same-session reconnection after chunk reload.
     *
     * <p>Uses {@code ecsIdByUuid.containsKey()} to distinguish genuine
     * same-session reconnection from cross-session ECS entity ID collisions.
     * After a world reset, all stale mappings are cleared by {@link #clear()}
     * so every NPC takes the fresh-registration path.
     */
    public void onNpcJoinWorld(WandscapeNpc npc, World world) {
        // Same-session reconnection: NPC was already registered in THIS session
        // (chunk unload/reload). Only trust this path if the UUID is still known.
        if (npc.ecsEntityId > 0 && ecsIdByUuid.containsKey(npc.getUUID())
                && world.has(npc.ecsEntityId, com.wsteam.wandscape.core.component.Position.class)) {
            LOGGER.debug("NPC {} reconnecting to ECS entity {}", npc.getUUID(), npc.ecsEntityId);
            world.addComponent(npc.ecsEntityId,
                    new com.wsteam.wandscape.core.component.Position(
                            new GridPos(npc.getBlockX(), npc.getBlockY(), npc.getBlockZ())));
            npcByEcsId.put(npc.ecsEntityId, npc);
            ecsIdByUuid.put(npc.getUUID(), npc.ecsEntityId);
            return;
        }

        // Fresh registration (or cross-session: stale ecsEntityId from NBT)
        UUID colony = npc.colonyId != null ? npc.colonyId : PLACEHOLDER_COLONY;

        // Default capability: all NPCs can build at level 1.
        // This breaks the cold-start deadlock: NPC needs building capability
        // to construct the warehouse, but wands are produced at the warehouse.
        // The default builder_wand capability represents the NPC's innate
        // "bare hands" building ability — enough to bootstrap the colony.
        // Cold-start wand: NPCs start with builder_wand capabilities.
        // Must track the wand ID so it gets returned to warehouse on task completion
        // and re-equipped via the normal wand provision flow for subsequent tasks.
        WandCarrier defaultCarrier = new WandCarrier(
                Map.of(com.wsteam.wandscape.core.types.BehaviourTag.BUILDING,
                        com.wsteam.wandscape.core.types.BehaviourLevel.of(1)),
                1.0f, 1,
                java.util.List.of("builder_wand"));

        long ecsId = CoreBootstrap.createNpc(world,
                npc.getBlockX(), npc.getBlockY(), npc.getBlockZ(),
                defaultCarrier, colony, npc.maxMana, npc.manaRegenRate);

        // Apply current mana from NBT if it was consumed before save
        ManaPool mana = world.get(ecsId, ManaPool.class);
        if (mana != null && npc.currentMana < mana.max()) {
            float toConsume = mana.current() - npc.currentMana;
            if (toConsume > 0) {
                mana.consume(toConsume);
            }
        }

        npc.ecsEntityId = ecsId;
        npcByEcsId.put(ecsId, npc);
        ecsIdByUuid.put(npc.getUUID(), ecsId);

        LOGGER.info("NPC {} joined ECS as entity {} (colony={})",
                npc.getUUID().toString().substring(0, 8), ecsId,
                colony.toString().substring(0, 8));
    }

    /** Clear all NPC→ECS mappings. Called on world reset to prevent cross-session collisions. */
    public void clear() {
        npcByEcsId.clear();
        ecsIdByUuid.clear();
        LOGGER.info("EntityComponentBridge cleared — {} NPCs, {} UUIDs",
                npcByEcsId.size(), ecsIdByUuid.size());
    }

    /**
     * Remove this NPC from the ECS World. Only called on KILLED / DISCARDED.
     * Chunk unloads (UNLOADED_TO_CHUNK) keep ECS components alive.
     */
    public void onNpcLeaveWorld(WandscapeNpc npc, World world) {
        if (npc.ecsEntityId < 0) return;

        for (Class<?> comp : NPC_COMPONENTS) {
            world.removeComponent(npc.ecsEntityId, comp);
        }
        npcByEcsId.remove(npc.ecsEntityId);
        ecsIdByUuid.remove(npc.getUUID());

        LOGGER.info("NPC {} left ECS (entity {})", npc.getUUID().toString().substring(0, 8), npc.ecsEntityId);
    }

    // ================================================================
    // Per-tick sync
    // ================================================================

    /**
     * Sync MC entity positions → ECS Position components.
     * Called every MC tick, before the engine tick gate.
     */
    public void syncPositions(World world) {
        for (var entry : npcByEcsId.entrySet()) {
            WandscapeNpc npc = entry.getValue();
            if (npc != null && !npc.isRemoved()) {
                world.addComponent(entry.getKey(),
                        new com.wsteam.wandscape.core.component.Position(
                                new GridPos(npc.getBlockX(), npc.getBlockY(), npc.getBlockZ())));
            }
        }
    }

    // ================================================================
    // Lookup
    // ================================================================

    @Nullable
    public WandscapeNpc getNpc(long ecsId) {
        return npcByEcsId.get(ecsId);
    }

    @Nullable
    public Long getEcsId(UUID uuid) {
        return ecsIdByUuid.get(uuid);
    }

    /** All mapped NPCs (for iteration, e.g. NpcApiImpl). */
    public Map<Long, WandscapeNpc> allNpcs() {
        return Map.copyOf(npcByEcsId);
    }
}
