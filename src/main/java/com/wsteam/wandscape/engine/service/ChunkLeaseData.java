package com.wsteam.wandscape.engine.service;

import com.wsteam.wandscape.shared.log.Log;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.*;

/**
 * Persists the chunk-lease registry (buildingId → footprint chunks) across
 * world sessions.
 *
 * <p>Vanilla {@code setChunkForced} writes to {@code ForcedChunksSavedData},
 * which survives crashes. This registry lets {@link ChunkLoadManager}
 * reconcile stale force-loads on the next server start instead of leaving
 * chunks permanently loaded.
 */
public final class ChunkLeaseData extends SavedData {

    private static final String TAG = "ChunkLeaseData";
    private static final String DATA_NAME = "wandscape_chunk_leases";

    private final Map<UUID, Set<ChunkPos>> leases = new HashMap<>();

    private ChunkLeaseData() {
    }

    // ---- Factory ----

    public static ChunkLeaseData getOrCreate(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new Factory<>(ChunkLeaseData::new, ChunkLeaseData::load),
                DATA_NAME);
    }

    // ---- Registry accessors ----

    public Map<UUID, Set<ChunkPos>> getLeases() {
        return leases;
    }

    public void addLease(UUID buildingId, Set<ChunkPos> chunks) {
        leases.put(buildingId, new HashSet<>(chunks));
        setDirty();
    }

    public void removeLease(UUID buildingId) {
        if (leases.remove(buildingId) != null) {
            setDirty();
        }
    }

    public void clearAll() {
        if (!leases.isEmpty()) {
            leases.clear();
            setDirty();
        }
    }

    // ---- NBT save/load ----

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (var entry : leases.entrySet()) {
            CompoundTag b = new CompoundTag();
            b.putUUID("id", entry.getKey());
            long[] chunks = entry.getValue().stream().mapToLong(ChunkPos::toLong).toArray();
            b.putLongArray("chunks", chunks);
            list.add(b);
        }
        tag.put("leases", list);
        Log.info(TAG, "[ChunkLeaseData] saved {} building leases", leases.size());
        return tag;
    }

    private static ChunkLeaseData load(CompoundTag tag, HolderLookup.Provider registries) {
        ChunkLeaseData data = new ChunkLeaseData();
        if (tag.contains("leases", Tag.TAG_LIST)) {
            ListTag list = tag.getList("leases", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag b = list.getCompound(i);
                UUID id = b.getUUID("id");
                long[] chunks = b.getLongArray("chunks");
                if (id == null) continue;
                Set<ChunkPos> set = new HashSet<>();
                for (long c : chunks) {
                    set.add(new ChunkPos(c));
                }
                if (!set.isEmpty()) {
                    data.leases.put(id, set);
                }
            }
        }
        Log.info(TAG, "[ChunkLeaseData] loaded {} building leases", data.leases.size());
        return data;
    }
}
