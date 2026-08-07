package com.wsteam.wandscape.npc.entity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.core.component.ColonyMember;
import com.wsteam.wandscape.core.component.EquipmentComponent;
import com.wsteam.wandscape.core.component.MagicState;
import com.wsteam.wandscape.core.component.NavigationState;
import com.wsteam.wandscape.core.component.TaskExecutor;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.types.AttributeType;
import com.wsteam.wandscape.core.types.NpcAttributes;
import com.wsteam.wandscape.npc.network.NpcDataPacket;
import com.wsteam.wandscape.task.runtime.ExecutorState;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.engine.nav.WandscapeNavigation;
import com.wsteam.wandscape.npc.internal.EntityComponentBridge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.PacketDistributor;
import com.wsteam.wandscape.shared.entity.VillagerLike;
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
public class WandscapeNpc extends PathfinderMob implements VillagerLike {

    private static final String TAG = "WandscapeNpc";

    // ============================================================
    // Engine bridge (public for same-module cross-package access)
    // ============================================================

    /** ECS World entity ID — assigned by EntityComponentBridge on join. */
    public long ecsEntityId = -1;

    /** Colony membership. Stage 2: placeholder UUID. Stage 4: real colony. */
    public UUID colonyId = EntityComponentBridge.PLACEHOLDER_COLONY;

    // ============================================================
    // Attributes (ECS EquipmentComponent is authoritative at runtime:
    // base = these fields + equipment modifiers; these fields are NBT transit)
    // ============================================================

    public float maxHp = NpcAttributes.defaults().maxHp();
    public float moveSpeed = NpcAttributes.defaults().moveSpeed();
    public float spellPower = NpcAttributes.defaults().spellPower();
    public float workSpeed = NpcAttributes.defaults().workSpeed();
    public float spellSpeed = NpcAttributes.defaults().spellSpeed();
    public float armorValue = NpcAttributes.defaults().armorValue();
    public float maxMana = NpcAttributes.defaults().maxMana();

    // ============================================================
    // 魔力值 + 每魔法独立 CD + 施法互斥锁（纯逻辑在 core/component/MagicState）
    // 魔力上限 = 第 7 属性 MAX_MANA（ECS EquipmentComponent 权威，getEffectiveAttribute 读取）
    // ============================================================

    public final MagicState magic = new MagicState();

    /** 当前魔力。 */
    public float getCurrentMana() {
        return magic.getMana();
    }

    /** 魔力上限（第 7 属性有效值）。 */
    public float getMaxMana() {
        return getEffectiveAttribute(AttributeType.MAX_MANA);
    }

    /**
     * 原子施放门控：互斥锁 + 该魔法独立 CD + 固定魔力消耗，全满足才成功。
     * CD 基础值按 SPELL_SPEED 缩短（向上取整）；成功后占用 {@code lockDurationTicks} 的施法互斥锁。
     */
    public boolean tryCastSpell(String magicId, int baseCooldown, int manaCost, int lockDurationTicks) {
        return magic.tryCast(magicId, baseCooldown, manaCost, lockDurationTicks,
                getEffectiveAttribute(AttributeType.SPELL_SPEED));
    }

    // ============================================================
    // 脱战生命恢复：受击后封伤 grace tick，之后每 interval tick 回 1 HP。
    // 剩余值 NBT 持久（tick 数可跨存档）。
    // ============================================================

    private int regenCooldown = 0;
    private int regenAccum = 0;

    /** 受击时调用（SelfDefenseHandler）：重置脱战封伤计时。 */
    public void markRecentlyDamaged() {
        regenCooldown = Config.NPC_REGEN_GRACE_TICKS.get();
        regenAccum = 0;
    }

    /** 每 server tick：脱战封伤计时递减，封伤过后按 interval 累计回血。 */
    private void tickHealthRegen() {
        if (regenCooldown > 0) {
            regenCooldown--;
            return;
        }
        if (getHealth() < getMaxHealth()) {
            regenAccum++;
            if (regenAccum >= Config.NPC_REGEN_INTERVAL_TICKS.get()) {
                regenAccum = 0;
                heal(1f);
            }
        } else {
            regenAccum = 0;
        }
    }

    /**
     * 读取 NPC 有效属性（ECS EquipmentComponent：base + 装备加成）。
     * ECS 不可用时回退到 NBT transit 字段。
     */
    public float getEffectiveAttribute(AttributeType type) {
        World world = WandscapeEngine.getWorld();
        if (world != null && ecsEntityId > 0) {
            EquipmentComponent eq = world.get(ecsEntityId, EquipmentComponent.class);
            if (eq != null) return eq.getAttribute(type);
        }
        return switch (type) {
            case MAX_HP -> maxHp;
            case MOVE_SPEED -> moveSpeed;
            case SPELL_POWER -> spellPower;
            case WORK_SPEED -> workSpeed;
            case SPELL_SPEED -> spellSpeed;
            case ARMOR_VALUE -> armorValue;
            case MAX_MANA -> maxMana;
        };
    }

    /** 上次推送到 vanilla 的属性值（防每 tick 重复 setBaseValue）。 */
    private float appliedMaxHp = -1;
    private float appliedMoveSpeed = -1;
    private float appliedArmor = -1;

    /** 把有效属性推送到 vanilla 实体（最大生命/移速/护甲）。 */
    private void applyEffectiveAttributes() {
        World world = WandscapeEngine.getWorld();
        if (world == null || ecsEntityId <= 0) return;
        EquipmentComponent eq = world.get(ecsEntityId, EquipmentComponent.class);
        if (eq == null) return;
        float maxHp = eq.getAttribute(AttributeType.MAX_HP);
        float speed = eq.getAttribute(AttributeType.MOVE_SPEED);
        float armor = eq.getAttribute(AttributeType.ARMOR_VALUE);
        if (Math.abs(maxHp - appliedMaxHp) > 0.001f) {
            appliedMaxHp = maxHp;
            this.getAttribute(Attributes.MAX_HEALTH).setBaseValue(maxHp);
            if (getHealth() > maxHp) setHealth(maxHp);
        }
        if (Math.abs(speed - appliedMoveSpeed) > 0.001f) {
            appliedMoveSpeed = speed;
            this.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(speed);
        }
        if (Math.abs(armor - appliedArmor) > 0.001f) {
            appliedArmor = armor;
            this.getAttribute(Attributes.ARMOR).setBaseValue(armor);
        }
    }

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

    /**
     * When true, the NPC is holding the default spawned wand and the wand slot
     * in the mage screen should appear empty to prevent players from taking it.
     */
    private boolean hasDefaultWand = true;

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

    // ── 手动施法（shift+右键）：窗口内强制 isCasting=true，与 ECS 驱动的施法互不干扰 ──
    private int manualCastTicks = 0;

    /**
     * 触发一次手动施法：在 {@code ticks} 内保持举杖姿态（isCasting=true）。
     * 窗口结束由 tick() 自动恢复为 ECS 决定的状态。
     */
    public void startManualCast(int ticks) {
        manualCastTicks = Math.max(manualCastTicks, ticks);
        setCasting(true);
    }

    // ── 自防御仇恨：被非玩家攻击者打伤后记仇，直到对方死亡/超出范围/过期 ──
    private UUID hatedAttackerUuid = null;
    private long hateExpiryTick = 0;

    /** 记录仇恨目标（非玩家攻击者）与其过期 tick。 */
    public void setHatedAttacker(UUID attackerUuid, long expiryTick) {
        this.hatedAttackerUuid = attackerUuid;
        this.hateExpiryTick = expiryTick;
    }

    /**
     * 当前有效仇恨目标：未过期且在所在 Level 中存活的生物；否则 null。
     * 由 {@code SelfDefenseExecutor} 每轮目标解析调用。
     */
    @Nullable
    public LivingEntity getHatedAttacker(ServerLevel level) {
        if (hatedAttackerUuid == null || level.getGameTime() > hateExpiryTick) return null;
        Entity e = level.getEntity(hatedAttackerUuid);
        return (e instanceof LivingEntity le && le.isAlive() && !le.isRemoved()) ? le : null;
    }

    /** 仇恨已过期或目标已死/不存在时清除，避免空转。 */
    public void clearHatedAttackerIfExpired(ServerLevel level) {
        if (hatedAttackerUuid != null && getHatedAttacker(level) == null) {
            hatedAttackerUuid = null;
            hateExpiryTick = 0;
        }
    }

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

    @Override
    protected PathNavigation createNavigation(Level level) {
        return new WandscapeNavigation(this, level);
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
                .add(Attributes.FOLLOW_RANGE, 48.0);
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

        // 脱战回血 + 属性推送 + 魔力回复：idle NPC 也要执行，放在快路 return 之前
        tickHealthRegen();
        applyEffectiveAttributes();
        // 首 tick 满蓝填充（新 NPC / 旧存档迁移），此后每 10tick 回 1 点
        if (!magic.isManaSeeded()) {
            magic.setMana(getMaxMana());
            magic.markManaSeeded();
        }
        magic.tickRegen(getMaxMana(), Config.NPC_MANA_REGEN_TICKS.get());

        boolean manual = manualCastTicks > 0;
        if (manual) manualCastTicks--;

        boolean casting;
        if (ecsPollCooldown > 0 && !isCasting() && !manual) {
            // Fast path: idle NPC, skip ECS query this tick
            ecsPollCooldown--;
            return;
        } else {
            World ecsWorld = WandscapeEngine.getWorld();
            if (ecsWorld != null && ecsEntityId > 0) {
                var exec = ecsWorld.get(ecsEntityId,
                        TaskExecutor.class);
                casting = exec != null
                        && exec.state == ExecutorState.ACTIVE
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

        if (manual) casting = true;
        if (casting != isCasting()) {
            setCasting(casting);
        }
        if (isCasting() && !suppressWandering) {
            getNavigation().stop();
            setDeltaMovement(Vec3.ZERO);
        }
    }

    // ============================================================
    // 施法几何（与客户端渲染器/模型同一套，保证法阵/光束落在持杖手上）
    // 右臂举杖姿态基准角统一在此，避免模型/渲染/服务端三处硬编码漂移。
    // 瞄准目标时 faceTarget() 设置 getXRot（俯仰角），手臂角度随之指向目标。
    // ============================================================

    /** 举杖姿态右臂 xRot 基准角（弧度）：模型 rightArm.xRot = 此值 + 俯仰角。 */
    public static final double CAST_ARM_ANGLE = -1.2;
    /** 手臂长度（方块）。 */
    public static final double CAST_ARM_LENGTH = 0.75;

    /** 当前右臂抬起角（弧度）= 基准角 + NPC 俯仰角。getXRot 由 faceTarget() 对准目标时设置。 */
    public double getCastArmAngle() {
        return CAST_ARM_ANGLE + Math.toRadians(getXRot());
    }

    /** 持法杖的右手世界位置。 */
    public Vec3 getStaffPosition() {
        double yawRad = Math.toRadians(yBodyRot);
        double cos = Math.cos(yawRad);
        double sin = Math.sin(yawRad);
        double armAngle = getCastArmAngle();
        double deltaY = -CAST_ARM_LENGTH * (Math.cos(armAngle) - Math.cos(CAST_ARM_ANGLE));
        double deltaFwd = -CAST_ARM_LENGTH * (Math.sin(armAngle) - Math.sin(CAST_ARM_ANGLE));
        double fwd = 0.6 + deltaFwd;
        double oy = getY() + 1.5 + deltaY;
        double ox = getX() - 0.65 * cos - fwd * sin;
        double oz = getZ() - 0.65 * sin + fwd * cos;
        return new Vec3(ox, oy, oz);
    }

    /** 水平正前方向（基于 yBodyRot，与 spawnCastRay 无目标 fallback 一致）。 */
    public Vec3 getFacingDirection() {
        double yawRad = Math.toRadians(yBodyRot);
        return new Vec3(-Math.sin(yawRad), 0, Math.cos(yawRad)).normalize();
    }

    /** Face the NPC toward a target block (yaw from horizontal, pitch from vertical angle). */
    public void faceTarget(BlockPos target) {
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

        var exec = ecsWorld.get(ecsEntityId, TaskExecutor.class);
        var nav = ecsWorld.get(ecsEntityId, NavigationState.class);

        // 1. Navigation states (visible even if idle task-wise)
        if (nav != null) {
            switch (nav.mode) {
                case TELEPORT_WAITING -> { return "waiting_magic"; }
                case TELEPORT_RITUAL   -> { return "waiting_teleport"; }
                case PATHFINDING       -> { return "moving"; }
            }
        }

        // 2. No task executor or no work → idle
        if (exec == null || !(exec.npcQueue.hasWork() || exec.globalTaskId != null) || exec.state == ExecutorState.IDLE) return "idle";

        // 3. Pending async future (navigation or channeled op)
        if (exec.pendingFuture != null && !exec.pendingFuture.isDone()) {
            if (exec.pendingFutureIsNav) return "moving";
            // Channeled op in progress
            String kind = exec.currentOpKind;
            if (kind != null) {
                if (kind.startsWith("block_interact:")) {
                    String action = kind.substring("block_interact:".length());
                    return actionKey(action);
                }
                if (kind.startsWith("ritual:")) {
                    String ritual = kind.substring("ritual:".length());
                    return ritualKey(ritual);
                }
                if (kind.equals("combat")) return "combat";
            }
            return "guiding";
        }

        // 4. Actively executing
        if (exec.state == ExecutorState.ACTIVE) {
            if (exec.currentSequence != null) {
                return "task:" + exec.currentSequence.label();
            }
            String kind = exec.currentOpKind;
            if (kind != null) {
                if (kind.startsWith("block_interact:")) {
                    return actionKey(kind.substring("block_interact:".length()));
                }
                if (kind.startsWith("ritual:")) {
                    return ritualKey(kind.substring("ritual:".length()));
                }
                if (kind.equals("transform")) return "transforming";
                if (kind.equals("combat")) return "combat";
            }
            return "executing";
        }

        if (exec.state == ExecutorState.WAITING) return "waiting";

        return "";
    }

    private static String actionKey(String action) {
        return switch (action) {
            case "gather" -> "gathering";
            case "place" -> "placing";
            case "break" -> "breaking";
            case "interact" -> "interacting";
            case "cast" -> "casting";
            default -> "op:" + action;
        };
    }

    private static String ritualKey(String ritual) {
        return switch (ritual) {
            case "self_teleport" -> "teleporting";
            case "lightning" -> "summon_lightning";
            case "portal_gate" -> "portal_gate";
            case "rain_call" -> "rain_call";
            case "clear_weather" -> "clear_weather";
            default -> "ritual:" + ritual;
        };
    }

    /**
     * Client-side fallback (zh) for a status key, shown only when the lang
     * entry is missing. Keys prefixed {@code op:}/{@code ritual:}/{@code task:}
     * carry dynamic payloads and never resolve via lang — fallback reassembles
     * the original display text.
     */
    public static String statusFallback(String statusKey) {
        return switch (statusKey) {
            case "waiting_magic" -> "等待魔力";
            case "waiting_teleport" -> "等待传送";
            case "moving" -> "移动中";
            case "idle" -> "空闲";
            case "gathering" -> "采集中";
            case "placing" -> "放置中";
            case "breaking" -> "破坏中";
            case "interacting" -> "交互中";
            case "casting" -> "施法中";
            case "combat" -> "战斗中";
            case "guiding" -> "引导中";
            case "transforming" -> "建造中";
            case "executing" -> "执行中";
            case "waiting" -> "等待中";
            case "teleporting" -> "传送中";
            case "summon_lightning" -> "召唤雷电";
            case "portal_gate" -> "开启传送门";
            case "rain_call" -> "祈雨";
            case "clear_weather" -> "驱云";
            default -> {
                if (statusKey.startsWith("op:")) yield "执行: " + statusKey.substring(3);
                if (statusKey.startsWith("ritual:")) yield "施法: " + statusKey.substring(7);
                if (statusKey.startsWith("task:")) yield statusKey.substring(5);
                yield statusKey;
            }
        };
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (level().isClientSide) {
            return InteractionResult.SUCCESS;
        }
        // Send NPC data to the player to open the info/equipment screen
        if (player instanceof ServerPlayer sp) {
            PacketDistributor.sendToPlayer(sp, NpcDataPacket.from(this));
        }
        return InteractionResult.CONSUME;
    }

    // ── Default wand tracking ──

    /** Whether the NPC still has the default spawned wand (prevent player from taking it). */
    public boolean hasDefaultWand() {
        return hasDefaultWand;
    }

    public void setHasDefaultWand(boolean value) {
        this.hasDefaultWand = value;
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
                            TaskExecutor.class);
                    if (exec != null && exec.globalTaskId != null) {
                        world.taskPool.releaseTaskForReassign(
                                exec.globalTaskId, ecsEntityId, world);
                    }

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
                                    ColonyMember.class);
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

    // ============================================================
    // NBT persistence
    // ============================================================

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("SkinVariant", getSkinVariant());
        tag.putInt("HatColor", getHatColor());
        tag.putLong("EcsEntityId", ecsEntityId);
        tag.putFloat("maxHp", maxHp);
        tag.putFloat("moveSpeed", moveSpeed);
        tag.putFloat("spellPower", spellPower);
        tag.putFloat("workSpeed", workSpeed);
        tag.putFloat("spellSpeed", spellSpeed);
        tag.putFloat("armorValue", armorValue);
        tag.putFloat("maxMana", maxMana);
        tag.putFloat("currentMana", magic.getMana());
        tag.putInt("manaRegenAccum", magic.getManaRegenAccum());
        tag.putInt("spellLockTicks", magic.getLockTicks());
        tag.putBoolean("manaSeeded", magic.isManaSeeded());
        CompoundTag magicCds = new CompoundTag();
        for (Map.Entry<String, Integer> e : magic.getCooldowns().entrySet()) {
            magicCds.putInt(e.getKey(), e.getValue());
        }
        tag.put("magicCooldowns", magicCds);
        tag.putInt("regenCooldown", regenCooldown);
        tag.putInt("regenAccum", regenAccum);
        tag.putBoolean("hasDefaultWand", hasDefaultWand);
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
        maxHp = tag.getFloat("maxHp");
        moveSpeed = tag.getFloat("moveSpeed");
        spellPower = tag.getFloat("spellPower");
        workSpeed = tag.getFloat("workSpeed");
        spellSpeed = tag.getFloat("spellSpeed");
        armorValue = tag.getFloat("armorValue");
        maxMana = tag.getFloat("maxMana");
        Map<String, Integer> cds = new HashMap<>();
        if (tag.contains("magicCooldowns")) {
            CompoundTag mc = tag.getCompound("magicCooldowns");
            for (String key : mc.getAllKeys()) {
                cds.put(key, mc.getInt(key));
            }
        }
        magic.load(tag.getFloat("currentMana"), tag.getInt("manaRegenAccum"),
                tag.getInt("spellLockTicks"), tag.getBoolean("manaSeeded"), cds);
        regenCooldown = tag.getInt("regenCooldown");
        regenAccum = tag.getInt("regenAccum");
        hasDefaultWand = tag.getBoolean("hasDefaultWand");
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
        var exec = world.get(ecsEntityId, TaskExecutor.class);
        return exec == null || !(exec.npcQueue.hasWork() || exec.globalTaskId != null);
    }

    @Nullable
    public UUID getCurrentTaskId() {
        if (ecsEntityId < 0) return null;
        World world = WandscapeEngine.getWorld();
        if (world == null) return null;
        var exec = world.get(ecsEntityId, TaskExecutor.class);
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
