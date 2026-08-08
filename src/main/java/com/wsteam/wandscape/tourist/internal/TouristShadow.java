package com.wsteam.wandscape.tourist.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.wsteam.wandscape.shared.data.Emotion;
import com.wsteam.wandscape.shared.data.VisitMemory;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * Data-side mirror of a {@code TouristEntity} that survives chunk unload.
 *
 * <p>Every tourist has exactly one shadow. While its position chunk is loaded
 * the physical entity is the live representation and the shadow mirrors it
 * (synced every sim step); while unloaded the {@link TouristSimSystem} advances
 * the shadow — constant-speed straight-line movement (no terrain, no pathfinding),
 * building interactions, cooldowns and departure — using the same SavedData-driven
 * economy code as the real AI.
 *
 * <p>Cooldowns are stored relative to {@link #simTick} (which mirrors the
 * entity's {@code tickCount} semantics), so they can be converted to entity ticks
 * on spawn and back on snapshot without absolute-clock portability issues.
 */
public final class TouristShadow implements TouristStateHost {

    // ── Identity / appearance ──
    private UUID touristId;
    private String touristName;
    private int skinVariant;
    private boolean mage;
    private float maxHp;
    private float moveSpeed;
    private float spellPower;
    private float workSpeed;
    private float spellSpeed;
    private float armorValue;
    private float maxMana;
    private boolean mageResumeStored;

    // ── Sim clock (mirrors entity tickCount for cooldown semantics) ──
    private int simTick;

    // ── Position (double for smooth constant-speed movement) ──
    private double posX;
    private double posY;
    private double posZ;

    // ── Movement state ──
    @Nullable
    private BlockPos commuteTarget;
    @Nullable
    private UUID targetBuildingId;
    @Nullable
    private String targetBuildingCategory;

    // ── Attributes ──
    private int energy;
    private int satisfaction;
    private int level;
    private int wallet;
    private int initialWallet;
    private final Map<String, Integer> typePreferences = new HashMap<>();

    @Nullable
    private UUID colonyId;

    // ── Hotel ──
    @Nullable
    private UUID checkedInBuildingId;
    private int hotelCheckinTime;
    /** Position to return to on morning checkout (the pre-bed spot). */
    @Nullable
    private BlockPos wakeUpPos;

    // ── Visit / cooldown state ──
    private final Set<UUID> visitedBuildings = new HashSet<>();
    /** buildingId → simTick when the cooldown expires (0 = none). */
    private final Map<UUID, Integer> serviceCooldowns = new HashMap<>();
    private int serviceCooldownEndTick;
    private final List<VisitMemory> recentVisits = new ArrayList<>();
    private long arrivalTime;

    public TouristShadow() {
    }

    // ── Basic clock ──

    @Override
    public int timeBase() {
        return simTick;
    }

    /** Mirrors the entity's {@code tickCount}; used as the cooldown time base. */
    public int simTick() {
        return simTick;
    }

    public void advanceSimTick(int delta) {
        this.simTick += delta;
    }

    // ── Transient hydration flag (not persisted) ──
    // True while a live physical entity is driven by this shadow. Used to apply
    // shadow→entity once on the unloaded→loaded transition, then sync entity→shadow.

    private boolean hydrated;

    public boolean isHydrated() { return hydrated; }
    public void markHydrated() { this.hydrated = true; }
    public void markUnhydrated() { this.hydrated = false; }

    // ── Getters / setters ──

    public UUID getTouristId() { return touristId; }
    public void setTouristId(UUID id) { this.touristId = id; }

    /** Display name resolved to the current language (legacy literal names pass through). */
    public String getTouristName() {
        return com.wsteam.wandscape.shared.data.CharacterNames.localizedString(touristName);
    }

    /** Raw name key (or legacy literal) — used when copying between entity and shadow. */
    public String getTouristNameKey() { return touristName; }

    public void setTouristName(String n) { this.touristName = n; }

    public int getSkinVariant() { return skinVariant; }
    public void setSkinVariant(int v) { this.skinVariant = v; }

    public boolean isMage() { return mage; }
    public void setMage(boolean m) { this.mage = m; }

    public float getMaxHp() { return maxHp; }
    public void setMaxHp(float v) { this.maxHp = v; }
    public float getMoveSpeed() { return moveSpeed; }
    public void setMoveSpeed(float v) { this.moveSpeed = v; }
    public float getSpellPower() { return spellPower; }
    public void setSpellPower(float v) { this.spellPower = v; }
    public float getWorkSpeed() { return workSpeed; }
    public void setWorkSpeed(float v) { this.workSpeed = v; }
    public float getSpellSpeed() { return spellSpeed; }
    public void setSpellSpeed(float v) { this.spellSpeed = v; }
    public float getArmor() { return armorValue; }
    public void setArmor(float v) { this.armorValue = v; }
    public float getMaxMana() { return maxMana; }
    public void setMaxMana(float v) { this.maxMana = v; }
    public boolean isMageResumeStored() { return mageResumeStored; }
    public void setMageResumeStored(boolean v) { this.mageResumeStored = v; }

    public double getPosX() { return posX; }
    public double getPosY() { return posY; }
    public double getPosZ() { return posZ; }
    public void setPosition(double x, double y, double z) { this.posX = x; this.posY = y; this.posZ = z; }

    @Nullable public BlockPos getCommuteTarget() { return commuteTarget; }
    public void setCommuteTarget(@Nullable BlockPos t) { this.commuteTarget = t; }

    @Nullable public UUID getTargetBuildingId() { return targetBuildingId; }
    public void setTargetBuildingId(@Nullable UUID id) { this.targetBuildingId = id; }

    @Nullable public String getTargetBuildingCategory() { return targetBuildingCategory; }
    public void setTargetBuildingCategory(@Nullable String c) { this.targetBuildingCategory = c; }

    public int getEnergy() { return energy; }
    public void setEnergy(int e) { this.energy = Math.clamp(e, 0, 200); }
    public int getSatisfaction() { return satisfaction; }
    public void setSatisfaction(int s) { this.satisfaction = Math.clamp(s, 0, 100); }
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

    public Map<String, Integer> getTypePreferences() { return typePreferences; }

    public int getTypePreference(String buildingTypeId) {
        return typePreferences.getOrDefault(buildingTypeId, 40);
    }

    /** Adjust preference for a building type by delta (clamped to 5–100). */
    public void adjustTypePreference(String buildingTypeId, int delta) {
        int current = getTypePreference(buildingTypeId);
        int next = Math.clamp(current + delta, 5, 100);
        if (next == 40) {
            typePreferences.remove(buildingTypeId);
        } else {
            typePreferences.put(buildingTypeId, next);
        }
    }

    @Nullable public UUID getColonyId() { return colonyId; }
    public void setColonyId(@Nullable UUID id) { this.colonyId = id; }

    @Nullable public UUID getCheckedInBuildingId() { return checkedInBuildingId; }
    public void setCheckedInBuildingId(@Nullable UUID id) { this.checkedInBuildingId = id; }
    public int getHotelCheckinTime() { return hotelCheckinTime; }
    public void setHotelCheckinTime(int t) { this.hotelCheckinTime = t; }
    @Nullable public BlockPos getWakeUpPos() { return wakeUpPos; }
    public void setWakeUpPos(@Nullable BlockPos pos) { this.wakeUpPos = pos; }

    public Set<UUID> getVisitedBuildings() { return visitedBuildings; }
    public boolean hasVisitedBuilding(UUID id) { return visitedBuildings.contains(id); }
    public void addVisitedBuilding(UUID id) { visitedBuildings.add(id); }

    public int getServiceCooldown(UUID buildingId) {
        return serviceCooldowns.getOrDefault(buildingId, 0);
    }

    public void setServiceCooldown(UUID buildingId, int endSimTick) {
        serviceCooldowns.put(buildingId, endSimTick);
    }

    public Map<UUID, Integer> getServiceCooldownsMap() { return serviceCooldowns; }

    public int getServiceCooldownEndTick() { return serviceCooldownEndTick; }
    public void setServiceCooldownEndTick(int endSimTick) { this.serviceCooldownEndTick = endSimTick; }

    public void addVisitMemory(VisitMemory memory) {
        if (recentVisits.size() >= 24) {
            recentVisits.remove(0);
        }
        recentVisits.add(memory);
    }

    public void clearRecentVisits() { recentVisits.clear(); }

    public List<VisitMemory> getRecentVisits() { return List.copyOf(recentVisits); }

    public long getArrivalTime() { return arrivalTime; }
    public void setArrivalTime(long t) { this.arrivalTime = t; }

    // ── NBT ──

    public CompoundTag save(CompoundTag tag) {
        tag.putUUID("id", touristId);
        tag.putString("name", touristName);
        tag.putInt("skin", skinVariant);
        tag.putBoolean("mage", mage);
        tag.putFloat("maxHp", maxHp);
        tag.putFloat("moveSpeed", moveSpeed);
        tag.putFloat("spellPower", spellPower);
        tag.putFloat("workSpeed", workSpeed);
        tag.putFloat("spellSpeed", spellSpeed);
        tag.putFloat("armorValue", armorValue);
        tag.putFloat("maxMana", maxMana);
        tag.putBoolean("mageResume", mageResumeStored);
        tag.putInt("simTick", simTick);
        tag.putDouble("x", posX);
        tag.putDouble("y", posY);
        tag.putDouble("z", posZ);
        if (commuteTarget != null) tag.putLong("commute", commuteTarget.asLong());
        if (targetBuildingId != null) tag.putUUID("target", targetBuildingId);
        if (targetBuildingCategory != null) tag.putString("targetCat", targetBuildingCategory);
        tag.putInt("energy", energy);
        tag.putInt("satisfaction", satisfaction);
        tag.putInt("level", level);
        tag.putInt("wallet", wallet);
        tag.putInt("initialWallet", initialWallet);

        CompoundTag prefs = new CompoundTag();
        for (var e : typePreferences.entrySet()) prefs.putInt(e.getKey(), e.getValue());
        tag.put("prefs", prefs);

        if (colonyId != null) tag.putUUID("colony", colonyId);
        if (checkedInBuildingId != null) tag.putUUID("hotel", checkedInBuildingId);
        tag.putInt("hotelCheckin", hotelCheckinTime);
        if (wakeUpPos != null) tag.putLong("wakeUpPos", wakeUpPos.asLong());

        ListTag visited = new ListTag();
        for (UUID id : visitedBuildings) {
            CompoundTag e = new CompoundTag();
            e.putUUID("id", id);
            visited.add(e);
        }
        tag.put("visited", visited);

        ListTag cooldowns = new ListTag();
        for (var e : serviceCooldowns.entrySet()) {
            if (e.getValue() > simTick) {
                CompoundTag c = new CompoundTag();
                c.putUUID("buildingId", e.getKey());
                c.putInt("end", e.getValue());
                cooldowns.add(c);
            }
        }
        tag.put("cooldowns", cooldowns);
        if (serviceCooldownEndTick > simTick) tag.putInt("cooldownEnd", serviceCooldownEndTick);

        ListTag visits = new ListTag();
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
            visits.add(vt);
        }
        tag.put("visits", visits);

        tag.putLong("arrival", arrivalTime);
        return tag;
    }

    public static TouristShadow load(CompoundTag tag) {
        TouristShadow s = new TouristShadow();
        s.touristId = tag.getUUID("id");
        s.touristName = tag.getString("name");
        s.skinVariant = tag.getInt("skin");
        s.mage = tag.getBoolean("mage");
        s.maxHp = tag.getFloat("maxHp");
        s.moveSpeed = tag.getFloat("moveSpeed");
        s.spellPower = tag.getFloat("spellPower");
        s.workSpeed = tag.getFloat("workSpeed");
        s.spellSpeed = tag.getFloat("spellSpeed");
        s.armorValue = tag.getFloat("armorValue");
        s.maxMana = tag.getFloat("maxMana");
        s.mageResumeStored = tag.getBoolean("mageResume");
        s.simTick = tag.getInt("simTick");
        s.posX = tag.getDouble("x");
        s.posY = tag.getDouble("y");
        s.posZ = tag.getDouble("z");
        s.commuteTarget = tag.contains("commute") ? BlockPos.of(tag.getLong("commute")) : null;
        s.targetBuildingId = tag.hasUUID("target") ? tag.getUUID("target") : null;
        s.targetBuildingCategory = tag.contains("targetCat") ? tag.getString("targetCat") : null;
        s.energy = Math.clamp(tag.getInt("energy"), 0, 200);
        s.satisfaction = Math.clamp(tag.getInt("satisfaction"), 0, 100);
        s.level = Math.max(1, tag.getInt("level"));
        s.wallet = Math.max(0, tag.getInt("wallet"));
        s.initialWallet = Math.max(0, tag.getInt("initialWallet"));

        if (tag.contains("prefs")) {
            CompoundTag prefs = tag.getCompound("prefs");
            for (String key : prefs.getAllKeys()) s.typePreferences.put(key, prefs.getInt(key));
        }

        s.colonyId = tag.hasUUID("colony") ? tag.getUUID("colony") : null;
        s.checkedInBuildingId = tag.hasUUID("hotel") ? tag.getUUID("hotel") : null;
        s.hotelCheckinTime = tag.getInt("hotelCheckin");
        s.wakeUpPos = tag.contains("wakeUpPos") ? BlockPos.of(tag.getLong("wakeUpPos")) : null;

        if (tag.contains("visited", Tag.TAG_LIST)) {
            ListTag visited = tag.getList("visited", Tag.TAG_COMPOUND);
            for (int i = 0; i < visited.size(); i++) {
                CompoundTag e = visited.getCompound(i);
                if (e.hasUUID("id")) s.visitedBuildings.add(e.getUUID("id"));
            }
        }

        if (tag.contains("cooldowns", Tag.TAG_LIST)) {
            ListTag cooldowns = tag.getList("cooldowns", Tag.TAG_COMPOUND);
            for (int i = 0; i < cooldowns.size(); i++) {
                CompoundTag c = cooldowns.getCompound(i);
                if (c.hasUUID("buildingId")) s.serviceCooldowns.put(c.getUUID("buildingId"), c.getInt("end"));
            }
        }
        s.serviceCooldownEndTick = tag.contains("cooldownEnd") ? tag.getInt("cooldownEnd") : 0;

        if (tag.contains("visits", Tag.TAG_LIST)) {
            ListTag visits = tag.getList("visits", Tag.TAG_COMPOUND);
            for (int i = 0; i < visits.size(); i++) {
                CompoundTag vt = visits.getCompound(i);
                int delta = vt.getInt("satisfactionDelta");
                s.recentVisits.add(new VisitMemory(
                        vt.getString("buildingTypeId"),
                        vt.getString("buildingDisplayName"),
                        vt.getString("category"),
                        vt.getLong("gameTime"),
                        vt.getInt("satisfactionBefore"),
                        delta,
                        vt.getInt("energyDelta"),
                        vt.getString("whatHappened"),
                        Emotion.fromDelta(delta)));
            }
        }

        s.arrivalTime = tag.getLong("arrival");
        return s;
    }
}
