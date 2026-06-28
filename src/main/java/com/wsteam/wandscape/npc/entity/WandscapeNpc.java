package com.wsteam.wandscape.npc.entity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.core.component.ManaPool;
import com.wsteam.wandscape.core.component.NavigationState;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.task.ExecutorState;
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
import net.neoforged.fml.ModList;
import com.wsteam.wandscape.shared.log.Log;

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

    private static final String TAG = "WandscapeNpc";

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

    public static final int SKIN_VARIANT_COUNT = detectSkinVariants();

    private static int detectSkinVariants() {
        try {
            Path dir = ModList.get().getModFileById(Wandscape.MODID).getFile()
                    .findResource("assets", "wandscape", "textures", "entity", "wizard");
            try (Stream<Path> files = Files.list(dir)) {
                int count = (int) files
                        .filter(p -> p.toString().endsWith(".png"))
                        .count();
                if (count > 0) return count;
            }
        } catch (IOException | RuntimeException ignored) {}
        return 1;
    }

    private static final EntityDataAccessor<Integer> DATA_SKIN_VARIANT =
            SynchedEntityData.defineId(WandscapeNpc.class, EntityDataSerializers.INT);

    private static final EntityDataAccessor<Integer> DATA_HAT_COLOR =
            SynchedEntityData.defineId(WandscapeNpc.class, EntityDataSerializers.INT);

    private static final EntityDataAccessor<Boolean> DATA_CASTING =
            SynchedEntityData.defineId(WandscapeNpc.class, EntityDataSerializers.BOOLEAN);

    private static final EntityDataAccessor<String> DATA_OP_KIND =
            SynchedEntityData.defineId(WandscapeNpc.class, EntityDataSerializers.STRING);

    /** Status text shown above the NPC's head (synced to client). */
    private static final EntityDataAccessor<String> DATA_STATUS_TEXT =
            SynchedEntityData.defineId(WandscapeNpc.class, EntityDataSerializers.STRING);

    public int getSkinVariant() {
        return this.entityData.get(DATA_SKIN_VARIANT);
    }

    public int getHatColor() {
        return this.entityData.get(DATA_HAT_COLOR);
    }

    public boolean isCasting() {
        return this.entityData.get(DATA_CASTING);
    }

    public void setCasting(boolean casting) {
        this.entityData.set(DATA_CASTING, casting);
    }

    /** Visual effect kind for the current op. Synced to client for renderer dispatch. */
    public String getOpKind() {
        return this.entityData.get(DATA_OP_KIND);
    }

    public void setOpKind(@Nullable String kind) {
        this.entityData.set(DATA_OP_KIND, kind != null ? kind : "");
    }

    /** Status text shown above head. Synced to client. */
    public String getStatusText() {
        return this.entityData.get(DATA_STATUS_TEXT);
    }

    public void setStatusText(String text) {
        this.entityData.set(DATA_STATUS_TEXT, text != null ? text : "");
    }

    /** Debug flag — when true, skips ECS polling and forces casting state. */
    private boolean debugCasting = false;

    /**
     * When true, {@link RandomStrollGoal} is suppressed so
     * {@code NavigationSystem} can control navigation without AI interference.
     */
    private boolean suppressWandering = false;

    // ── Dirty guards: only sync entity data when values actually change ──
    private String lastSyncedOpKind = "";
    private BlockPos lastSyncedTarget = null;

    // ── Fast path: skip ECS polling for idle NPCs ──
    private int ecsPollCooldown = 0;

    // ── Client-side: last tick particles were spawned (throttle to 1×/tick) ──
    public int lastParticleTick = -1;

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
        builder.define(DATA_SKIN_VARIANT, -1);
        builder.define(DATA_HAT_COLOR, 0);
        builder.define(DATA_CASTING, false);
        builder.define(DATA_DEBUG_TARGET, Optional.empty());
        builder.define(DATA_OP_KIND, "");
        builder.define(DATA_STATUS_TEXT, "");
    }

    // ============================================================
    // Lifecycle — ECS bridge
    // ============================================================

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) return;

        boolean casting;
        if (debugCasting) {
            casting = true;
            BlockPos target = Wandscape.debugDiamondTarget;
            if (target != null) {
                faceTarget(target);
                if (!target.equals(lastSyncedTarget)) {
                    setDebugTarget(target);
                    lastSyncedTarget = target;
                }
            }
        } else if (ecsPollCooldown > 0 && !isCasting()) {
            // Fast path: idle NPC, skip ECS query this tick
            ecsPollCooldown--;
            return;
        } else {
            World ecsWorld = WandscapeEngine.getWorld();
            if (ecsWorld != null && ecsEntityId > 0) {
                var exec = ecsWorld.get(ecsEntityId,
                        com.wsteam.wandscape.core.component.TaskExecutor.class);
                casting = exec != null
                        && exec.state == com.wsteam.wandscape.core.task.ExecutorState.ACTIVE
                        && (exec.npcQueue.hasWork() || exec.globalTaskId != null);
                if (casting && exec.currentOpTarget != null) {
                    var t = exec.currentOpTarget;
                    BlockPos target = new BlockPos(t.x(), t.y(), t.z());
                    if (!target.equals(lastSyncedTarget)) {
                        setDebugTarget(target);
                        lastSyncedTarget = target;
                    }
                    String kind = exec.currentOpKind != null ? exec.currentOpKind : "";
                    if (!kind.equals(lastSyncedOpKind)) {
                        setOpKind(exec.currentOpKind);
                        lastSyncedOpKind = kind;
                    }
                    faceTarget(target);
                } else {
                    if (lastSyncedTarget != null) {
                        setDebugTarget(null);
                        lastSyncedTarget = null;
                    }
                    if (!lastSyncedOpKind.isEmpty()) {
                        setOpKind(null);
                        lastSyncedOpKind = "";
                    }
                }
                // Compute status text from ECS state
                String status = computeStatusText(ecsWorld);
                if (!status.equals(getStatusText())) {
                    setStatusText(status);
                }
            } else {
                casting = false;
                if (!getStatusText().isEmpty()) {
                    setStatusText("");
                }
            }
            // Poll every tick while casting, every 20 ticks while idle
            ecsPollCooldown = casting ? 0 : 20;
        }

        if (casting != isCasting()) {
            setCasting(casting);
        }
        if (isCasting() && !suppressWandering) {
            getNavigation().stop();
            setDeltaMovement(Vec3.ZERO);
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

    // ============================================================
    // Status text (shown above NPC head)
    // ============================================================

    /**
     * Compute a short status string from ECS state for overhead display.
     */
    private String computeStatusText(World ecsWorld) {
        if (ecsWorld == null || ecsEntityId < 0) return "";

        var exec = ecsWorld.get(ecsEntityId, com.wsteam.wandscape.core.component.TaskExecutor.class);
        var nav = ecsWorld.get(ecsEntityId, NavigationState.class);

        // 1. Navigation states (visible even if idle task-wise)
        if (nav != null) {
            switch (nav.mode) {
                case TELEPORT_WAITING -> { return "等待魔力"; }
                case TELEPORT_RITUAL   -> { return "等待传送"; }
                case PATHFINDING       -> { return "移动中"; }
            }
        }

        // 2. No task executor or no work → idle
        if (exec == null || !(exec.npcQueue.hasWork() || exec.globalTaskId != null) || exec.state == ExecutorState.IDLE) return "空闲";

        // 3. Pending async future (navigation or channeled op)
        if (exec.pendingFuture != null && !exec.pendingFuture.isDone()) {
            if (exec.pendingFutureIsNav) return "移动中";
            // Channeled op in progress
            String kind = exec.currentOpKind;
            if (kind != null) {
                if (kind.startsWith("block_interact:")) {
                    String action = kind.substring("block_interact:".length());
                    return actionDisplayName(action);
                }
                if (kind.startsWith("ritual:")) {
                    String ritual = kind.substring("ritual:".length());
                    return ritualDisplayName(ritual);
                }
            }
            return "引导中";
        }

        // 4. Actively executing
        if (exec.state == ExecutorState.ACTIVE) {
            if (exec.currentSequence != null) {
                return exec.currentSequence.label();
            }
            String kind = exec.currentOpKind;
            if (kind != null) {
                if (kind.startsWith("block_interact:")) {
                    return actionDisplayName(kind.substring("block_interact:".length()));
                }
                if (kind.startsWith("ritual:")) {
                    return ritualDisplayName(kind.substring("ritual:".length()));
                }
                if (kind.equals("transform")) return "建造中";
            }
            return "执行中";
        }

        if (exec.state == ExecutorState.WAITING) return "等待中";

        return "";
    }

    private static String actionDisplayName(String action) {
        return switch (action) {
            case "gather" -> "采集中";
            case "place" -> "放置中";
            case "break" -> "破坏中";
            case "interact" -> "交互中";
            case "cast" -> "施法中";
            default -> "执行: " + action;
        };
    }

    private static String ritualDisplayName(String ritual) {
        return switch (ritual) {
            case "self_teleport" -> "传送中";
            case "lightning" -> "召唤雷电";
            case "portal_gate" -> "开启传送门";
            case "rain_call" -> "祈雨";
            case "clear_weather" -> "驱云";
            default -> "施法: " + ritual;
        };
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
            if (getSkinVariant() < 0) {
                this.entityData.set(DATA_SKIN_VARIANT, random.nextInt(SKIN_VARIANT_COUNT));
            }
            if (getHatColor() == 0) {
                this.entityData.set(DATA_HAT_COLOR, generateRandomHatColor());
            }
            // Equip wand on spawn so casting animation shows the item
            setItemInHand(InteractionHand.MAIN_HAND,
                    new ItemStack(Wandscape.WAND.get()));
            // Prevent vanilla despawn — NPC persistence is managed by the colony/engine
            this.setPersistenceRequired();
            World world = WandscapeEngine.getWorld();
            if (world != null) {
                EntityComponentBridge.INSTANCE.onNpcJoinWorld(this, world);
            } else {
                // Engine not yet bootstrapped — entity loaded before ServerStartingEvent.
                // Defer registration until the next tick.
                Log.warn(TAG, "NPC {} onAddedToLevel but Engine World is null — deferring ECS registration",
                        getUUID().toString().substring(0, 8));
                EntityComponentBridge.INSTANCE.deferJoin(this);
            }
        }
    }

    private int generateRandomHatColor() {
        float hue = random.nextFloat();
        float saturation = 0.5f + random.nextFloat() * 0.5f;
        float brightness = 0.3f + random.nextFloat() * 0.7f;
        int rgb = java.awt.Color.HSBtoRGB(hue, saturation, brightness);
        return 0xFF000000 | (rgb & 0x00FFFFFF);
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

                    // Return equipped wands to colony warehouse on death/despawn
                    returnEquippedWands(world);

                    // Release resource reservations from pending transports.
                    // Items were reserved but never consumed — just dropping the
                    // reservation is correct (no items need to be returned to bank).
                    var resourceReqExec = WandscapeEngine.getResourceRequestExec();
                    if (resourceReqExec != null) {
                        resourceReqExec.cancelForNpc(ecsEntityId);
                    }

                    // Orphan recovery: cancel all in-flight transports for this NPC
                    var transporter = WandscapeEngine.getTransporter();
                    if (transporter != null) {
                        var bank = com.wsteam.wandscape.warehouse.ColonyItemBank.get(level());
                        if (bank != null) {
                            UUID cid = this.colonyId != null ? this.colonyId : new UUID(0, 0);
                            var member = world.get(ecsEntityId,
                                    com.wsteam.wandscape.core.component.ColonyMember.class);
                            if (member != null && member.colonyId() != null) cid = member.colonyId();
                            transporter.cancelForNpc(ecsEntityId, bank, cid);
                        }
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

    /**
     * Return any equipped wands to the colony warehouse on death/despawn.
     * Must be called BEFORE {@link EntityComponentBridge#onNpcLeaveWorld}
     * since that destroys the ECS components.
     */
    private void returnEquippedWands(World world) {
        if (ecsEntityId < 0) return;
        var wc = world.get(ecsEntityId, com.wsteam.wandscape.core.component.WandCarrier.class);
        if (wc == null || wc.equippedWandIds().isEmpty()) return;

        var bank = com.wsteam.wandscape.warehouse.ColonyItemBank.get(level());
        if (bank == null) return;

        UUID cid = this.colonyId != null ? this.colonyId : new UUID(0, 0);
        var member = world.get(ecsEntityId,
                com.wsteam.wandscape.core.component.ColonyMember.class);
        if (member != null && member.colonyId() != null) {
            cid = member.colonyId();
        }

        for (String presetId : wc.equippedWandIds()) {
            // All wands are stored as "wandscape:wand" with NBT from the preset
            var preset = Wandscape.WAND_PRESET_LOADER.getPreset(presetId);
            if (preset == null) {
                Log.warn(TAG, "[NPC-death] unknown wand preset {}, cannot return", presetId);
                continue;
            }
            var key = com.wsteam.wandscape.shared.data.ItemKey.of(
                    "wandscape:wand", preset.nbt().copy());
            bank.add(cid, key, 1);
            Log.info(TAG, "[NPC-death] returned {} to warehouse (colony={})", presetId, cid);
        }
    }

    // ============================================================
    // NBT persistence
    // ============================================================

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("SkinVariant", getSkinVariant());
        tag.putInt("HatColor", getHatColor());
        // Read current mana from ECS (the authoritative source at runtime)
        World world = WandscapeEngine.getWorld();
        if (world != null && ecsEntityId > 0) {
            ManaPool mana = world.get(ecsEntityId, ManaPool.class);
            tag.putInt("currentMana", mana != null ? (int) mana.current() : currentMana);
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
        if (tag.contains("SkinVariant")) {
            this.entityData.set(DATA_SKIN_VARIANT, tag.getInt("SkinVariant"));
        }
        if (tag.contains("HatColor")) {
            this.entityData.set(DATA_HAT_COLOR, tag.getInt("HatColor"));
        }
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
        return exec == null || !(exec.npcQueue.hasWork() || exec.globalTaskId != null);
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
