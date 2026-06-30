package com.wsteam.wandscape.stats.data;

import java.util.HashMap;
import java.util.Map;

import com.wsteam.wandscape.shared.data.ElementType;

import net.minecraft.nbt.CompoundTag;

/**
 * Immutable daily statistical snapshot for one colony at a settlement boundary.
 *
 * <p>Recorded during daily settlement. Captures maintenance activity, tourist
 * traffic from the preceding day, and colony evaluation values at the boundary.
 */
public record ColonyDailySnapshot(
        long day,
        Map<ElementType, Long> elementsConsumed,
        int buildingsPaid,
        int buildingsShutdown,
        int buildingsRestarted,
        int touristsArrived,
        int touristsDeparted,
        int totalSatisfaction,
        int comfort,
        int magic,
        int wonder
) {
    private static final String TAG_DAY = "day";
    private static final String TAG_ELEMENTS_CONSUMED = "elements_consumed";
    private static final String TAG_BUILDINGS_PAID = "buildings_paid";
    private static final String TAG_BUILDINGS_SHUTDOWN = "buildings_shutdown";
    private static final String TAG_BUILDINGS_RESTARTED = "buildings_restarted";
    private static final String TAG_TOURISTS_ARRIVED = "tourists_arrived";
    private static final String TAG_TOURISTS_DEPARTED = "tourists_departed";
    private static final String TAG_TOTAL_SATISFACTION = "total_satisfaction";
    private static final String TAG_COMFORT = "comfort";
    private static final String TAG_MAGIC = "magic";
    private static final String TAG_WONDER = "wonder";

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putLong(TAG_DAY, day);

        CompoundTag elemTag = new CompoundTag();
        for (var entry : elementsConsumed.entrySet()) {
            elemTag.putLong(entry.getKey().getId(), entry.getValue());
        }
        tag.put(TAG_ELEMENTS_CONSUMED, elemTag);

        tag.putInt(TAG_BUILDINGS_PAID, buildingsPaid);
        tag.putInt(TAG_BUILDINGS_SHUTDOWN, buildingsShutdown);
        tag.putInt(TAG_BUILDINGS_RESTARTED, buildingsRestarted);
        tag.putInt(TAG_TOURISTS_ARRIVED, touristsArrived);
        tag.putInt(TAG_TOURISTS_DEPARTED, touristsDeparted);
        tag.putInt(TAG_TOTAL_SATISFACTION, totalSatisfaction);
        tag.putInt(TAG_COMFORT, comfort);
        tag.putInt(TAG_MAGIC, magic);
        tag.putInt(TAG_WONDER, wonder);
        return tag;
    }

    public static ColonyDailySnapshot fromNbt(CompoundTag tag) {
        long day = tag.getLong(TAG_DAY);

        CompoundTag elemTag = tag.getCompound(TAG_ELEMENTS_CONSUMED);
        Map<ElementType, Long> consumed = new HashMap<>();
        for (String key : elemTag.getAllKeys()) {
            consumed.put(ElementType.fromId(key), elemTag.getLong(key));
        }

        int buildingsPaid = tag.getInt(TAG_BUILDINGS_PAID);
        int buildingsShutdown = tag.getInt(TAG_BUILDINGS_SHUTDOWN);
        int buildingsRestarted = tag.getInt(TAG_BUILDINGS_RESTARTED);
        int touristsArrived = tag.getInt(TAG_TOURISTS_ARRIVED);
        int touristsDeparted = tag.getInt(TAG_TOURISTS_DEPARTED);
        int totalSatisfaction = tag.getInt(TAG_TOTAL_SATISFACTION);
        int comfort = tag.getInt(TAG_COMFORT);
        int magic = tag.getInt(TAG_MAGIC);
        int wonder = tag.getInt(TAG_WONDER);

        return new ColonyDailySnapshot(
                day, consumed,
                buildingsPaid, buildingsShutdown, buildingsRestarted,
                touristsArrived, touristsDeparted, totalSatisfaction,
                comfort, magic, wonder);
    }
}
