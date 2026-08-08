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
import com.wsteam.wandscape.shared.data.Emotion;
import com.wsteam.wandscape.shared.data.MageAttributeRoller;
import com.wsteam.wandscape.shared.data.RecruitmentCandidate;
import com.wsteam.wandscape.shared.data.VisitMemory;
import com.wsteam.wandscape.shared.entity.VillagerLike;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import com.wsteam.wandscape.tourist.internal.HotelStayHandler;
import com.wsteam.wandscape.tourist.internal.TouristSpawnSystem;
import com.wsteam.wandscape.tourist.internal.TouristSimSystem;
import com.wsteam.wandscape.tourist.internal.TouristStateHost;

import javax.annotation.Nullable;

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
 * Mage tourists carry mana/spell-power stats; when their satisfaction reaches 100%,
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

    private int energy = 100;
    private int satisfaction;
    private int level = 1;
    /** Universal-element spending money. Higher tourist levels start with more. */
    private int wallet;
    /** The wallet the tourist arrived with — caps each shopping trip's budget. */
    private int initialWallet;

    // ── Per-building-type preference (buildingTypeId → 5..100, default 50) ──

    private final Map<String, Integer> typePreferences = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int DEFAULT_TYPE_PREFERENCE = 40;
    private static final int MIN_TYPE_PREFERENCE = 5;
    private static final int MAX_TYPE_PREFERENCE = 100;

    // ── Mage-only attributes (stored in tavern recruitment resume at 100% satisfaction) ──

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

    /** Set of building IDs the tourist has already visited this trip. */
    private final Set<UUID> visitedBuildings = new HashSet<>();

    /** Per-building cooldown end ticks (buildingId → game tick when cooldown expires). */
    private final Map<UUID, Integer> serviceCooldowns = new java.util.concurrent.ConcurrentHashMap<>();

    /** Global rest-cooldown end tick after interacting with any building (shop/service).
     *  During this, the tourist wanders or visits POIs and skips building visits. */
    private int serviceCooldownEndTick;

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
            // Freshly-created tourists (spawn egg) have no sim shadow yet — adopt
            // them now, else the sim's orphan sweep discards them as departed
            // bodies. Disk-loaded bodies (loadedFromDisk) are left for that sweep.
            if (!loadedFromDisk) {
                TouristSimSystem sim = TouristSimSystem.getActive();
                if (sim != null && sim.getRegistry() != null && sim.getRegistry().get(getUUID()) == null) {
                    sim.adoptTourist(this);
                }
            }
        }

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
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        return new WandscapeNavigation(this, level);
    }

    @Override
    public boolean shouldBeSaved() { return true; }

    @Override
    public boolean removeWhenFarAway(double d) { return false; }

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
        if (reason == RemovalReason.KILLED || reason == RemovalReason.DISCARDED) {
            onTouristKilled();
        }
    }

    private void onTouristKilled() {
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
                api.registerDeparture(getUUID(), colonyId, getSatisfaction());
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
        tag.putInt("satisfaction", satisfaction);
        tag.putInt("level", level);
        tag.putInt("wallet", wallet);
        tag.putInt("initialWallet", initialWallet);

        // Save per-building-type preferences as a flat compound
        CompoundTag prefs = new CompoundTag();
        for (var entry : typePreferences.entrySet()) {
            prefs.putInt(entry.getKey(), entry.getValue());
        }
        tag.put("typePreferences", prefs);

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

        // Save visited building IDs
        ListTag visitedList = new ListTag();
        for (UUID id : visitedBuildings) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("id", id);
            visitedList.add(entry);
        }
        tag.put("visitedBuildings", visitedList);

        // Save service cooldowns as remaining ticks (absolute tickCount values are not portable)
        ListTag cooldownList = new ListTag();
        for (var entry : serviceCooldowns.entrySet()) {
            int remaining = entry.getValue() - this.tickCount;
            if (remaining > 0) {
                CompoundTag entryTag = new CompoundTag();
                entryTag.putUUID("buildingId", entry.getKey());
                entryTag.putInt("remaining", remaining);
                cooldownList.add(entryTag);
            }
        }
        tag.put("serviceCooldowns", cooldownList);
        int remainingGlobal = serviceCooldownEndTick - this.tickCount;
        if (remainingGlobal > 0) {
            tag.putInt("serviceCooldownRemaining", remainingGlobal);
        }

        // Save visit memories (journey diary)
        ListTag visitsList = new ListTag();
        for (VisitMemory v : recentVisits) {
            CompoundTag vt = new CompoundTag();
            vt.putString("buildingTypeId", v.buildingTypeId());
            vt.putString("buildingDisplayName", v.buildingDisplayName());
            vt.putString("category", v.category());
            vt.putLong("gameTime", v.gameTime());
            vt.putInt("satisfactionBefore", v.satisfactionBefore());
            vt.putInt("satisfactionDelta", v.satisfactionDelta());
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
        this.energy = Math.clamp(tag.getInt("energy"), 0, 200);
        this.satisfaction = Math.clamp(tag.getInt("satisfaction"), 0, 100);
        this.level = Math.max(1, tag.getInt("level"));
        this.wallet = Math.max(0, tag.getInt("wallet"));
        this.initialWallet = Math.max(0, tag.getInt("initialWallet"));

        // Restore per-building-type preferences
        this.typePreferences.clear();
        if (tag.contains("typePreferences")) {
            CompoundTag prefs = tag.getCompound("typePreferences");
            for (String key : prefs.getAllKeys()) {
                this.typePreferences.put(key, prefs.getInt(key));
            }
        }

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

        // Restore service cooldowns from remaining ticks (reconstruct absolute endTick for current session)
        this.serviceCooldowns.clear();
        if (tag.contains("serviceCooldowns")) {
            ListTag cooldownList = tag.getList("serviceCooldowns", Tag.TAG_COMPOUND);
            for (int i = 0; i < cooldownList.size(); i++) {
                CompoundTag entry = cooldownList.getCompound(i);
                if (entry.hasUUID("buildingId")) {
                    int remaining = entry.getInt("remaining");
                    if (remaining > 0) {
                        this.serviceCooldowns.put(entry.getUUID("buildingId"), this.tickCount + remaining);
                    }
                }
            }
        }
        this.serviceCooldownEndTick = tag.contains("serviceCooldownRemaining")
                ? this.tickCount + tag.getInt("serviceCooldownRemaining")
                : 0;

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
                        vt.getInt("satisfactionBefore"),
                        vt.getInt("satisfactionDelta"),
                        vt.getInt("energyDelta"),
                        vt.getString("whatHappened"),
                        Emotion.fromDelta(vt.getInt("satisfactionDelta"))
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
    public void setEnergy(int e) { this.energy = Math.clamp(e, 0, 200); }

    public int getSatisfaction() { return satisfaction; }
    public void setSatisfaction(int s) { this.satisfaction = Math.clamp(s, 0, 100); }

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

    // ── Preferences ──

    /** Returns this tourist's preference for a building type (5–100, default 50). */
    public int getTypePreference(String buildingTypeId) {
        return typePreferences.getOrDefault(buildingTypeId, DEFAULT_TYPE_PREFERENCE);
    }

    /** Adjust preference for a building type by delta (clamped to 5–100). */
    public void adjustTypePreference(String buildingTypeId, int delta) {
        int current = getTypePreference(buildingTypeId);
        int next = Math.clamp(current + delta, MIN_TYPE_PREFERENCE, MAX_TYPE_PREFERENCE);
        if (next == DEFAULT_TYPE_PREFERENCE) {
            typePreferences.remove(buildingTypeId); // keep map small
        } else {
            typePreferences.put(buildingTypeId, next);
        }
    }

    /** Mutable type-preference map (for shadow sync). */
    public Map<String, Integer> getTypePreferencesMap() { return typePreferences; }

    // ── Mage-only ──

    public float getMaxHp() { return maxHp; }
    public float getMoveSpeed() { return moveSpeed; }
    public float getSpellPower() { return spellPower; }
    public float getWorkSpeed() { return workSpeed; }
    public float getSpellSpeed() { return spellSpeed; }
    public float getArmor() { return armorValue; }
    public float getMaxMana() { return maxMana; }

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

    public Set<UUID> getVisitedBuildings() { return visitedBuildings; }
    public void addVisitedBuilding(UUID buildingId) {
        if (com.wsteam.wandscape.tourist.internal.TouristCooldownDebug.skipVisitedBuildings) return;
        visitedBuildings.add(buildingId);
    }
    public boolean hasVisitedBuilding(UUID buildingId) {
        if (com.wsteam.wandscape.tourist.internal.TouristCooldownDebug.skipVisitedBuildings) return false;
        return visitedBuildings.contains(buildingId);
    }

    // ── Service cooldown ──

    /** Returns the tick when the cooldown for a specific service building expires, or 0. */
    public int getServiceCooldown(UUID buildingId) {
        if (com.wsteam.wandscape.tourist.internal.TouristCooldownDebug.skipServiceCooldown) return 0;
        return serviceCooldowns.getOrDefault(buildingId, 0);
    }

    /** Set a cooldown for a specific service building until the given tick. */
    public void setServiceCooldown(UUID buildingId, int endTick) {
        if (com.wsteam.wandscape.tourist.internal.TouristCooldownDebug.skipServiceCooldown) return;
        serviceCooldowns.put(buildingId, endTick);
    }

    /** Returns the global service cooldown end tick (0 = no cooldown). */
    public int getServiceCooldownEndTick() {
        if (com.wsteam.wandscape.tourist.internal.TouristCooldownDebug.skipServiceCooldown) return 0;
        return serviceCooldownEndTick;
    }

    /** Set the global service cooldown end tick. */
    public void setServiceCooldownEndTick(int endTick) {
        if (com.wsteam.wandscape.tourist.internal.TouristCooldownDebug.skipServiceCooldown) return;
        this.serviceCooldownEndTick = endTick;
    }

    /** Mutable service-cooldown map (for shadow sync). */
    public Map<UUID, Integer> getServiceCooldownsMap() { return serviceCooldowns; }

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
