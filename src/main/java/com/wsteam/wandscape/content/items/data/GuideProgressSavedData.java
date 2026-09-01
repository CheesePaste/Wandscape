package com.wsteam.wandscape.content.items.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player tutorial progress (highest reached step + dismissal), stored in
 * the overworld so it survives restarts. Keyed by player UUID.
 */
public class GuideProgressSavedData extends SavedData {

    public static final String DATA_NAME = "wandscape_guide_progress";

    private static final String TAG_PLAYERS = "players";
    private static final String TAG_UUID = "uuid";
    private static final String TAG_STEP = "step";
    private static final String TAG_DISMISSED = "dismissed";

    /** stepIndex = number of completed steps (0 = none); dismissed = guide closed. */
    public record GuideProgress(int stepIndex, boolean dismissed) {}

    private final Map<UUID, GuideProgress> progress = new HashMap<>();

    public static final Factory<GuideProgressSavedData> FACTORY = new Factory<>(
            GuideProgressSavedData::new,
            GuideProgressSavedData::load,
            null);

    public static GuideProgressSavedData get(Level level) {
        return level.getServer().overworld()
                .getDataStorage()
                .computeIfAbsent(FACTORY, DATA_NAME);
    }

    /** Saved progress for a player, or {@code (0, false)} if none recorded. */
    public GuideProgress get(UUID playerId) {
        return progress.getOrDefault(playerId, new GuideProgress(0, false));
    }

    public void set(UUID playerId, int stepIndex, boolean dismissed) {
        progress.put(playerId, new GuideProgress(Math.max(0, stepIndex), dismissed));
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (var e : progress.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID(TAG_UUID, e.getKey());
            entry.putInt(TAG_STEP, e.getValue().stepIndex());
            entry.putBoolean(TAG_DISMISSED, e.getValue().dismissed());
            list.add(entry);
        }
        tag.put(TAG_PLAYERS, list);
        return tag;
    }

    private static GuideProgressSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        GuideProgressSavedData data = new GuideProgressSavedData();
        ListTag list = tag.getList(TAG_PLAYERS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (!entry.hasUUID(TAG_UUID)) continue;
            data.progress.put(entry.getUUID(TAG_UUID),
                    new GuideProgress(entry.getInt(TAG_STEP), entry.getBoolean(TAG_DISMISSED)));
        }
        return data;
    }
}
