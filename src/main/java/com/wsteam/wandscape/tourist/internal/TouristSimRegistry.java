package com.wsteam.wandscape.tourist.internal;

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
 * Persists every tourist's data shadow across world sessions.
 *
 * <p>The shadow is the authoritative tourist state: while the tourist's chunk
 * is loaded the physical entity mirrors it, while unloaded the
 * {@link TouristSimSystem} advances it. Keeping one registry for all tourists
 * (loaded and unloaded) makes population / hotel-occupancy queries single-source
 * and avoids double-counting between entities and shadows.
 */
public final class TouristSimRegistry extends SavedData {

    private static final String TAG = "TouristSimRegistry";
    private static final String DATA_NAME = "wandscape_tourist_sim";

    private final Map<UUID, TouristShadow> shadows = new ConcurrentHashMap<>();

    private TouristSimRegistry() {
    }

    // ---- Factory ----

    public static TouristSimRegistry getOrCreate(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new Factory<>(TouristSimRegistry::new, TouristSimRegistry::load),
                DATA_NAME);
    }

    // ---- Registry accessors ----

    public Map<UUID, TouristShadow> getShadows() {
        return shadows;
    }

    public TouristShadow get(UUID touristId) {
        return shadows.get(touristId);
    }

    public void put(UUID touristId, TouristShadow shadow) {
        shadows.put(touristId, shadow);
        setDirty();
    }

    public void remove(UUID touristId) {
        if (shadows.remove(touristId) != null) {
            setDirty();
        }
    }

    public int size() {
        return shadows.size();
    }

    // ---- NBT save/load ----

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (TouristShadow shadow : shadows.values()) {
            list.add(shadow.save(new CompoundTag()));
        }
        tag.put("shadows", list);
        Log.info(TAG, "[TouristSimRegistry] saved {} tourist shadows", shadows.size());
        return tag;
    }

    private static TouristSimRegistry load(CompoundTag tag, HolderLookup.Provider registries) {
        TouristSimRegistry registry = new TouristSimRegistry();
        if (tag.contains("shadows", Tag.TAG_LIST)) {
            ListTag list = tag.getList("shadows", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                TouristShadow shadow = TouristShadow.load(list.getCompound(i));
                if (shadow.getTouristId() != null) {
                    registry.shadows.put(shadow.getTouristId(), shadow);
                }
            }
        }
        Log.info(TAG, "[TouristSimRegistry] loaded {} tourist shadows", registry.shadows.size());
        return registry;
    }
}
