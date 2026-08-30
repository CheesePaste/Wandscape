package com.wsteam.wandscape.engine.colony;

import com.wsteam.wandscape.shared.log.Log;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists colony level and experience.
 *
 * <p>Each colony has a level (starting at 1) and accumulated experience.
 * Experience is gained when tourists depart with all three bars full.
 * Stored as world SavedData under "wandscape_colony_levels".
 */
public class ColonyLevelData extends SavedData {
    private static final String TAG = "ColonyLevelData";
    private static final String DATA_NAME = "wandscape_colony_levels";

    /** colonyId → level record */
    private final Map<UUID, Record> colonies = new ConcurrentHashMap<>();

    public record Record(UUID colonyId, int level, int experience, String name) {
        public String getName() { return name != null ? name : ""; }
    }

    private ColonyLevelData() {}

    public static ColonyLevelData getOrCreate(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new Factory<>(ColonyLevelData::new, ColonyLevelData::load), DATA_NAME);
    }

    /** Get the level for a colony (default 1). */
    public int getLevel(UUID colonyId) {
        Record r = colonies.get(colonyId);
        return r != null ? r.level : 1;
    }

    /** Get the experience for a colony (default 0). */
    public int getExperience(UUID colonyId) {
        Record r = colonies.get(colonyId);
        return r != null ? r.experience : 0;
    }

    /** Get the name for a colony (default formatted short UUID). */
    public String getName(UUID colonyId) {
        Record r = colonies.get(colonyId);
        if (r != null) {
            String n = r.name;
            if (n != null && !n.isEmpty()) return n;
        }
        return "小镇 " + colonyId.toString().substring(0, 8);
    }

    /** Set the level for a colony. */
    public void setLevel(UUID colonyId, int level) {
        colonies.put(colonyId, new Record(colonyId, level, getExperience(colonyId), getName(colonyId)));
        setDirty();
    }

    /** Set the experience for a colony. */
    public void setExperience(UUID colonyId, int experience) {
        colonies.put(colonyId, new Record(colonyId, getLevel(colonyId), experience, getName(colonyId)));
        setDirty();
    }

    /** Set the name for a colony. */
    public void setName(UUID colonyId, String name) {
        colonies.put(colonyId, new Record(colonyId, getLevel(colonyId), getExperience(colonyId), name));
        setDirty();
    }

    /** Add experience and return the new total. */
    public int addExperience(UUID colonyId, int amount) {
        int current = getExperience(colonyId);
        int updated = current + amount;
        colonies.put(colonyId, new Record(colonyId, getLevel(colonyId), updated, getName(colonyId)));
        setDirty();
        return updated;
    }

    /** Ensure a colony record exists (create if first time). */
    public Record ensure(UUID colonyId) {
        return colonies.computeIfAbsent(colonyId, id -> {
            setDirty();
            return new Record(id, 1, 0, null);
        });
    }

    // ── SavedData serialization ──

    private static final String TAG_COLONIES = "colonies";
    private static final String TAG_COLONY_ID = "colonyId";
    private static final String TAG_LEVEL = "level";
    private static final String TAG_EXPERIENCE = "experience";
    private static final String TAG_NAME = "name";

    static ColonyLevelData load(CompoundTag tag, HolderLookup.Provider provider) {
        ColonyLevelData data = new ColonyLevelData();
        ListTag colonies = tag.getList(TAG_COLONIES, Tag.TAG_COMPOUND);
        for (int i = 0; i < colonies.size(); i++) {
            CompoundTag ct = colonies.getCompound(i);
            UUID colonyId = ct.getUUID(TAG_COLONY_ID);
            int level = ct.getInt(TAG_LEVEL);
            int experience = ct.getInt(TAG_EXPERIENCE);
            String name = ct.contains(TAG_NAME) ? ct.getString(TAG_NAME) : null;
            data.colonies.put(colonyId, new Record(colonyId, Math.max(1, level), Math.max(0, experience), name));
        }
        Log.info(TAG, "Loaded {} colony level records", data.colonies.size());
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag colonies = new ListTag();
        for (Record r : this.colonies.values()) {
            CompoundTag ct = new CompoundTag();
            ct.putUUID(TAG_COLONY_ID, r.colonyId());
            ct.putInt(TAG_LEVEL, r.level());
            ct.putInt(TAG_EXPERIENCE, r.experience());
            if (r.name() != null && !r.name().isEmpty()) {
                ct.putString(TAG_NAME, r.name());
            }
            colonies.add(ct);
        }
        tag.put(TAG_COLONIES, colonies);
        return tag;
    }
}
