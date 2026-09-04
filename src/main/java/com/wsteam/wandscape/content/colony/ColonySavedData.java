package com.wsteam.wandscape.content.colony;
import com.wsteam.wandscape.content.task.ecs.World;

import com.wsteam.wandscape.foundation.util.NameStyle;
import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Standalone persistence for colonies — does not depend on buildings.
 *
 * <p>Each colony is stored as (colonyId → origin). On server restart,
 * {@code ColonyApiImpl.rebuildFromSavedData()} reads from this store directly
 * instead of scanning {@code BuildingSavedData} for government buildings.
 */
public class ColonySavedData extends SavedData {
    private static final String TAG = "ColonySavedData";
    private static final String DATA_NAME = "wandscape_colonies";

    private static final String KEY_COLONIES = "colonies";
    private static final String KEY_ID = "id";
    private static final String KEY_X = "x";
    private static final String KEY_Y = "y";
    private static final String KEY_Z = "z";
    private static final String KEY_FOUNDER = "founder";
    private static final String KEY_NAMING_STYLE = "namingStyle";
    private static final String KEY_TOURIST_SPAWN_DISABLED = "touristSpawnDisabled";

    private final Map<UUID, BlockPos> colonies = new ConcurrentHashMap<>();
    /** colonyId → founding player UUID (informational; permissions remain shared). */
    private final Map<UUID, UUID> founders = new ConcurrentHashMap<>();
    /** colonyId → character naming rule (defaults to FANTASY when absent). */
    private final Map<UUID, NameStyle> namingStyles = new ConcurrentHashMap<>();
    /** Colony IDs whose town hall 「生成游客」 toggle is OFF (absent = enabled). */
    private final Set<UUID> touristSpawnDisabled = ConcurrentHashMap.newKeySet();

    private static final Factory<ColonySavedData> FACTORY = new Factory<>(
            ColonySavedData::new,
            ColonySavedData::load,
            null
    );

    public static ColonySavedData getOrCreate(Level level) {
        return level.getServer().overworld()
                .getDataStorage()
                .computeIfAbsent(FACTORY, DATA_NAME);
    }

    // ── Accessors ──

    public void addColony(UUID colonyId, BlockPos origin) {
        addColony(colonyId, origin, null);
    }

    public void addColony(UUID colonyId, BlockPos origin, @Nullable UUID founder) {
        colonies.put(colonyId, origin.immutable());
        if (founder != null) {
            founders.put(colonyId, founder);
        }
        setDirty();
        Log.info(TAG, "[Colony] Persisted colony {} at {}", colonyId.toString().substring(0, 8), origin);
    }

    public void removeColony(UUID colonyId) {
        BlockPos removed = colonies.remove(colonyId);
        founders.remove(colonyId);
        namingStyles.remove(colonyId);
        touristSpawnDisabled.remove(colonyId);
        if (removed != null) {
            setDirty();
            Log.info(TAG, "[Colony] Removed colony {} from persistence", colonyId.toString().substring(0, 8));
        }
    }

    @Nullable
    public BlockPos getOrigin(UUID colonyId) {
        return colonies.get(colonyId);
    }

    @Nullable
    public UUID getFounder(UUID colonyId) {
        return founders.get(colonyId);
    }

    /** The colony founded by the given player (one player = one colony), or null. */
    @Nullable
    public UUID getColonyByFounder(UUID founder) {
        for (var entry : founders.entrySet()) {
            if (entry.getValue().equals(founder)) return entry.getKey();
        }
        return null;
    }

    /** Naming rule for future tourist/NPC names; defaults to FANTASY. */
    public NameStyle getNamingStyle(UUID colonyId) {
        return namingStyles.getOrDefault(colonyId, NameStyle.FANTASY);
    }

    public void setNamingStyle(UUID colonyId, NameStyle style) {
        if (style == getNamingStyle(colonyId)) return;
        namingStyles.put(colonyId, style);
        setDirty();
        Log.info(TAG, "[Colony] Naming style for colony {} → {}",
                colonyId.toString().substring(0, 8), style);
    }

    /** True when the colony's town hall has 「生成游客」 enabled (default true). */
    public boolean isTouristSpawningEnabled(UUID colonyId) {
        return !touristSpawnDisabled.contains(colonyId);
    }

    public void setTouristSpawningEnabled(UUID colonyId, boolean enabled) {
        boolean changed = enabled ? touristSpawnDisabled.remove(colonyId)
                : touristSpawnDisabled.add(colonyId);
        if (!changed) return;
        setDirty();
        Log.info(TAG, "[Colony] Tourist spawning for colony {} → {}",
                colonyId.toString().substring(0, 8), enabled ? "enabled" : "disabled");
    }

    public Map<UUID, BlockPos> getAllColonies() {
        return Collections.unmodifiableMap(colonies);
    }

    public int size() {
        return colonies.size();
    }

    /**
     * Write colony data to disk synchronously, bypassing NeoForge's async IO
     * worker. Call this immediately after {@link #addColony} to guarantee the
     * colony survives a crash or quick exit.
     *
     * @param level      the overworld (used to locate the data folder)
     * @param registries the server registry access
     */
    public void saveNow(Level level, HolderLookup.Provider registries) {
        if (!isDirty()) return;

        CompoundTag root = new CompoundTag();
        root.put("data", this.save(new CompoundTag(), registries));
        NbtUtils.addCurrentDataVersion(root);
        CompoundTag copied = root.copy();

        Path filePath = level.getServer().getWorldPath(
                net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("data")
                .resolve(DATA_NAME + ".dat");

        try {
            net.neoforged.neoforge.common.IOUtilities.writeNbtCompressed(
                    copied, filePath);
        } catch (IOException e) {
            Log.error(TAG, "Failed to force-save colony data to {}", filePath);
            return;
        }

        setDirty(false);
        Log.info(TAG, "[Colony] Force-saved {} colonies to disk", colonies.size());
    }

    // ── NBT persistence ──

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (var entry : colonies.entrySet()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putUUID(KEY_ID, entry.getKey());
            BlockPos pos = entry.getValue();
            entryTag.putInt(KEY_X, pos.getX());
            entryTag.putInt(KEY_Y, pos.getY());
            entryTag.putInt(KEY_Z, pos.getZ());
            UUID founder = founders.get(entry.getKey());
            if (founder != null) {
                entryTag.putUUID(KEY_FOUNDER, founder);
            }
            NameStyle style = namingStyles.get(entry.getKey());
            if (style != null) {
                entryTag.putString(KEY_NAMING_STYLE, style.name());
            }
            list.add(entryTag);
        }
        tag.put(KEY_COLONIES, list);

        if (!touristSpawnDisabled.isEmpty()) {
            ListTag disabled = new ListTag();
            for (UUID id : touristSpawnDisabled) {
                disabled.add(net.minecraft.nbt.StringTag.valueOf(id.toString()));
            }
            tag.put(KEY_TOURIST_SPAWN_DISABLED, disabled);
        }
        return tag;
    }

    private static ColonySavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        ColonySavedData data = new ColonySavedData();
        ListTag list = tag.getList(KEY_COLONIES, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            UUID id = entry.getUUID(KEY_ID);
            int x = entry.getInt(KEY_X);
            int y = entry.getInt(KEY_Y);
            int z = entry.getInt(KEY_Z);
            data.colonies.put(id, new BlockPos(x, y, z));
            if (entry.contains(KEY_FOUNDER)) {
                data.founders.put(id, entry.getUUID(KEY_FOUNDER));
            }
            if (entry.contains(KEY_NAMING_STYLE)) {
                try {
                    data.namingStyles.put(id, NameStyle.valueOf(entry.getString(KEY_NAMING_STYLE)));
                } catch (IllegalArgumentException e) {
                    Log.warn(TAG, "[Colony] Unknown naming style '{}' for colony {}, using FANTASY",
                            entry.getString(KEY_NAMING_STYLE), id);
                }
            }
        }
        Log.info(TAG, "Loaded {} colonies from saved data", data.colonies.size());

        if (tag.contains(KEY_TOURIST_SPAWN_DISABLED)) {
            ListTag disabled = tag.getList(KEY_TOURIST_SPAWN_DISABLED, Tag.TAG_STRING);
            for (int i = 0; i < disabled.size(); i++) {
                try {
                    data.touristSpawnDisabled.add(UUID.fromString(disabled.getString(i)));
                } catch (IllegalArgumentException e) {
                    Log.warn(TAG, "[Colony] Bad tourist-spawn-disabled UUID '{}', skipped",
                            disabled.getString(i));
                }
            }
        }
        return data;
    }
}
