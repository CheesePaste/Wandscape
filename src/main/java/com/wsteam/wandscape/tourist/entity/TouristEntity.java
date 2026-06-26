package com.wsteam.wandscape.tourist.entity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import com.wsteam.wandscape.Wandscape;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

/**
 * A tourist NPC that visits the colony to interact with shops and service buildings.
 *
 * <p>Extends {@link PathfinderMob} to use player-model rendering with custom skins.
 * Short-term visitors spawned by {@code TouristSpawnSystem}; movement driven by
 * {@code TouristMoveGoal}.
 *
 * <p>95% civilian appearance (skins from {@code textures/entity/citizen}),
 * 5% mage appearance (skins from {@code textures/entity/wizard}).
 * Mage tourists carry mana/spell-power stats; when their satisfaction reaches 100%,
 * their data is stored in the tavern as a recruitment resume.
 */
public class TouristEntity extends PathfinderMob {

    private static final Logger LOGGER = LogUtils.getLogger();

    // ── Appearance ──

    public enum Appearance {
        CIVILIAN,
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

    public static final int CIVILIAN_SKIN_COUNT = detectSkinCount("textures/entity/citizen");
    public static final int WIZARD_SKIN_COUNT   = detectSkinCount("textures/entity/wizard");

    // ── Synched data keys ──

    /** Skin variant — index within the appearance-specific pool. */
    private static final EntityDataAccessor<Integer> DATA_SKIN_VARIANT =
            SynchedEntityData.defineId(TouristEntity.class, EntityDataSerializers.INT);
    /** 0 = CIVILIAN, 1 = MAGE. */
    private static final EntityDataAccessor<Byte> DATA_APPEARANCE =
            SynchedEntityData.defineId(TouristEntity.class, EntityDataSerializers.BYTE);

    // ── Identity ──

    private String touristName = "";

    // ── Movement ──

    @Nullable
    private BlockPos commuteTarget;

    // ── Tourist attributes ──

    private int energy = 100;
    private int satisfaction;
    private int level = 1;

    // ── Per-building-type preference (buildingTypeId → 5..100, default 40) ──

    private final Map<String, Integer> typePreferences = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int DEFAULT_TYPE_PREFERENCE = 40;
    private static final int MIN_TYPE_PREFERENCE = 5;
    private static final int MAX_TYPE_PREFERENCE = 100;

    // ── Mage-only attributes (stored in tavern recruitment resume at 100% satisfaction) ──

    private int maxMana = 100;
    private int manaRegenRate = 2;
    private int spellPower = 1;

    /** Whether the mage resume has already been stored in the tavern for this tourist. */
    private boolean mageResumeStored;

    @Nullable
    private UUID colonyId;

    /** True when this entity is a short-term tourist (not a long-term resident). */
    private boolean touristMode;

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

    /** Per-service-building cooldown end ticks (buildingId → game tick when cooldown expires). */
    private final Map<UUID, Integer> serviceCooldowns = new java.util.concurrent.ConcurrentHashMap<>();

    /** Global cooldown end tick after using any service building. */
    private int serviceCooldownEndTick;

    public TouristEntity(EntityType<? extends PathfinderMob> entityType, Level level) {
        super(entityType, level);
        setInvulnerable(true);
        setCustomNameVisible(true);
    }

    // ──────────────────────── Synched data ────────────────────────

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_SKIN_VARIANT, -1);
        builder.define(DATA_APPEARANCE, (byte) 0);
    }

    public int getSkinVariant() {
        return entityData.get(DATA_SKIN_VARIANT);
    }

    public Appearance getAppearance() {
        return entityData.get(DATA_APPEARANCE) == 1 ? Appearance.MAGE : Appearance.CIVILIAN;
    }

    public boolean isMage() {
        return getAppearance() == Appearance.MAGE;
    }

    // ──────────────────────── AI goals ────────────────────────

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new com.wsteam.wandscape.tourist.internal.TouristMoveGoal(this, 0.5));
        this.goalSelector.addGoal(3, new RandomLookAroundGoal(this));
    }

    // ──────────────────────── Interaction ────────────────────────

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!this.isAlive()) return super.mobInteract(player, hand);

        if (!level().isClientSide) {
            String appearance = isMage() ? "法师" : "市民";
            StringBuilder sb = new StringBuilder();
            sb.append(touristName).append(" - ").append(appearance)
                    .append(" - Lv.").append(level)
                    .append(" - 精力 ").append(energy)
                    .append(" - 满意 ").append(satisfaction).append("%");
            if (isMage()) {
                sb.append(" - 魔力 ").append(maxMana)
                        .append(" - 法术 ").append(spellPower);
            }
            player.sendSystemMessage(Component.literal(sb.toString()));
        }
        return InteractionResult.sidedSuccess(level().isClientSide);
    }

    // ──────────────────────── Lifecycle ────────────────────────

    @Override
    public void onAddedToLevel() {
        super.onAddedToLevel();
        setInvulnerable(true);
        syncName();

        if (getSkinVariant() < 0) {
            boolean mage = random.nextDouble() < MAGE_CHANCE;
            int variant;

            if (mage) {
                variant = random.nextInt(WIZARD_SKIN_COUNT);
                maxMana = 80 + random.nextInt(121);       // 80–200
                manaRegenRate = 1 + random.nextInt(5);    // 1–5
                spellPower = 1 + random.nextInt(4);        // 1–4
                level = 1 + random.nextInt(5);             // 1–5
            } else {
                variant = random.nextInt(CIVILIAN_SKIN_COUNT);
            }

            entityData.set(DATA_SKIN_VARIANT, variant);
            entityData.set(DATA_APPEARANCE, (byte) (mage ? 1 : 0));
        }
    }

    @Override
    public boolean shouldBeSaved() { return false; }

    @Override
    public boolean removeWhenFarAway(double d) { return false; }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {}

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {}

    // ──────────────────────── Attributes ────────────────────────

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MOVEMENT_SPEED, 0.5)
                .add(Attributes.FOLLOW_RANGE, 48.0)
                .add(Attributes.MAX_HEALTH, 20.0);
    }

    // ──────────────────────── Getters / Setters ────────────────────────

    public String getTouristName() { return touristName; }
    public void setTouristName(String name) { this.touristName = name; syncName(); }

    @Nullable public BlockPos getCommuteTarget() { return commuteTarget; }
    public void setCommuteTarget(@Nullable BlockPos t) { this.commuteTarget = t; }

    public int getEnergy() { return energy; }
    public void setEnergy(int e) { this.energy = Math.clamp(e, 0, 200); }

    public int getSatisfaction() { return satisfaction; }
    public void setSatisfaction(int s) { this.satisfaction = Math.clamp(s, 0, 100); }

    public boolean isMageResumeStored() { return mageResumeStored; }
    public void setMageResumeStored(boolean v) { this.mageResumeStored = v; }

    public int getLevel() { return level; }
    public void setLevel(int l) { this.level = Math.max(1, l); }

    // ── Preferences ──

    public int getTypePreference(String buildingTypeId) {
        return typePreferences.getOrDefault(buildingTypeId, DEFAULT_TYPE_PREFERENCE);
    }

    public void adjustTypePreference(String buildingTypeId, int delta) {
        int current = getTypePreference(buildingTypeId);
        int next = Math.clamp(current + delta, MIN_TYPE_PREFERENCE, MAX_TYPE_PREFERENCE);
        if (next == DEFAULT_TYPE_PREFERENCE) {
            typePreferences.remove(buildingTypeId);
        } else {
            typePreferences.put(buildingTypeId, next);
        }
    }

    // ── Mage-only ──

    public int getMaxMana() { return maxMana; }
    public int getManaRegenRate() { return manaRegenRate; }
    public int getSpellPower() { return spellPower; }

    @Nullable public UUID getColonyId() { return colonyId; }
    public void setColonyId(@Nullable UUID id) { this.colonyId = id; }

    public boolean isTouristMode() { return touristMode; }
    public void setTouristMode(boolean mode) { this.touristMode = mode; }

    @Nullable public UUID getTargetBuildingId() { return targetBuildingId; }
    public void setTargetBuildingId(@Nullable UUID id) { this.targetBuildingId = id; }

    @Nullable public String getTargetBuildingCategory() { return targetBuildingCategory; }
    public void setTargetBuildingCategory(@Nullable String cat) { this.targetBuildingCategory = cat; }

    @Nullable public UUID getCheckedInBuildingId() { return checkedInBuildingId; }
    public void setCheckedInBuildingId(@Nullable UUID id) { this.checkedInBuildingId = id; }

    public int getHotelCheckinTime() { return hotelCheckinTime; }
    public void setHotelCheckinTime(int time) { this.hotelCheckinTime = time; }

    public Set<UUID> getVisitedBuildings() { return visitedBuildings; }
    public void addVisitedBuilding(UUID buildingId) { visitedBuildings.add(buildingId); }
    public boolean hasVisitedBuilding(UUID buildingId) { return visitedBuildings.contains(buildingId); }

    // ── Service cooldown ──

    public int getServiceCooldown(UUID buildingId) {
        return serviceCooldowns.getOrDefault(buildingId, 0);
    }

    public void setServiceCooldown(UUID buildingId, int endTick) {
        serviceCooldowns.put(buildingId, endTick);
    }

    public int getServiceCooldownEndTick() {
        return serviceCooldownEndTick;
    }

    public void setServiceCooldownEndTick(int endTick) {
        this.serviceCooldownEndTick = endTick;
    }

    private void syncName() { setCustomName(Component.literal(touristName)); }
}
