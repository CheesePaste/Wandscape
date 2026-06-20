package com.wsteam.wandscape.npc.entity;

import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.core.component.ManaPool;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.npc.internal.EntityComponentBridge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

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
    // Casting state (synced to client for animation + particles)
    // ============================================================

    private static final EntityDataAccessor<Boolean> DATA_CASTING =
            SynchedEntityData.defineId(WandscapeNpc.class, EntityDataSerializers.BOOLEAN);

    public boolean isCasting() {
        return this.entityData.get(DATA_CASTING);
    }

    public void setCasting(boolean casting) {
        this.entityData.set(DATA_CASTING, casting);
    }

    /** Debug flag — when true, skips ECS polling and forces casting state. */
    private boolean debugCasting = false;

    /**
     * When true, {@link RandomStrollGoal} is suppressed so
     * {@code NavigationSystem} can control navigation without AI interference.
     */
    private boolean suppressWandering = false;

    /** Enable or disable idle wandering AI. Called by NavigationSystem. */
    public void setAiWanderingEnabled(boolean enabled) {
        this.suppressWandering = !enabled;
        if (!enabled) {
            getNavigation().stop();
        }
    }

    /** Debug ray target (synced to client). */
    private static final EntityDataAccessor<Optional<BlockPos>> DATA_DEBUG_TARGET =
            SynchedEntityData.defineId(WandscapeNpc.class, EntityDataSerializers.OPTIONAL_BLOCK_POS);

    public Optional<BlockPos> getDebugTarget() {
        return this.entityData.get(DATA_DEBUG_TARGET);
    }

    public void setDebugTarget(BlockPos pos) {
        this.entityData.set(DATA_DEBUG_TARGET, Optional.ofNullable(pos));
    }

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
                .add(Attributes.ATTACK_DAMAGE, 1.0)
                .add(Attributes.FOLLOW_RANGE, 32.0);
    }

    // ============================================================
    // AI goals
    // ============================================================

    @Override
    protected void registerGoals() {
        // Priority 0: don't drown
        this.goalSelector.addGoal(0, new FloatGoal(this));
        // Priority 5: wander around when idle (suppressed when MovementOps controls navigation)
        this.goalSelector.addGoal(5, new RandomStrollGoal(this, 0.6) {
            @Override
            public boolean canUse() {
                return !suppressWandering && super.canUse();
            }

            @Override
            public boolean canContinueToUse() {
                return !suppressWandering && super.canContinueToUse();
            }

            @Override
            public void stop() {
                if (!suppressWandering) {
                    super.stop(); // only clear navigation if stopping organically
                }
                // When suppressWandering is set, MovementOps owns the navigation —
                // don't let the goal selector's cleanup kill our path.
            }
        });
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_CASTING, false);
        builder.define(DATA_DEBUG_TARGET, Optional.empty());
    }

    // ============================================================
    // Lifecycle — ECS bridge
    // ============================================================

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide) {
            boolean casting;
            if (debugCasting) {
                casting = true;
                BlockPos target = Wandscape.debugDiamondTarget;
                if (target != null) {
                    setDebugTarget(target);
                    faceTarget(target);
                }
            } else {
                World ecsWorld = WandscapeEngine.getWorld();
                if (ecsWorld != null && ecsEntityId > 0) {
                    var exec = ecsWorld.get(ecsEntityId,
                            com.wsteam.wandscape.core.component.TaskExecutor.class);
                    casting = exec != null
                            && exec.state == com.wsteam.wandscape.core.task.ExecutorState.ACTIVE
                            && exec.hasWork();
                    if (casting && exec.currentOpTarget != null) {
                        var t = exec.currentOpTarget;
                        BlockPos target = new BlockPos(t.x(), t.y(), t.z());
                        setDebugTarget(target);
                        faceTarget(target);
                    } else {
                        setDebugTarget(null);
                    }
                } else {
                    casting = false;
                }
            }
            if (casting != isCasting()) {
                setCasting(casting);
            }
            if (isCasting() && !suppressWandering) {
                getNavigation().stop();
                setDeltaMovement(Vec3.ZERO);
            }
        }
    }

    /** Face the NPC toward a target block (yaw from horizontal, pitch from vertical angle). */
    private void faceTarget(BlockPos target) {
        double dx = target.getX() + 0.5 - getX();
        double dz = target.getZ() + 0.5 - getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        setYRot(yaw);
        yBodyRot = yaw;
        yHeadRot = yaw;
        double dy = target.getY() + 0.5 - (getY() + 1.4);
        double hDist = Math.sqrt(dx * dx + dz * dz);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, hDist));
        setXRot(pitch);
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (level().isClientSide) {
            return InteractionResult.SUCCESS;
        }
        debugCasting = !debugCasting;
        if (debugCasting) {
            setItemInHand(InteractionHand.MAIN_HAND,
                    new ItemStack(Wandscape.WAND.get()));
            BlockPos target = Wandscape.debugDiamondTarget;
            setDebugTarget(target);
            if (target != null) {
                player.sendSystemMessage(Component.literal(
                        "[Wandscape Debug] NPC casting ON — targeting " + target));
            } else {
                player.sendSystemMessage(Component.literal(
                        "[Wandscape Debug] NPC casting ON — no diamond target set"));
            }
        } else {
            setDebugTarget(null);
            player.sendSystemMessage(Component.literal(
                    "[Wandscape Debug] NPC casting OFF"));
        }
        return InteractionResult.sidedSuccess(level().isClientSide);
    }

    @Override
    public void onAddedToLevel() {
        super.onAddedToLevel();
        if (!level().isClientSide) {
            // Prevent vanilla despawn — NPC persistence is managed by the colony/engine
            this.setPersistenceRequired();
            World world = WandscapeEngine.getWorld();
            if (world != null) {
                EntityComponentBridge.INSTANCE.onNpcJoinWorld(this, world);
            }
        }
    }

    @Override
    public void onRemovedFromLevel() {
        RemovalReason reason = getRemovalReason();
        if (!level().isClientSide && reason != null) {
            World world = WandscapeEngine.getWorld();

            // CHANGED_DIMENSION: entity is transitioning to another dimension,
            // not leaving the world. Skip all cleanup — ECS components stay.
            if (reason == RemovalReason.CHANGED_DIMENSION) {
                super.onRemovedFromLevel();
                return;
            }

            // KILLED / DISCARDED: entity is destroyed (died, /kill, despawn).
            // Release global task for reassignment (preserve stepIndex),
            // then destroy ECS components. Private queue is discarded.
            if (reason == RemovalReason.KILLED || reason == RemovalReason.DISCARDED) {
                if (world != null && ecsEntityId > 0) {
                    var exec = world.get(ecsEntityId,
                            com.wsteam.wandscape.core.component.TaskExecutor.class);
                    if (exec != null && exec.globalTaskId != null) {
                        world.taskPool.releaseTaskForReassign(
                                exec.globalTaskId, ecsEntityId, world);
                    }
                    EntityComponentBridge.INSTANCE.onNpcLeaveWorld(this, world);
                }
            }
            // UNLOADED_TO_CHUNK / UNLOADED_WITH_PLAYER:
            // Entity still exists, just unloaded. Keep ECS components alive
            // for reconnection when the chunk/player returns.
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
