package com.wsteam.wandscape.npc.entity;

import java.util.UUID;

import javax.annotation.Nullable;

import com.wsteam.wandscape.core.component.ManaPool;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.npc.internal.EntityComponentBridge;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.level.Level;

/**
 * A colony NPC — the MC-layer shell for an ECS-driven task executor.
 *
 * <p>Architecture: the NPC entity provides appearance, pathfinding, and NBT
 * persistence. All logic (mana, scheduling, task execution) is driven by the
 * core engine via ECS components. An {@link EntityComponentBridge} maintains
 * the bidirectional mapping between this entity and its ECS counterpart.
 *
 * <p>Stage 2 (V1 minimal): basic idle AI, no task-driven movement.
 * Subsequent stages add stuck detection, death/grave, house binding, etc.
 */
public class WandscapeNpc extends PathfinderMob {

    // ============================================================
    // Engine bridge (public for same-module cross-package access)
    // ============================================================

    /** ECS World entity ID — assigned by EntityComponentBridge on join. */
    public long ecsEntityId = -1;

    /** Colony membership. Stage 2: placeholder UUID. Stage 4: real colony. */
    public UUID colonyId = EntityComponentBridge.PLACEHOLDER_COLONY;

    // ============================================================
    // Attributes (ECS is authoritative at runtime; these are NBT transit)
    // ============================================================

    public int currentMana = 100;
    public int maxMana = 100;
    public int manaRegenRate = 2;
    public int spellPower = 1;

    // ============================================================
    // Inventory
    // ============================================================

    public final SimpleContainer inventory = new SimpleContainer(27);

    // ============================================================
    // Construction
    // ============================================================

    public WandscapeNpc(EntityType<? extends PathfinderMob> entityType, Level level) {
        super(entityType, level);
    }

    // ============================================================
    // AI goals
    // ============================================================

    @Override
    protected void registerGoals() {
        // Priority 0: don't drown
        this.goalSelector.addGoal(0, new FloatGoal(this));
        // Priority 5: wander around when idle
        this.goalSelector.addGoal(5, new RandomStrollGoal(this, 0.6));
    }

    // ============================================================
    // Lifecycle — ECS bridge
    // ============================================================

    @Override
    public void tick() {
        super.tick();
        // Mana regen is handled by ManaRegenSystem in the engine.
        // Stuck detection will be added in stage 3+.
    }

    @Override
    public void onAddedToLevel() {
        super.onAddedToLevel();
        if (!level().isClientSide) {
            World world = WandscapeEngine.getWorld();
            if (world != null) {
                EntityComponentBridge.INSTANCE.onNpcJoinWorld(this, world);
            }
        }
    }

    @Override
    public void onRemovedFromLevel() {
        // When the entity is permanently removed (killed, discarded, changed dim),
        // unregister from ECS. Chunk unloads (!shouldSave) keep ECS components alive.
        RemovalReason reason = getRemovalReason();
        if (!level().isClientSide && reason != null && reason.shouldSave()) {
            World world = WandscapeEngine.getWorld();
            if (world != null) {
                EntityComponentBridge.INSTANCE.onNpcLeaveWorld(this, world);
            }
        }
        super.onRemovedFromLevel();
    }

    // ============================================================
    // NBT persistence
    // ============================================================

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        // Read current mana from ECS (the authoritative source at runtime)
        World world = WandscapeEngine.getWorld();
        if (world != null && ecsEntityId > 0) {
            ManaPool mana = world.get(ecsEntityId, ManaPool.class);
            tag.putInt("currentMana", mana != null ? mana.current() : currentMana);
        } else {
            tag.putInt("currentMana", currentMana);
        }
        tag.putLong("EcsEntityId", ecsEntityId);
        tag.putInt("maxMana", maxMana);
        tag.putInt("manaRegenRate", manaRegenRate);
        tag.putInt("spellPower", spellPower);
        if (colonyId != null) {
            tag.putUUID("colonyId", colonyId);
        }
        // Inventory save deferred to stage 3+ (wand contents)
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        ecsEntityId = tag.getLong("EcsEntityId");
        currentMana = tag.getInt("currentMana");
        maxMana = tag.getInt("maxMana");
        manaRegenRate = tag.getInt("manaRegenRate");
        spellPower = tag.getInt("spellPower");
        if (tag.hasUUID("colonyId")) {
            colonyId = tag.getUUID("colonyId");
        }
    }

    // ============================================================
    // Helpers
    // ============================================================

    /** Whether the NPC is idle (no ECS work). Used by NpcApiImpl. */
    public boolean isEngineIdle() {
        if (ecsEntityId < 0) return true;
        World world = WandscapeEngine.getWorld();
        if (world == null) return true;
        var exec = world.get(ecsEntityId, com.wsteam.wandscape.core.component.TaskExecutor.class);
        return exec == null || !exec.hasWork();
    }

    @Nullable
    public UUID getCurrentTaskId() {
        if (ecsEntityId < 0) return null;
        World world = WandscapeEngine.getWorld();
        if (world == null) return null;
        var exec = world.get(ecsEntityId, com.wsteam.wandscape.core.component.TaskExecutor.class);
        return exec != null && exec.globalTaskId != null
                ? new UUID(0, exec.globalTaskId) : null;
    }

    /** In-game display name for the NPC. */
    public String getNpcName() {
        return hasCustomName() ? getCustomName().getString() : "Wizard";
    }
}
