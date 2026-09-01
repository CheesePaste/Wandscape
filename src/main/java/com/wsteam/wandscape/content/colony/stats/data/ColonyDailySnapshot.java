package com.wsteam.wandscape.content.colony.stats.data;

import net.minecraft.nbt.CompoundTag;

/**
 * Immutable daily statistical snapshot for one colony at a settlement boundary.
 *
 * <p>Recorded during daily settlement. Captures tourist traffic from the
 * preceding day and colony evaluation values at the boundary.
 */
public record ColonyDailySnapshot(
        long day,
        int touristsArrived,
        int touristsDeparted,
        int touristComfortTotal,
        int touristMagicTotal,
        int touristWonderTotal,
        int comfort,
        int magic,
        int wonder
) {
    private static final String TAG_DAY = "day";
    private static final String TAG_TOURISTS_ARRIVED = "tourists_arrived";
    private static final String TAG_TOURISTS_DEPARTED = "tourists_departed";
    private static final String TAG_TOURIST_COMFORT_TOTAL = "tourist_comfort_total";
    private static final String TAG_TOURIST_MAGIC_TOTAL = "tourist_magic_total";
    private static final String TAG_TOURIST_WONDER_TOTAL = "tourist_wonder_total";
    private static final String TAG_COMFORT = "comfort";
    private static final String TAG_MAGIC = "magic";
    private static final String TAG_WONDER = "wonder";

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putLong(TAG_DAY, day);
        tag.putInt(TAG_TOURISTS_ARRIVED, touristsArrived);
        tag.putInt(TAG_TOURISTS_DEPARTED, touristsDeparted);
        tag.putInt(TAG_TOURIST_COMFORT_TOTAL, touristComfortTotal);
        tag.putInt(TAG_TOURIST_MAGIC_TOTAL, touristMagicTotal);
        tag.putInt(TAG_TOURIST_WONDER_TOTAL, touristWonderTotal);
        tag.putInt(TAG_COMFORT, comfort);
        tag.putInt(TAG_MAGIC, magic);
        tag.putInt(TAG_WONDER, wonder);
        return tag;
    }

    public static ColonyDailySnapshot fromNbt(CompoundTag tag) {
        long day = tag.getLong(TAG_DAY);
        int touristsArrived = tag.getInt(TAG_TOURISTS_ARRIVED);
        int touristsDeparted = tag.getInt(TAG_TOURISTS_DEPARTED);
        int touristComfortTotal = tag.getInt(TAG_TOURIST_COMFORT_TOTAL);
        int touristMagicTotal = tag.getInt(TAG_TOURIST_MAGIC_TOTAL);
        int touristWonderTotal = tag.getInt(TAG_TOURIST_WONDER_TOTAL);
        int comfort = tag.getInt(TAG_COMFORT);
        int magic = tag.getInt(TAG_MAGIC);
        int wonder = tag.getInt(TAG_WONDER);
        return new ColonyDailySnapshot(
                day,
                touristsArrived, touristsDeparted,
                touristComfortTotal, touristMagicTotal, touristWonderTotal,
                comfort, magic, wonder);
    }
}
