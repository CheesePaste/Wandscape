package com.wsteam.wandscape.content.colony.stats.internal;
import com.wsteam.wandscape.content.task.ecs.World;

import com.wsteam.wandscape.content.colony.stats.data.ColonyDailySnapshot;
import com.wsteam.wandscape.content.colony.stats.data.ColonyStatsSummary;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.*;

/**
 * Persistent storage for colony daily statistics.
 *
 * <p>Maintains a configurable rolling window of daily snapshots per colony
 * (default 30). Snapshots are stored with newest first for efficient trimming.
 */
public final class StatisticsData extends SavedData {

    private static final String DATA_NAME = "wandscape_statistics";
    private static final String TAG_COLONY_DATA = "colony_data";
    private static final String TAG_COLONY_ID = "colony_id";
    private static final String TAG_SNAPSHOTS = "snapshots";

    static final int MAX_SNAPSHOTS = 30;

    private final Map<UUID, LinkedList<ColonyDailySnapshot>> colonySnapshots = new HashMap<>();

    // ── Factory ──

    public static final Factory<StatisticsData> FACTORY = new Factory<>(
            StatisticsData::new,
            StatisticsData::load,
            null
    );

    public static StatisticsData get(Level level) {
        return level.getServer().overworld()
                .getDataStorage()
                .computeIfAbsent(FACTORY, DATA_NAME);
    }

    // ── Mutators ──

    /** Add a snapshot for a colony, trimming to the rolling window. */
    public synchronized void addSnapshot(UUID colonyId, ColonyDailySnapshot snapshot) {
        LinkedList<ColonyDailySnapshot> list = colonySnapshots
                .computeIfAbsent(colonyId, k -> new LinkedList<>());
        list.addFirst(snapshot);
        if (list.size() > MAX_SNAPSHOTS) {
            list.removeLast();
        }
        setDirty();
    }

    /** Remove all snapshots for a colony. */
    public synchronized void clear(UUID colonyId) {
        colonySnapshots.remove(colonyId);
        setDirty();
    }

    // ── Query ──

    public synchronized List<ColonyDailySnapshot> getSnapshots(UUID colonyId) {
        LinkedList<ColonyDailySnapshot> list = colonySnapshots.get(colonyId);
        if (list == null) return List.of();
        return List.copyOf(list);
    }

    /**
     * Compute the aggregate summary over all stored snapshots for a colony.
     * Returns {@link ColonyStatsSummary#EMPTY} when no data exists.
     */
    public synchronized ColonyStatsSummary computeSummary(UUID colonyId) {
        LinkedList<ColonyDailySnapshot> list = colonySnapshots.get(colonyId);
        if (list == null || list.isEmpty()) return ColonyStatsSummary.EMPTY;

        int touristsArrived = 0;
        int touristsDeparted = 0;
        int touristComfortTotal = 0;
        int touristMagicTotal = 0;
        int touristWonderTotal = 0;
        long currentDay = 0;
        int comfort = 0;
        int magic = 0;
        int wonder = 0;

        for (ColonyDailySnapshot snap : list) {
            touristsArrived += snap.touristsArrived();
            touristsDeparted += snap.touristsDeparted();
            touristComfortTotal += snap.touristComfortTotal();
            touristMagicTotal += snap.touristMagicTotal();
            touristWonderTotal += snap.touristWonderTotal();
            currentDay = Math.max(currentDay, snap.day());
        }

        // Fresh evaluation values from the most recent snapshot
        ColonyDailySnapshot newest = list.getFirst();
        comfort = newest.comfort();
        magic = newest.magic();
        wonder = newest.wonder();

        int avgComfortRatio = touristsDeparted > 0
                ? (int) Math.round((double) touristComfortTotal / touristsDeparted)
                : 0;
        int avgMagicRatio = touristsDeparted > 0
                ? (int) Math.round((double) touristMagicTotal / touristsDeparted)
                : 0;
        int avgWonderRatio = touristsDeparted > 0
                ? (int) Math.round((double) touristWonderTotal / touristsDeparted)
                : 0;

        return new ColonyStatsSummary(
                currentDay,
                touristsArrived, touristsDeparted,
                avgComfortRatio, avgMagicRatio, avgWonderRatio,
                comfort, magic, wonder,
                list.size());
    }

    // ── NBT ──

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag colonyList = new ListTag();
        for (var entry : colonySnapshots.entrySet()) {
            CompoundTag colonyTag = new CompoundTag();
            colonyTag.putUUID(TAG_COLONY_ID, entry.getKey());

            ListTag snapList = new ListTag();
            for (ColonyDailySnapshot snap : entry.getValue()) {
                snapList.add(snap.toNbt());
            }
            colonyTag.put(TAG_SNAPSHOTS, snapList);
            colonyList.add(colonyTag);
        }
        tag.put(TAG_COLONY_DATA, colonyList);
        return tag;
    }

    private static StatisticsData load(CompoundTag tag, HolderLookup.Provider registries) {
        StatisticsData data = new StatisticsData();
        ListTag colonyList = tag.getList(TAG_COLONY_DATA, Tag.TAG_COMPOUND);
        for (int i = 0; i < colonyList.size(); i++) {
            CompoundTag colonyTag = colonyList.getCompound(i);
            UUID colonyId = colonyTag.getUUID(TAG_COLONY_ID);

            LinkedList<ColonyDailySnapshot> snapshots = new LinkedList<>();
            ListTag snapList = colonyTag.getList(TAG_SNAPSHOTS, Tag.TAG_COMPOUND);
            for (int j = 0; j < snapList.size(); j++) {
                snapshots.addLast(ColonyDailySnapshot.fromNbt(snapList.getCompound(j)));
            }

            if (!snapshots.isEmpty()) {
                data.colonySnapshots.put(colonyId, snapshots);
            }
        }
        return data;
    }
}
