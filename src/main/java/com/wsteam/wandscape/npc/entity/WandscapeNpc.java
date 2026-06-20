package com.wsteam.wandscape.npc.entity;

import java.util.UUID;

import javax.annotation.Nullable;

import com.wsteam.wandscape.core.component.ManaPool;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.npc.internal.EntityComponentBridge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
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
    // Attributes (must be registered via EntityAttributeCreationEvent)
    // ============================================================

    /**
     * Creates the attribute supplier for this NPC.
     * Register via {@code EntityAttributeCreationEvent} in common setup.
     */
    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 40.0)
                .add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.ATTACK_DAMAGE, 1.0);
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
        RemovalReason reason = getRemovalReason();
        if (!level().isClientSide && reason != null && reason.shouldSave()) {
            World world = WandscapeEngine.getWorld();
            if (world != null) {
                // NPC dying → release global task for reassignment (preserve stepIndex).
                // Private queue is discarded along with ECS components.
                if (reason == RemovalReason.KILLED && ecsEntityId > 0) {
                    var exec = world.get(ecsEntityId,
                            com.wsteam.wandscape.core.component.TaskExecutor.class);
                    if (exec != null && exec.globalTaskId != null) {
                        world.taskPool.releaseTaskForReassign(
                                exec.globalTaskId, ecsEntityId, world);
                    }
                }
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

    // ============================================================
    // Work animation (called from engine boundary on op completion)
    // ============================================================

    /**
     * Visual feedback for work completion: arm swing + particles at target.
     * Called from AsyncTransformExecutor when a block op finishes.
     */
    public void doWorkAnimation(BlockPos target) {
        this.swing(InteractionHand.MAIN_HAND);
        if (level().isClientSide) return;
        // Spawn particles at the target block position (server syncs to clients)
        for (int i = 0; i < 5; i++) {
            level().addParticle(
                    ParticleTypes.WITCH,
                    target.getX() + 0.5 + (random.nextDouble() - 0.5) * 0.5,
                    target.getY() + 0.5 + (random.nextDouble() - 0.5) * 0.5,
                    target.getZ() + 0.5 + (random.nextDouble() - 0.5) * 0.5,
                    0, 0, 0);
        }
    }
}
