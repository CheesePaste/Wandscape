package com.wsteam.wandscape.npc.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.wsteam.wandscape.core.component.*;
import com.wsteam.wandscape.core.types.ResourceStack;
import com.wsteam.wandscape.core.types.NpcAttributes;
import com.wsteam.wandscape.core.CoreBootstrap;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.types.GridPos;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.shared.log.Log;

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
    private static final String TAG = "EntityComponentBridge";

    /** Stage 2 placeholder colony — allows engine scheduling without real colonies. */
    public static final UUID PLACEHOLDER_COLONY =
            UUID.fromString("00000000-0000-0000-0000-000000000000");

    // ecsEntityId → MC entity
    private final Map<Long, WandscapeNpc> npcByEcsId = new ConcurrentHashMap<>();
    // MC entity UUID → ecsEntityId
    private final Map<UUID, Long> ecsIdByUuid = new ConcurrentHashMap<>();

    /** NPCs that loaded before the engine was bootstrapped — flush on next tick. */
    private final List<WandscapeNpc> deferredJoins = new ArrayList<>();

    /** Inventory items to fill after ECS join (keyed by NPC UUID). */
    private final Map<UUID, java.util.List<ResourceStack>> deferredInventory = new ConcurrentHashMap<>();

    /** ECS component types that make up an NPC. */
    private static final Class<?>[] NPC_COMPONENTS = {
            Position.class,
            TaskExecutor.class,
            EquipmentComponent.class,
            Inventory.class,
            ColonyMember.class,
    };

    private EntityComponentBridge() {}

    /**
     * Schedule inventory items to be filled into the NPC's ECS Inventory
     * after it joins the ECS world.
     *
     * <p>Used by {@code ColonyCommand} to give the builder NPC its starter
     * materials at colony creation time.
     */
    public void scheduleInventoryFill(UUID npcUuid, UUID colonyId,
                                      java.util.List<ResourceStack> items) {
        deferredInventory.put(npcUuid, items);
    }

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
            Log.info(TAG, "Deferred NPC {} now joining ECS", npc.getUUID().toString().substring(0, 8));
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
                && world.has(npc.ecsEntityId, Position.class)) {
            world.addComponent(npc.ecsEntityId,
                    new Position(
                            new GridPos(npc.getBlockX(), npc.getBlockY(), npc.getBlockZ())));
            npcByEcsId.put(npc.ecsEntityId, npc);
            ecsIdByUuid.put(npc.getUUID(), npc.ecsEntityId);
            fillDeferredInventory(npc, world);
            npc.syncArmorAttributes();
            npc.syncWandAttributes();
            return;
        }

        // Fresh registration (or cross-session: stale ecsEntityId from NBT)
        UUID colony = npc.colonyId != null ? npc.colonyId : PLACEHOLDER_COLONY;

        // Auto-detect colony for spawn-egg NPCs that still have the default
        if (PLACEHOLDER_COLONY.equals(colony)) {
            var colonyApi = com.wsteam.wandscape.shared.registry.WandscapeApis.getColonyApiSilently();
            if (colonyApi != null) {
                UUID detected = colonyApi.getColonyId(npc.blockPosition());
                if (detected != null) {
                    colony = detected;
                    npc.colonyId = detected;
                    Log.info(TAG, "NPC {} auto-assigned to colony {} (spawn-egg detection)",
                            npc.getUUID().toString().substring(0, 8),
                            detected.toString().substring(0, 8));
                }
            }
        }

        NpcAttributes attrs = new NpcAttributes(
                npc.maxHp, npc.moveSpeed, npc.spellPower, npc.workSpeed,
                npc.spellSpeed, npc.armorValue, npc.maxMana);
        long ecsId = CoreBootstrap.createNpc(world,
                npc.getBlockX(), npc.getBlockY(), npc.getBlockZ(),
                colony, attrs);

        npc.ecsEntityId = ecsId;
        npcByEcsId.put(ecsId, npc);
        ecsIdByUuid.put(npc.getUUID(), ecsId);

        Log.info(TAG, "NPC {} joined ECS as entity {} (colony={})",
                npc.getUUID().toString().substring(0, 8), ecsId,
                colony.toString().substring(0, 8));

        // Fill deferred inventory items (e.g. from colony creation command)
        fillDeferredInventory(npc, world);
        // Seed armor attribute modifiers (equipment component was just created)
        npc.syncArmorAttributes();
        npc.syncWandAttributes();
    }

    /** Fill inventory items that were scheduled before ECS registration. */
    private void fillDeferredInventory(WandscapeNpc npc, World world) {
        java.util.List<ResourceStack> items =
                deferredInventory.remove(npc.getUUID());
        if (items == null || items.isEmpty()) return;

        Long ecsId = ecsIdByUuid.get(npc.getUUID());
        if (ecsId == null) return;

        Inventory inv = world.get(ecsId, Inventory.class);
        if (inv == null) {
            Log.warn(TAG, "[Bridge] Cannot fill inventory — NPC {} has no Inventory component",
                    npc.getUUID().toString().substring(0, 8));
            return;
        }

        int added = 0;
        for (ResourceStack stack : items) {
            if (inv.add(stack)) added++;
        }
        Log.info(TAG, "[Bridge] Filled NPC {} inventory with {} stacks (colony={})",
                npc.getUUID().toString().substring(0, 8), added,
                npc.colonyId != null ? npc.colonyId.toString().substring(0, 8) : "?");
    }

    /** Clear all NPC→ECS mappings. Called on world reset to prevent cross-session collisions. */
    public void clear() {
        npcByEcsId.clear();
        ecsIdByUuid.clear();
        deferredInventory.clear();
        Log.info(TAG, "EntityComponentBridge cleared — {} NPCs, {} UUIDs",
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

        Log.info(TAG, "NPC {} left ECS (entity {})", npc.getUUID().toString().substring(0, 8), npc.ecsEntityId);
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
                        new Position(
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
