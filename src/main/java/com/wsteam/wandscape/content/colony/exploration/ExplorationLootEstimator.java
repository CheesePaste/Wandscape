package com.wsteam.wandscape.content.colony.exploration;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.content.element.data.ElementType;
import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntry;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Works out what one chest of a loot table is worth in elements, by reading the loot table's
 * own weights rather than rolling it.
 *
 * <p>For each pool, every entry's share of a roll is its {@code weight / total weight}; a roll
 * happens {@code rolls} times; an entry's per-hit worth is its items priced through
 * {@code ElementApi.getBuildCost} — the same element mapping the build economy uses. The sums
 * are the expectation.
 *
 * <p>Rather than walking the entry classes by hand, this drives the vanilla machinery with a
 * {@link ExplorationProbeRandom} that answers every draw with its range's midpoint:
 * {@link LootPoolEntryContainer#expand} flattens the container tree (honouring conditions and
 * alternatives/sequence/group), {@link LootPoolEntry#getWeight} gives the odds, and
 * {@code createItemStack} produces the items with their functions applied — {@code set_count}
 * included — but at their expected counts. That keeps this exact for constant and uniform
 * numbers, and cheap: no randomness, no repeated draws.
 *
 * <p>Known approximation: a pool entry that references another loot table gets one probe pass,
 * so the referenced table's own weighted pick collapses onto a single branch. Vanilla chest
 * tables rarely nest this way; when they do, the estimate is off rather than zero.
 */
public final class ExplorationLootEstimator {

    private static final String TAG = "ExplorationLootEstimator";

    /**
     * Calibration applied to what a loot table is worth before it reaches the reward maths.
     *
     * <p>Element mappings price an item by what it costs to build with. That measures a chest's
     * worth honestly, but it turns out to undershoot how rewarding finding one should feel — the
     * payouts came out roughly half of what they wanted to be.
     *
     * <p>It belongs here rather than in {@code exp_ratio} because EXP is the element total divided
     * by that ratio: scaling the ratio would move EXP alone and leave the elements small, while
     * scaling the value moves both together, which is what "the chests pay too little" means.
     *
     * <p>Only estimates are scaled. A declared {@code reward.value} is never touched — whoever
     * writes a number means that number.
     */
    private static final double ESTIMATE_SCALE = 2.0;

    private ExplorationLootEstimator() {}

    /**
     * Expected element value of a single chest from this loot table.
     *
     * @return per-element value, empty when the table is missing or nothing in it is priced
     */
    public static Map<ElementType, Long> estimate(ServerLevel level, ResourceKey<LootTable> lootKey) {
        if (level == null || lootKey == null) return Map.of();

        LootTable table = level.getServer().reloadableRegistries().getLootTable(lootKey);
        if (table == null || table == LootTable.EMPTY) {
            Log.warn(TAG, "Loot table {} is missing or empty — nothing to price", lootKey.location());
            return Map.of();
        }

        Map<ElementType, Double> expected = new EnumMap<>(ElementType.class);
        try {
            LootContext probe = probeContext(level);
            for (LootPool pool : table.pools) {
                accumulatePool(pool, probe, expected);
            }
        } catch (Throwable t) {
            // A modded entry type doing something exotic must not take the server down with it.
            Log.warn(TAG, "Failed to estimate loot table {}: {}", lootKey.location(), t.toString());
            return Map.of();
        }
        return round(expected);
    }

    /** A context whose random source answers with midpoints, so one pass yields the expectation. */
    private static LootContext probeContext(ServerLevel level) {
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, Vec3.ZERO)
                .create(LootContextParamSets.CHEST);
        return new LootContext.Builder(params)
                .withOptionalRandomSource(ExplorationProbeRandom.INSTANCE)
                .create(Optional.empty());
    }

    private static void accumulatePool(LootPool pool, LootContext probe, Map<ElementType, Double> out) {
        // bonus_rolls scale with luck, which is 0 for a plain chest opening.
        int rolls = pool.getRolls().getInt(probe);
        if (rolls <= 0) return;

        List<LootPoolEntry> candidates = new ArrayList<>();
        int totalWeight = 0;
        for (LootPoolEntryContainer container : pool.entries) {
            container.expand(probe, entry -> candidates.add(entry));
        }
        for (LootPoolEntry entry : candidates) {
            totalWeight += Math.max(0, entry.getWeight(0.0f));
        }
        if (totalWeight <= 0) return;

        for (LootPoolEntry entry : candidates) {
            int weight = Math.max(0, entry.getWeight(0.0f));
            if (weight == 0) continue;
            double hits = rolls * ((double) weight / totalWeight);
            for (Map.Entry<ElementType, Long> e : probeEntry(entry, probe).entrySet()) {
                out.merge(e.getKey(), e.getValue() * hits, Double::sum);
            }
        }
    }

    /** What one hit of this entry is worth, with counts read at their expected values. */
    private static Map<ElementType, Long> probeEntry(LootPoolEntry entry, LootContext probe) {
        Map<ElementType, Long> value = new LinkedHashMap<>();
        entry.createItemStack(stack -> addStackValue(stack, value), probe);
        return value;
    }

    private static void addStackValue(ItemStack stack, Map<ElementType, Long> out) {
        if (stack == null || stack.isEmpty()) return;
        Map<ElementType, Long> cost = Wandscape.ELEMENT_API.getBuildCost(stack);
        if (cost == null || cost.isEmpty()) return;
        long count = stack.getCount();
        for (Map.Entry<ElementType, Long> e : cost.entrySet()) {
            out.merge(e.getKey(), e.getValue() * count, Long::sum);
        }
    }

    private static Map<ElementType, Long> round(Map<ElementType, Double> expected) {
        Map<ElementType, Long> out = new LinkedHashMap<>();
        for (ElementType type : ElementType.values()) {
            long value = Math.round(expected.getOrDefault(type, 0.0) * ESTIMATE_SCALE);
            if (value > 0) out.put(type, value);
        }
        return out;
    }
}
