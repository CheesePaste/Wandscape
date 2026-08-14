package com.wsteam.wandscape.tourist.entity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import com.wsteam.wandscape.engine.nav.WandscapeNavigation;
import com.wsteam.wandscape.shared.data.Activity;
import com.wsteam.wandscape.shared.data.BarRatio;
import com.wsteam.wandscape.shared.data.Emotion;
import com.wsteam.wandscape.shared.data.MageAttributeRoller;
import com.wsteam.wandscape.shared.data.RecruitmentCandidate;
import com.wsteam.wandscape.shared.data.VisitMemory;
import com.wsteam.wandscape.shared.entity.VillagerLike;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import com.wsteam.wandscape.shared.registry.WandscapeConstants;
import com.wsteam.wandscape.tourist.internal.HotelStayHandler;
import com.wsteam.wandscape.tourist.internal.TouristSpawnSystem;
import com.wsteam.wandscape.tourist.internal.TouristSimSystem;
import com.wsteam.wandscape.tourist.internal.TouristSpotManager;
import com.wsteam.wandscape.tourist.internal.TouristStateHost;

import javax.annotation.Nullable;

import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.tourist.internal.TouristState;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import com.wsteam.wandscape.tourist.network.TouristDataPacket;
/**
 * A tourist NPC that visits the colony to interact with shops and service buildings.
 *
 * <p>Extends {@link PathfinderMob} to use player-model rendering with custom skins.
 * Managed by {@link com.wsteam.wandscape.tourist.internal.TouristSpawnSystem} (time-scheduled spawn/despawn).
 * Uses {@link com.wsteam.wandscape.tourist.internal.TouristMoveGoal} for unified movement.
 *
 * <p>95% tourist appearance (skins from {@code textures/entity/tourist}),
 * 5% mage appearance (skins from {@code textures/entity/wizard}).
 * Mage tourists carry mana/spell-power stats; when their three bars are full,
 * their data is stored in the tavern as a recruitment resume.
 */
public class TouristEntity extends PathfinderMob implements VillagerLike, TouristStateHost {

    private static final String TAG = "TouristEntity";

    // ── Appearance ──

    public enum Appearance {
        TOURIST,
        MAGE
    }

    /** Probability of a tourist being a mage (0.05 = 5%). */
    public static final double MAGE_CHANCE = 0.05;

    // ── Skin variant detection ──

    private static int detectSkinCount(String subPath) {
        try {
            Path dir = ModList.get().getModFileById(Wandscape.MODID).getFile()
                    .findResource("assets", "wandscape", subPath);
            try (Stream<Path> files = Files.list(dir)) {
                return (int) files.filter(p -> p.toString().endsWith(".png")).count();
            }
        } catch (IOException | RuntimeException ignored) {}
        return 1;
    }

    public static final int TOURIST_SKIN_COUNT = detectSkinCount("textures/entity/tourist");
    public static final int WIZARD_SKIN_COUNT  = detectSkinCount("textures/entity/wizard");

    // ── Synched data keys ──

    /** Skin variant — index within the appearance-specific pool (tourist or wizard). */
    private static final EntityDataAccessor<Integer> DATA_SKIN_VARIANT =
            SynchedEntityData.defineId(TouristEntity.class, EntityDataSerializers.INT);
    /** 0 = TOURIST, 1 = MAGE. */
    private static final EntityDataAccessor<Byte> DATA_APPEARANCE =
            SynchedEntityData.defineId(TouristEntity.class, EntityDataSerializers.BYTE);
    /** 当前活动动作（Activity ordinal，-1 = 无）。同步给客户端驱动姿态/粒子渲染。 */
    private static final EntityDataAccessor<Integer> DATA_ACTIVITY =
            SynchedEntityData.defineId(TouristEntity.class, EntityDataSerializers.INT);
    /** 预览假人标记（客户端用于跳过气泡渲染）。 */
    private static final EntityDataAccessor<Boolean> DATA_PREVIEW =
            SynchedEntityData.defineId(TouristEntity.class, EntityDataSerializers.BOOLEAN);

    // ── Debug synched data (for TouristDebugRenderer) ──

    /** Current commute target (where the tourist is navigating to). */
    private static final EntityDataAccessor<Optional<BlockPos>> DEBUG_COMMUTE_TARGET =
            SynchedEntityData.defineId(TouristEntity.class, EntityDataSerializers.OPTIONAL_BLOCK_POS);
    /** Entry point for current building visit (macro nav destination). */
    private static final EntityDataAccessor<Optional<BlockPos>> DEBUG_ENTRY_POINT =
            SynchedEntityData.defineId(TouristEntity.class, EntityDataSerializers.OPTIONAL_BLOCK_POS);
    /** Interact point for current building visit (micro nav destination). */
    private static final EntityDataAccessor<Optional<BlockPos>> DEBUG_INTERACT_POINT =
            SynchedEntityData.defineId(TouristEntity.class, EntityDataSerializers.OPTIONAL_BLOCK_POS);
    /** Whether tourist is in indoor micro-navigation phase. */
    private static final EntityDataAccessor<Boolean> DEBUG_INDOOR_PHASE =
            SynchedEntityData.defineId(TouristEntity.class, EntityDataSerializers.BOOLEAN);

    // ── Identity ──

    private String touristName = "";

    /**
     * True when this entity was restored from saved world data (chunk load)
     * rather than freshly spawned. Fresh spawns get adopted into the tourist sim
     * shadow registry; disk-loaded bodies that outlived their departed shadow are
     * left for the sim's orphan sweep to discard.
     */
    private boolean loadedFromDisk;

    // ── State label (synced by TouristMoveGoal) ──

    private TouristState currentState = TouristState.IDLE;

    /**
     * Non-null when a command forces a specific MoveMode.
     * Checked and consumed by {@link TouristMoveGoal} each tick.
     * Setting this to null (or an invalid value) clears the override.
     */
    @javax.annotation.Nullable
    private TouristState forcedMoveMode = null;

    @Nullable
    private BlockPos commuteTarget;
    private boolean commuteArrived;

    @Nullable
    private BlockPos wanderAnchor;
    private int wanderRadius = 8;

    private List<BlockPos> poiList = List.of();

    // ── Tourist attributes ──

    private int energy = WandscapeConstants.TOURIST_MAX_ENERGY;
    private int level = 1;
    /** Universal-element spending money. Higher tourist levels start with more. */
    private int wallet;
    /** The wallet the tourist arrived with — caps each shopping trip's budget. */
    private int initialWallet;

    // ── 三条需求条（fill/need）＋ 画像 / 活动 / 停留 / 总旅费（Block 2） ──

    private int comfortSat, magicSat, wonderSat;
    private int comfortNeed = 100, magicNeed = 100, wonderNeed = 100;
    /** 活动状态存 synched data（DATA_ACTIVITY），客户端渲染姿态/粒子用。 */
    private int activityTicks;
    private int occupiedSpot = -1;
    private int nightsStayed;
    private long departureDeadline = Long.MAX_VALUE;
    private int travelFund;
    /** ATM 上次成功取现的 timeBase（tickCount）时刻；0 = 从未取现。取现冷却起点。 */
    private int lastAtmWithdrawTime;

    /** 预览模式（交互位 marker 的演示假人）：不参与 AI/生成/离开，仅站桩循环做动作。 */
    private boolean previewMode;

    /** 活动期间锁定的朝向 yaw（null=不锁定）。交互动作时锁定面向 spot，防 LookControl/MoveControl 拉偏。 */
    @javax.annotation.Nullable
    private Float frozenYaw;

    /** 用餐（EAT）时手持的食物（registry id，缺省面包）。 */
    private String heldFoodItem = "minecraft:bread";

    // ── Mage-only attributes (stored in tavern recruitment resume at three-bars-full) ──

    private float maxHp = 40f;
    private float moveSpeed = 0.3f;
    private float spellPower = 1f;
    private float workSpeed = 1f;
    private float spellSpeed = 1f;
    private float armorValue = 0f;
    private float maxMana = 200f;

    /** Whether the mage resume has already been stored in the tavern for this tourist. */
    private boolean mageResumeStored;

    @Nullable
    private UUID colonyId;

    /** Target building ID the tourist is currently navigating to. */
    @Nullable
    private UUID targetBuildingId;

    /** Target building category (shop/service) for interaction. */
    @Nullable
    private String targetBuildingCategory;

    /** Hotel building ID the tourist is currently checked into, null if not staying. */
    @Nullable
    private UUID checkedInBuildingId;

    /** Tick count when the tourist checked into the hotel. */
    private int hotelCheckinTime;

    /** Position to return to when checking out of the hotel in the morning
     *  (the spot where the tourist stood before teleporting into a bed). */
    @Nullable
    private BlockPos wakeUpPos;

    /** Set of building IDs the tourist has already visited this trip. */
    private final Set<UUID> visitedBuildings = new HashSet<>();

    // ── Narrative memory (journey diary) ──

    /** Visit memories for the current journey (max 24, FIFO, not persisted). */
    private final List<VisitMemory> recentVisits = new ArrayList<>();
    private static final int MAX_VISIT_MEMORIES = 24;
    /** Game tick when the tourist arrived at the colony. */
    private long arrivalTime;

    public TouristEntity(EntityType<? extends PathfinderMob> entityType, Level level) {
        super(entityType, level);
        setCustomNameVisible(true);
    }

    // ──────────────────────── Synched data ────────────────────────

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void doPush(net.minecraft.world.entity.Entity entity) {
        if (entity instanceof TouristEntity) {
            return;
        }
        super.doPush(entity);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_SKIN_VARIANT, -1);
        builder.define(DATA_APPEARANCE, (byte) 0);
        builder.define(DATA_ACTIVITY, -1);
        builder.define(DATA_PREVIEW, false);
        builder.define(DEBUG_COMMUTE_TARGET, Optional.empty());
        builder.define(DEBUG_ENTRY_POINT, Optional.empty());
        builder.define(DEBUG_INTERACT_POINT, Optional.empty());
        builder.define(DEBUG_INDOOR_PHASE, false);
    }

    /** Skin variant — index within the appearance-specific pool. */
    public int getSkinVariant() {
        return entityData.get(DATA_SKIN_VARIANT);
    }

    public void setSkinVariant(int variant) {
        entityData.set(DATA_SKIN_VARIANT, variant);
    }

    public Appearance getAppearance() {
        return entityData.get(DATA_APPEARANCE) == 1 ? Appearance.MAGE : Appearance.TOURIST;
    }

    public void setAppearance(Appearance appearance) {
        entityData.set(DATA_APPEARANCE, (byte) (appearance == Appearance.MAGE ? 1 : 0));
    }

    public boolean isMage() {
        return getAppearance() == Appearance.MAGE;
    }

    // ──────────────────────── AI goals ────────────────────────

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new OpenDoorGoal(this, true));
        this.goalSelector.addGoal(2, new com.wsteam.wandscape.tourist.internal.TouristMoveGoal(this, 0.5, 0.35));
        this.goalSelector.addGoal(3, new RandomLookAroundGoal(this));
    }

    // ──────────────────────── Interaction ────────────────────────

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!this.isAlive()) return super.mobInteract(player, hand);
        // 预览假人不可交互（右键不出面板）
        if (previewMode) return InteractionResult.PASS;

        if (level().isClientSide) {
            return InteractionResult.SUCCESS;
        }
        // Send tourist data to the player to open the info screen
        if (player instanceof ServerPlayer sp) {
            PacketDistributor.sendToPlayer(sp, TouristDataPacket.from(this));
        }
        return InteractionResult.CONSUME;
    }

    // ──────────────────────── Lifecycle ────────────────────────

    @Override
    public void onAddedToLevel() {
        super.onAddedToLevel();
        syncName();

        // Spawn-egg tourists arrive without a name or colony — fill both in so
        // they get a display name and can plan building visits. System-spawned
        // tourists already set these before addFreshEntity, so this is a no-op
        // for them. Server-authoritative.
        if (!level().isClientSide) {
            if (touristName.isEmpty()) {
                setTouristName(TouristSpawnSystem.generateRandomTouristName());
            }
            if (colonyId == null) {
                var colonyApi = WandscapeApis.getColonyApiSilently();
                if (colonyApi != null) {
                    UUID detected = colonyApi.getColonyId(blockPosition());
                    if (detected != null) {
                        setColonyId(detected);
                    }
                }
            }
            // Spawn-egg / command tourists bypass applySpawnDefaults — give them the full
            // random-spawn defaults (rolled level, wallet, persona needs, stay window, travel
            // fund) so they behave like a randomly generated tourist. Only truly-uninitialized
            // fresh tourists qualify (deadline is still the Long.MAX_VALUE sentinel): system-
            // spawned and shadow-restored tourists already carry valid values and must not be
            // re-rolled, or their level/wallet/persona get wiped on every load.
            if (!previewMode) {
                if (!loadedFromDisk && getDepartureDeadline() == Long.MAX_VALUE) {
                    TouristSpawnSystem.applyRandomSpawnDefaults(this, colonyId, level().getGameTime());
                }
                // 兜底：applyRandomSpawnDefaults 失败（系统未注册）或旧存档缺少停留字段时仍补停留窗口。
                ensureStayWindow(level().getGameTime());
            }
            // Roll appearance (5% mage + skin variant) BEFORE adopt so the sim shadow captures
            // the rolled values. Adopting first exports the uninitialized -1 / non-mage state,
            // and importToEntity then overwrites the entity with them on the first observed sim
            // tick — every tourist would render the default skin and mages would vanish.
            if (getSkinVariant() < 0) {
                // Step 1: roll appearance (5% mage)
                boolean mage = random.nextDouble() < MAGE_CHANCE;
                int variant;

                if (mage) {
                    variant = random.nextInt(WIZARD_SKIN_COUNT);
                    // 偏斜分布 random⁴（多数偏低、偶发高值 → 自然出专精），等级做加法叠加（更公平）
                    RecruitmentCandidate roll = MageAttributeRoller.roll(level,
                            new java.util.Random(random.nextLong()));
                    maxHp = roll.maxHp();
                    maxMana = roll.maxMana();
                    moveSpeed = roll.moveSpeed();
                    spellPower = roll.spellPower();
                    workSpeed = roll.workSpeed();
                    spellSpeed = roll.spellSpeed();
                    armorValue = roll.armorValue();
                } else {
                    variant = random.nextInt(TOURIST_SKIN_COUNT);
                }

                entityData.set(DATA_SKIN_VARIANT, variant);
                entityData.set(DATA_APPEARANCE, (byte) (mage ? 1 : 0));
            }
            // Register in the live-entity cache for O(1) tick lookup (all non-preview,
            // including disk-loaded). Must be before the shadow-adopt block so the sim
            // tick that follows can find this entity without a full world scan.
            if (!previewMode) {
                TouristSimSystem.registerEntity(this);
            }
            // Freshly-created tourists (spawn egg) have no sim shadow yet — adopt
            // them now, else the sim's orphan sweep discards them as departed
            // bodies. Disk-loaded bodies (loadedFromDisk) are left for that sweep.
            // Preview mannequins are never adopted (no sim, no departure).
            if (!previewMode && !loadedFromDisk) {
                TouristSimSystem sim = TouristSimSystem.getActive();
                if (sim != null && sim.getRegistry() != null && sim.getRegistry().get(getUUID()) == null) {
                    // 到达登记：让殖民地游客计数包含本实体。覆盖所有 fresh 生成路径
                    // （系统生成/刷怪蛋/命令）——生成系统不再自行调用 registerArrival，
                    // 此处恰好每游客触发一次。sim 从 shadow 再水合出的实体（registry
                    // 已有该 uuid）与磁盘加载体（loadedFromDisk）在此被排除，避免重复
                    // 触发 TouristArrivedEvent 虚增「游客到达」统计。
                    if (colonyId != null) {
                        var api = WandscapeApis.getTouristApiSilently();
                        if (api != null) {
                            api.registerArrival(getUUID(), colonyId);
                        }
                    }
                    sim.adoptTourist(this);
                }
            }
        }

        // Re-establish hotel check-in when entity is loaded from disk
        if (checkedInBuildingId != null && !level().isClientSide) {
            HotelStayHandler hotel = HotelStayHandler.getActive();
            if (hotel != null && colonyId != null && hotel.checkIn(this, checkedInBuildingId, colonyId)) {
                hotelCheckinTime = tickCount;
            } else {
                // Hotel no longer available — clear check-in state
                checkedInBuildingId = null;
                hotelCheckinTime = 0;
            }
        }

        // Re-establish active building spot occupation when entity is loaded from disk (e.g. resting on chairs/browsing shops)
        if (targetBuildingId != null && occupiedSpot >= 0 && getCurrentActivity() != null && activityTicks > 0 && !level().isClientSide) {
            if (level() instanceof net.minecraft.server.level.ServerLevel sl) {
                int claimed = com.wsteam.wandscape.tourist.internal.TouristSimulation.claimSpotAt(sl, targetBuildingId, occupiedSpot, getUUID());
                if (claimed < 0) {
                    // Spot already claimed or building no longer valid — clear activity state safely
                    occupiedSpot = -1;
                    setCurrentActivity(null);
                    activityTicks = 0;
                }
            }
        }
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        return new WandscapeNavigation(this, level);
    }

    @Override
    public boolean shouldBeSaved() { return !previewMode; }

    /** 预览假人免疫伤害（仅站桩演示，不能被炸死/打掉）；游客免疫摔落伤害（防坐标对齐/微操/半砖导致的异常摔死）。 */
    @Override
    public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
        if (previewMode) return false;
        if (source.is(net.minecraft.world.damagesource.DamageTypes.FALL)) return false;
        return super.hurt(source, amount);
    }

    @Override
    public boolean removeWhenFarAway(double d) { return false; }

    /**
     * 锁定朝向：交互动作/预览期间（frozenYaw != null），在整帧 tick 末尾强制把
     * yRot/yBodyRot/yHeadRot 设回锁定值——LookControl/MoveControl/BodyRotationControl
     * 在 aiStep 里会用 setYRot 覆盖 spot 朝向，这里收尾兜底保证游客不转身。
     */
    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide && frozenYaw != null) {
            float yaw = frozenYaw;
            this.setYRot(yaw);
            this.yRotO = yaw;
            this.yBodyRot = yaw;
            this.yBodyRotO = yaw;
            this.setYHeadRot(yaw);
            this.yHeadRotO = yaw;
        }
    }

    /**
     * Only fresh spawns go through finalizeSpawn — clear the disk-load flag so a
     * spawn egg carrying custom entity NBT isn't mistaken for a restored body.
     */
    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty,
            MobSpawnType spawnType, @Nullable SpawnGroupData spawnGroupData) {
        SpawnGroupData data = super.finalizeSpawn(level, difficulty, spawnType, spawnGroupData);
        this.loadedFromDisk = false;
        return data;
    }

    /**
     * A killed/discarded tourist must fully die. Its data shadow would otherwise
     * make the sim respawn it at its old position — clear the shadow, free hotel
     * occupancy and the colony's population slot.
     */
    @Override
    public void onRemovedFromLevel() {
        super.onRemovedFromLevel();
        RemovalReason reason = getRemovalReason();
        if (level().isClientSide || reason == null) return;
        // 任意移除原因都释放其交互 spot 占位并清其排队登记——否则 occupancy/queue 残留
        // （离场 discard、被击杀、随世界卸载），该建筑永久显示占用/排队，新游客被分流饿死。
        if (!previewMode) {
            UUID bid = getTargetBuildingId();
            if (bid != null) {
                TouristSpotManager spots = TouristSpotManager.getActive();
                spots.leaveAllQueues(bid, getUUID());
                int occupied = getOccupiedSpot();
                if (occupied >= 0) {
                    spots.release(bid, occupied);
                }
            }
            // Unregister from the live-entity cache so subsequent ticks don't
            // see a stale/detached entity reference.
            TouristSimSystem.unregisterEntity(getUUID());
        }
        if (reason == RemovalReason.KILLED || reason == RemovalReason.DISCARDED) {
            onTouristKilled();
        }
    }

    private void onTouristKilled() {
        // 预览假人：无 shadow/无离场，禁止任何 departure 副作用
        if (previewMode) return;
        UUID colonyId = getColonyId();
        if (getCheckedInBuildingId() != null) {
            HotelStayHandler hotel = HotelStayHandler.getActive();
            if (hotel != null && level() instanceof ServerLevel sl) {
                hotel.checkOut(this, sl);
            }
        }
        TouristSimSystem sim = TouristSimSystem.getActive();
        if (sim != null) {
            sim.removeShadow(getUUID());
        }
        if (colonyId != null) {
            var api = WandscapeApis.getTouristApiSilently();
            if (api != null) {
                api.registerDeparture(getUUID(), colonyId,
                        BarRatio.of(getComfortSat(), getComfortNeed(), getMagicSat(), getMagicNeed(),
                                getWonderSat(), getWonderNeed()));
            }
        }
    }

    /** Time base for cooldown comparisons — mirrors {@link TouristStateHost#timeBase()}. */
    @Override
    public int timeBase() { return this.tickCount; }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("touristName", touristName);
        tag.putString("currentState", currentState.name());

        // Save skin variant and appearance (SynchedEntityData is NOT auto-saved)
        tag.putInt("skinVariant", entityData.get(DATA_SKIN_VARIANT));
        tag.putByte("appearance", entityData.get(DATA_APPEARANCE));

        if (commuteTarget != null) {
            tag.putLong("commuteTarget", commuteTarget.asLong());
        }
        if (wanderAnchor != null) {
            tag.putLong("wanderAnchor", wanderAnchor.asLong());
        }
        tag.putInt("wanderRadius", wanderRadius);
        tag.putInt("energy", energy);
        tag.putInt("level", level);
        tag.putInt("wallet", wallet);
        tag.putInt("initialWallet", initialWallet);

        // ── 三条需求条 / 活动 / 停留 / 总旅费（Block 2）──
        tag.putInt("comfortSat", comfortSat);
        tag.putInt("magicSat", magicSat);
        tag.putInt("wonderSat", wonderSat);
        tag.putInt("comfortNeed", comfortNeed);
        tag.putInt("magicNeed", magicNeed);
        tag.putInt("wonderNeed", wonderNeed);
        if (getCurrentActivity() != null) tag.putString("currentActivity", getCurrentActivity().name());
        tag.putInt("activityTicks", activityTicks);
        tag.putInt("occupiedSpot", occupiedSpot);
        tag.putInt("nightsStayed", nightsStayed);
        tag.putLong("departureDeadline", departureDeadline);
        tag.putInt("travelFund", travelFund);
        tag.putInt("lastAtmWithdrawTime", lastAtmWithdrawTime);

        tag.putFloat("maxHp", maxHp);
        tag.putFloat("moveSpeed", moveSpeed);
        tag.putFloat("spellPower", spellPower);
        tag.putFloat("workSpeed", workSpeed);
        tag.putFloat("spellSpeed", spellSpeed);
        tag.putFloat("armorValue", armorValue);
        tag.putFloat("maxMana", maxMana);
        tag.putBoolean("mageResumeStored", mageResumeStored);

        if (colonyId != null) tag.putUUID("colonyId", colonyId);
        if (targetBuildingId != null) tag.putUUID("targetBuildingId", targetBuildingId);
        if (targetBuildingCategory != null) tag.putString("targetBuildingCategory", targetBuildingCategory);
        if (checkedInBuildingId != null) tag.putUUID("checkedInBuildingId", checkedInBuildingId);
        if (wakeUpPos != null) tag.putLong("wakeUpPos", wakeUpPos.asLong());

        // Save visited building IDs
        ListTag visitedList = new ListTag();
        for (UUID id : visitedBuildings) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("id", id);
            visitedList.add(entry);
        }
        tag.put("visitedBuildings", visitedList);

        // Save visit memories (journey diary)
        ListTag visitsList = new ListTag();
        for (VisitMemory v : recentVisits) {
            CompoundTag vt = new CompoundTag();
            vt.putString("buildingTypeId", v.buildingTypeId());
            vt.putString("buildingDisplayName", v.buildingDisplayName());
            vt.putString("category", v.category());
            vt.putLong("gameTime", v.gameTime());
            vt.putInt("comfortDelta", v.comfortDelta());
            vt.putInt("magicDelta", v.magicDelta());
            vt.putInt("wonderDelta", v.wonderDelta());
            vt.putInt("energyDelta", v.energyDelta());
            vt.putString("whatHappened", v.whatHappened());
            visitsList.add(vt);
        }
        tag.put("recentVisits", visitsList);

        tag.putLong("arrivalTime", arrivalTime);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        // Restored from world data — not a fresh spawn (see loadedFromDisk).
        this.loadedFromDisk = true;
        this.touristName = tag.getString("touristName");

        if (tag.contains("currentState")) {
            try {
                this.currentState = TouristState.valueOf(tag.getString("currentState"));
            } catch (IllegalArgumentException e) {
                this.currentState = TouristState.IDLE;
            }
        }

        // Restore skin variant and appearance (SynchedEntityData is NOT auto-loaded)
        if (tag.contains("skinVariant")) {
            entityData.set(DATA_SKIN_VARIANT, tag.getInt("skinVariant"));
        }
        if (tag.contains("appearance")) {
            entityData.set(DATA_APPEARANCE, tag.getByte("appearance"));
        }

        this.commuteTarget = tag.contains("commuteTarget") ? BlockPos.of(tag.getLong("commuteTarget")) : null;
        this.wanderAnchor = tag.contains("wanderAnchor") ? BlockPos.of(tag.getLong("wanderAnchor")) : null;
        this.wanderRadius = tag.getInt("wanderRadius");
        // Clamp values in case of corrupted data
        this.energy = Math.clamp(tag.getInt("energy"), 0, WandscapeConstants.TOURIST_MAX_ENERGY);
        this.level = Math.max(1, tag.getInt("level"));
        this.wallet = Math.max(0, tag.getInt("wallet"));
        this.initialWallet = Math.max(0, tag.getInt("initialWallet"));

        // ── 三条需求条 / 活动 / 停留 / 总旅费（Block 2；旧档无 key 走字段默认）──
        if (tag.contains("comfortNeed")) this.comfortNeed = Math.max(1, tag.getInt("comfortNeed"));
        if (tag.contains("magicNeed")) this.magicNeed = Math.max(1, tag.getInt("magicNeed"));
        if (tag.contains("wonderNeed")) this.wonderNeed = Math.max(1, tag.getInt("wonderNeed"));
        this.comfortSat = Math.clamp(tag.getInt("comfortSat"), 0, comfortNeed);
        this.magicSat = Math.clamp(tag.getInt("magicSat"), 0, magicNeed);
        this.wonderSat = Math.clamp(tag.getInt("wonderSat"), 0, wonderNeed);
        if (tag.contains("currentActivity")) {
            try {
                setCurrentActivity(Activity.valueOf(tag.getString("currentActivity")));
            } catch (IllegalArgumentException e) {
                setCurrentActivity(null);
            }
        }
        this.activityTicks = Math.max(0, tag.getInt("activityTicks"));
        this.occupiedSpot = tag.getInt("occupiedSpot");
        this.nightsStayed = Math.max(0, tag.getInt("nightsStayed"));
        if (tag.contains("departureDeadline")) this.departureDeadline = tag.getLong("departureDeadline");
        if (tag.contains("travelFund")) this.travelFund = Math.max(0, tag.getInt("travelFund"));
        if (tag.contains("lastAtmWithdrawTime")) this.lastAtmWithdrawTime = tag.getInt("lastAtmWithdrawTime");

        this.maxHp = tag.getFloat("maxHp");
        this.moveSpeed = tag.getFloat("moveSpeed");
        this.spellPower = tag.getFloat("spellPower");
        this.workSpeed = tag.getFloat("workSpeed");
        this.spellSpeed = tag.getFloat("spellSpeed");
        this.armorValue = tag.getFloat("armorValue");
        this.maxMana = tag.getFloat("maxMana");
        this.mageResumeStored = tag.getBoolean("mageResumeStored");

        this.colonyId = tag.hasUUID("colonyId") ? tag.getUUID("colonyId") : null;
        this.targetBuildingId = tag.hasUUID("targetBuildingId") ? tag.getUUID("targetBuildingId") : null;
        this.targetBuildingCategory = tag.contains("targetBuildingCategory") ? tag.getString("targetBuildingCategory") : null;
        this.checkedInBuildingId = tag.hasUUID("checkedInBuildingId") ? tag.getUUID("checkedInBuildingId") : null;
        this.wakeUpPos = tag.contains("wakeUpPos") ? BlockPos.of(tag.getLong("wakeUpPos")) : null;

        // Restore visited building IDs
        this.visitedBuildings.clear();
        if (tag.contains("visitedBuildings")) {
            ListTag visitedList = tag.getList("visitedBuildings", Tag.TAG_COMPOUND);
            for (int i = 0; i < visitedList.size(); i++) {
                CompoundTag entry = visitedList.getCompound(i);
                if (entry.hasUUID("id")) {
                    this.visitedBuildings.add(entry.getUUID("id"));
                }
            }
        }

        // Restore visit memories
        this.recentVisits.clear();
        if (tag.contains("recentVisits")) {
            ListTag visitsList = tag.getList("recentVisits", Tag.TAG_COMPOUND);
            for (int i = 0; i < visitsList.size(); i++) {
                CompoundTag vt = visitsList.getCompound(i);
                this.recentVisits.add(new VisitMemory(
                        vt.getString("buildingTypeId"),
                        vt.getString("buildingDisplayName"),
                        vt.getString("category"),
                        vt.getLong("gameTime"),
                        vt.getInt("comfortDelta"),
                        vt.getInt("magicDelta"),
                        vt.getInt("wonderDelta"),
                        vt.getInt("energyDelta"),
                        vt.getString("whatHappened"),
                        Emotion.fromDelta(vt.getInt("comfortDelta") + vt.getInt("magicDelta") + vt.getInt("wonderDelta"))
                ));
            }
        }

        this.arrivalTime = tag.getLong("arrivalTime");

        // Reset transient state that should not survive reload
        this.forcedMoveMode = null;
        this.commuteArrived = false;
        // hotelCheckinTime is reset by onAddedToLevel re-checkin logic
    }

    // ──────────────────────── Attributes ────────────────────────

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MOVEMENT_SPEED, 0.5)
                .add(Attributes.FOLLOW_RANGE, 64.0)
                .add(Attributes.MAX_HEALTH, 20.0);
    }

    // ──────────────────────── State helpers ────────────────────────

    public void applyState(TouristState state) {
        if (this.currentState != state) {
            this.currentState = state;
            if (state == TouristState.SLEEPING) setPose(Pose.SLEEPING);
            else if (getPose() == Pose.SLEEPING) setPose(Pose.STANDING);
        }
    }

    /**
     * Force the TouristMoveGoal to switch to a specific MoveMode on the next tick.
     * Called by commands. Set to null to clear.
     *
     * <p>This is separate from {@link #applyState} because TouristState is normally
     * a one-way mirror of MoveMode — this method reverses the direction.
     */
    public void forceMoveMode(@javax.annotation.Nullable TouristState mode) {
        this.forcedMoveMode = mode;
        // Also update the display label immediately so the command response is consistent
        if (mode != null) {
            this.currentState = mode;
        }
    }

    @javax.annotation.Nullable
    public TouristState getForcedMoveMode() {
        return forcedMoveMode;
    }

    // ──────────────────────── Getters / Setters ────────────────────────

    /** Display name resolved to the current language (legacy literal names pass through). */
    public String getTouristName() {
        return com.wsteam.wandscape.shared.data.CharacterNames.localizedString(touristName);
    }

    /** Raw name key (or legacy literal) — used when copying between entity and shadow. */
    public String getTouristNameKey() { return touristName; }

    public void setTouristName(String name) { this.touristName = name; syncName(); }

    public TouristState getCurrentState() { return currentState; }

    @Nullable public BlockPos getCommuteTarget() { return commuteTarget; }
    public void setCommuteTarget(@Nullable BlockPos t) {
        this.commuteTarget = t;
        entityData.set(DEBUG_COMMUTE_TARGET, Optional.ofNullable(t));
    }

    public boolean isCommuteArrived() { return commuteArrived; }
    public void setCommuteArrived(boolean a) { this.commuteArrived = a; }

    @Nullable public BlockPos getWanderAnchor() { return wanderAnchor; }
    public void setWanderAnchor(@Nullable BlockPos a) { this.wanderAnchor = a; }

    public int getWanderRadius() { return wanderRadius; }
    public void setWanderRadius(int r) { this.wanderRadius = r; }

    public List<BlockPos> getPoiList() { return poiList; }
    public void setPoiList(List<BlockPos> pois) { this.poiList = List.copyOf(pois); }

    public int getEnergy() { return energy; }
    public void setEnergy(int e) { this.energy = Math.clamp(e, 0, WandscapeConstants.TOURIST_MAX_ENERGY); }

    // ── 三条需求条（fill/need）──

    @Override public int getComfortSat() { return comfortSat; }
    @Override public void setComfortSat(int v) { this.comfortSat = Math.clamp(v, 0, Math.max(0, comfortNeed)); }
    @Override public int getMagicSat() { return magicSat; }
    @Override public void setMagicSat(int v) { this.magicSat = Math.clamp(v, 0, Math.max(0, magicNeed)); }
    @Override public int getWonderSat() { return wonderSat; }
    @Override public void setWonderSat(int v) { this.wonderSat = Math.clamp(v, 0, Math.max(0, wonderNeed)); }
    @Override public int getComfortNeed() { return comfortNeed; }
    @Override public void setComfortNeed(int v) { this.comfortNeed = Math.max(1, v); }
    @Override public int getMagicNeed() { return magicNeed; }
    @Override public void setMagicNeed(int v) { this.magicNeed = Math.max(1, v); }
    @Override public int getWonderNeed() { return wonderNeed; }
    @Override public void setWonderNeed(int v) { this.wonderNeed = Math.max(1, v); }

    /** 满条 = 三条 ratio 全 1。 */
    @Override public boolean isFullySatisfied() {
        return comfortSat >= comfortNeed && magicSat >= magicNeed && wonderSat >= wonderNeed;
    }

    public boolean isMageResumeStored() { return mageResumeStored; }
    public void setMageResumeStored(boolean v) { this.mageResumeStored = v; }

    public int getLevel() { return level; }
    public void setLevel(int l) { this.level = Math.max(1, l); }

    public int getWallet() { return wallet; }
    public void setWallet(int w) { this.wallet = Math.max(0, w); }

    public int getInitialWallet() { return initialWallet; }
    public void setInitialWallet(int w) { this.initialWallet = Math.max(0, w); }

    /** Spend from the universal wallet; clamps at 0 if the amount exceeds the balance. */
    public void spendWallet(long amount) {
        this.wallet = (int) Math.max(0, (long) this.wallet - amount);
    }

    // ── 活动 / 停留 / 总旅费 ──

    @Nullable public Activity getCurrentActivity() {
        int o = entityData.get(DATA_ACTIVITY);
        Activity[] values = Activity.values();
        return o < 0 || o >= values.length ? null : values[o];
    }
    public void setCurrentActivity(@Nullable Activity a) {
        entityData.set(DATA_ACTIVITY, a == null ? -1 : a.ordinal());
    }
    public int getActivityTicks() { return activityTicks; }
    public void setActivityTicks(int t) { this.activityTicks = Math.max(0, t); }

    /** 预览假人（交互位演示）：不参与 AI/生成/离开，仅站桩循环做动作。 */
    public boolean isPreview() { return entityData.get(DATA_PREVIEW); }
    public void setPreview(boolean v) {
        entityData.set(DATA_PREVIEW, v);
        this.previewMode = v;
    }

    /** 锁定朝向（交互动作/预览时用）；null=解锁。 */
    @javax.annotation.Nullable
    public Float getFrozenYaw() { return frozenYaw; }
    public void setFrozenYaw(@javax.annotation.Nullable Float yaw) { this.frozenYaw = yaw; }

    /** 用餐时手持的食物 registry id（非法值回退面包）。 */
    public String getHeldFoodItem() { return heldFoodItem; }
    public void setHeldFoodItem(String id) {
        if (id != null && !id.isBlank()) this.heldFoodItem = id;
    }
    public int getOccupiedSpot() { return occupiedSpot; }
    public void setOccupiedSpot(int i) { this.occupiedSpot = i; }
    public int getNightsStayed() { return nightsStayed; }
    public void setNightsStayed(int n) { this.nightsStayed = Math.max(0, n); }
    public long getDepartureDeadline() { return departureDeadline; }
    public void setDepartureDeadline(long t) { this.departureDeadline = t; }

    /**
     * 兜底：刷怪蛋/旧存档游客可能没走过 {@code applySpawnDefaults}，停留字段仍是初始值
     * （arrivalTime=0、departureDeadline=Long.MAX_VALUE）——信息屏「共 X 天」会溢出成巨数，
     * 且永远不会按停留到点离场。给它们补一个 2~4 天的窗口。幂等：已设好窗口的游客不受影响。
     */
    public void ensureStayWindow(long gameTime) {
        long arrival = getArrivalTime();
        if (arrival <= 0L) {
            arrival = gameTime;
            setArrivalTime(arrival);
        }
        long deadline = getDepartureDeadline();
        if (deadline == Long.MAX_VALUE || deadline < arrival) {
            int stayMin = Config.TOURIST_STAY_MIN_DAYS.get();
            int stayMax = Config.TOURIST_STAY_MAX_DAYS.get();
            long stayTicks = (stayMin + random.nextInt(stayMax - stayMin + 1)) * 24000L;
            setDepartureDeadline(arrival + stayTicks);
        }
    }

    public int getTravelFund() { return travelFund; }
    public void setTravelFund(int v) { this.travelFund = Math.max(0, v); }

    public int getLastAtmWithdrawTime() { return lastAtmWithdrawTime; }
    public void setLastAtmWithdrawTime(int t) { this.lastAtmWithdrawTime = t; }

    /** 游客当前位置（视野过滤用）。 */
    @Override
    public BlockPos touristPos() { return blockPosition(); }

    // ── Mage-only ──

    public float getMaxHp() { return maxHp; }
    public float getMoveSpeed() { return moveSpeed; }
    public float getSpellPower() { return spellPower; }
    public float getWorkSpeed() { return workSpeed; }
    public float getSpellSpeed() { return spellSpeed; }
    public float getArmor() { return armorValue; }
    public float getMaxMana() { return maxMana; }

    public void setMageAttributes(float maxHp, float moveSpeed, float spellPower,
                                  float workSpeed, float spellSpeed, float armorValue,
                                  float maxMana) {
        this.maxHp = maxHp;
        this.moveSpeed = moveSpeed;
        this.spellPower = spellPower;
        this.workSpeed = workSpeed;
        this.spellSpeed = spellSpeed;
        this.armorValue = armorValue;
        this.maxMana = maxMana;
    }

    @Nullable public UUID getColonyId() { return colonyId; }
    public void setColonyId(@Nullable UUID id) { this.colonyId = id; }

    @Nullable public UUID getTargetBuildingId() { return targetBuildingId; }
    public void setTargetBuildingId(@Nullable UUID id) { this.targetBuildingId = id; }

    @Nullable public String getTargetBuildingCategory() { return targetBuildingCategory; }
    public void setTargetBuildingCategory(@Nullable String cat) { this.targetBuildingCategory = cat; }

    @Nullable public UUID getCheckedInBuildingId() { return checkedInBuildingId; }
    public void setCheckedInBuildingId(@Nullable UUID id) { this.checkedInBuildingId = id; }

    public int getHotelCheckinTime() { return hotelCheckinTime; }
    public void setHotelCheckinTime(int time) { this.hotelCheckinTime = time; }

    @Nullable public BlockPos getWakeUpPos() { return wakeUpPos; }
    public void setWakeUpPos(@Nullable BlockPos pos) { this.wakeUpPos = pos; }

    public Set<UUID> getVisitedBuildings() { return visitedBuildings; }
    public void addVisitedBuilding(UUID buildingId) {
        if (com.wsteam.wandscape.tourist.internal.TouristCooldownDebug.skipVisitedBuildings) return;
        visitedBuildings.add(buildingId);
    }
    public boolean hasVisitedBuilding(UUID buildingId) {
        if (com.wsteam.wandscape.tourist.internal.TouristCooldownDebug.skipVisitedBuildings) return false;
        return visitedBuildings.contains(buildingId);
    }

    // ── Narrative memory (journey diary) ──

    public void addVisitMemory(VisitMemory memory) {
        if (recentVisits.size() >= MAX_VISIT_MEMORIES) {
            recentVisits.remove(0); // Remove oldest
        }
        recentVisits.add(memory);
    }

    public List<VisitMemory> getRecentVisits() {
        return List.copyOf(recentVisits);
    }

    public long getArrivalTime() { return arrivalTime; }
    public void setArrivalTime(long t) { this.arrivalTime = t; }

    // ── Debug synced data (for TouristDebugRenderer) ──

    @Nullable
    public BlockPos getDebugCommuteTarget() {
        return entityData.get(DEBUG_COMMUTE_TARGET).orElse(null);
    }

    @Nullable
    public BlockPos getDebugEntryPoint() {
        return entityData.get(DEBUG_ENTRY_POINT).orElse(null);
    }

    public void setDebugEntryPoint(@Nullable BlockPos pos) {
        entityData.set(DEBUG_ENTRY_POINT, Optional.ofNullable(pos));
    }

    @Nullable
    public BlockPos getDebugInteractPoint() {
        return entityData.get(DEBUG_INTERACT_POINT).orElse(null);
    }

    public void setDebugInteractPoint(@Nullable BlockPos pos) {
        entityData.set(DEBUG_INTERACT_POINT, Optional.ofNullable(pos));
    }

    public boolean isDebugIndoorPhase() {
        return entityData.get(DEBUG_INDOOR_PHASE);
    }

    public void setDebugIndoorPhase(boolean indoor) {
        entityData.set(DEBUG_INDOOR_PHASE, indoor);
    }

    private void syncName() {
        setCustomName(com.wsteam.wandscape.shared.data.CharacterNames.displayComponent(touristName));
    }
}
