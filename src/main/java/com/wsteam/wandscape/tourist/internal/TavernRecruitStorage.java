package com.wsteam.wandscape.tourist.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.wsteam.wandscape.shared.data.MageResume;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Persists mage tourist resumes that reached 100% satisfaction.
 * Max 5 entries per colony, oldest evicted on overflow.
 *
 * <p>Stored as world SavedData under "wandscape_tavern_recruits".
 */
public class TavernRecruitStorage extends SavedData {
    private static final String TAG = "TavernRecruitStorage";
    private static final String DATA_NAME = "wandscape_tavern_recruits";
    private static final int MAX_PER_COLONY = 5;

    private final Map<UUID, List<MageResume>> colonyResumes = new ConcurrentHashMap<>();

    private TavernRecruitStorage() {}

    public static TavernRecruitStorage getOrCreate(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new Factory<>(TavernRecruitStorage::new, TavernRecruitStorage::load), DATA_NAME);
    }

    /** Add a mage resume to the colony's recruitment list. Evicts oldest if over capacity. */
    public void addResume(UUID colonyId, MageResume resume) {
        List<MageResume> list = colonyResumes.computeIfAbsent(colonyId, k -> new ArrayList<>());
        list.add(resume);
        while (list.size() > MAX_PER_COLONY) {
            MageResume removed = list.remove(0);
        }
        setDirty();
        Log.info(TAG, "[Tourist] Mage resume stored for colony {}: {} (Lv.{})",
                shortId(colonyId), resume.touristName(), resume.level());
    }

    /** Returns the list of mage resumes for a colony (newest last). */
    public List<MageResume> getResumes(UUID colonyId) {
        List<MageResume> list = colonyResumes.get(colonyId);
        return list != null ? List.copyOf(list) : List.of();
    }

    /** Remove a resume by index. Returns true if removed. */
    public boolean removeResume(UUID colonyId, int index) {
        List<MageResume> list = colonyResumes.get(colonyId);
        if (list == null || index < 0 || index >= list.size()) return false;
        list.remove(index);
        if (list.isEmpty()) colonyResumes.remove(colonyId);
        setDirty();
        return true;
    }

    /** Remove and return the resume at the given index, or null. */
    @Nullable
    public MageResume takeResume(UUID colonyId, int index) {
        List<MageResume> list = colonyResumes.get(colonyId);
        if (list == null || index < 0 || index >= list.size()) return null;
        MageResume resume = list.remove(index);
        if (list.isEmpty()) colonyResumes.remove(colonyId);
        setDirty();
        return resume;
    }

    // ── SavedData serialization ──

    private static final String TAG_COLONIES = "colonies";
    private static final String TAG_COLONY_ID = "colonyId";
    private static final String TAG_RESUMES = "resumes";
    private static final String TAG_NAME = "name";
    private static final String TAG_LEVEL = "level";
    private static final String TAG_MAX_HP = "maxHp";
    private static final String TAG_MOVE_SPEED = "moveSpeed";
    private static final String TAG_SPELL_POWER = "spellPower";
    private static final String TAG_WORK_SPEED = "workSpeed";
    private static final String TAG_SPELL_SPEED = "spellSpeed";
    private static final String TAG_ARMOR_VALUE = "armorValue";
    private static final String TAG_SKIN_VARIANT = "skinVariant";
    private static final String TAG_TIMESTAMP = "timestamp";

    static TavernRecruitStorage load(CompoundTag tag, HolderLookup.Provider provider) {
        TavernRecruitStorage storage = new TavernRecruitStorage();
        ListTag colonies = tag.getList(TAG_COLONIES, Tag.TAG_COMPOUND);
        for (int i = 0; i < colonies.size(); i++) {
            CompoundTag colonyTag = colonies.getCompound(i);
            UUID colonyId = colonyTag.getUUID(TAG_COLONY_ID);
            ListTag resumesTag = colonyTag.getList(TAG_RESUMES, Tag.TAG_COMPOUND);
            List<MageResume> resumes = new ArrayList<>();
            for (int j = 0; j < resumesTag.size(); j++) {
                CompoundTag rt = resumesTag.getCompound(j);
                resumes.add(new MageResume(
                        rt.getString(TAG_NAME),
                        rt.getInt(TAG_LEVEL),
                        rt.getFloat(TAG_MAX_HP),
                        rt.getFloat(TAG_MOVE_SPEED),
                        rt.getFloat(TAG_SPELL_POWER),
                        rt.getFloat(TAG_WORK_SPEED),
                        rt.getFloat(TAG_SPELL_SPEED),
                        rt.getFloat(TAG_ARMOR_VALUE),
                        rt.getInt(TAG_SKIN_VARIANT),
                        rt.getLong(TAG_TIMESTAMP)));
            }
            storage.colonyResumes.put(colonyId, resumes);
        }
        return storage;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag colonies = new ListTag();
        for (var entry : colonyResumes.entrySet()) {
            CompoundTag colonyTag = new CompoundTag();
            colonyTag.putUUID(TAG_COLONY_ID, entry.getKey());
            ListTag resumesTag = new ListTag();
            for (MageResume r : entry.getValue()) {
                CompoundTag rt = new CompoundTag();
                rt.putString(TAG_NAME, r.touristName());
                rt.putInt(TAG_LEVEL, r.level());
                rt.putFloat(TAG_MAX_HP, r.maxHp());
                rt.putFloat(TAG_MOVE_SPEED, r.moveSpeed());
                rt.putFloat(TAG_SPELL_POWER, r.spellPower());
                rt.putFloat(TAG_WORK_SPEED, r.workSpeed());
                rt.putFloat(TAG_SPELL_SPEED, r.spellSpeed());
                rt.putFloat(TAG_ARMOR_VALUE, r.armorValue());
                rt.putInt(TAG_SKIN_VARIANT, r.skinVariant());
                rt.putLong(TAG_TIMESTAMP, r.timestamp());
                resumesTag.add(rt);
            }
            colonyTag.put(TAG_RESUMES, resumesTag);
            colonies.add(colonyTag);
        }
        tag.put(TAG_COLONIES, colonies);
        return tag;
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }
}
