package com.wsteam.wandscape.content.colony.exploration;

import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.content.element.data.ElementType;
import com.wsteam.wandscape.foundation.log.Log;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Samples a loot table to find out what one chest of it is worth in elements.
 *
 * <p>This is the only place that turns loot into value, and it reads item worth through
 * {@code ElementApi.getBuildCost} — the same element mapping the build economy uses. It is
 * a <b>generator</b>, not a runtime oracle: a region that declares {@code reward.value}
 * never reaches here.
 *
 * <p>Only the game can answer this question. Loot tables expand pool weights, random counts,
 * enchantment functions and tags against live registries, so this cannot be precomputed
 * offline — which is why a region's numbers are worth writing down once instead of sampling
 * forever.
 */
public final class ExplorationLootSampler {

    private static final String TAG = "ExplorationLootSampler";

    /** Sampling cost is paid once per loot table, not per chest, so this is deliberately generous. */
    private static final int FALLBACK_SAMPLE_COUNT = 50;

    private ExplorationLootSampler() {}

    /**
     * Average element value of a single draw from the loot table.
     *
     * @return per-element expectation, empty when the table is missing or nothing in it is priced
     */
    public static Map<ElementType, Long> sample(ServerLevel level, ResourceKey<LootTable> lootKey) {
        if (level == null || lootKey == null) return Map.of();

        LootTable lootTable = level.getServer().reloadableRegistries().getLootTable(lootKey);
        if (lootTable == null || lootTable == LootTable.EMPTY) {
            Log.warn(TAG, "Loot table {} is missing or empty — nothing to sample", lootKey.location());
            return Map.of();
        }

        int sampleCount = Config.SPEC.isLoaded()
                ? Config.EXPLORATION_CHEST_SAMPLE_COUNT.get()
                : FALLBACK_SAMPLE_COUNT;

        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, Vec3.ZERO)
                .create(LootContextParamSets.CHEST);

        List<Map<ElementType, Long>> sampleDraws = new ArrayList<>(sampleCount);
        for (int i = 0; i < sampleCount; i++) {
            sampleDraws.add(valueOf(lootTable.getRandomItems(params)));
        }

        Map<ElementType, Long> expectation = ExplorationExpectationCalculator.expectation(sampleDraws);
        if (ExplorationExpectationCalculator.isDegenerate(expectation)) {
            Log.warn(TAG, "Loot table {} yielded no element value in {} samples — its items have no "
                            + "element_mappings entry. Declare reward.value for this region to stop relying "
                            + "on the element mapping.",
                    lootKey.location(), sampleCount);
        }
        return expectation;
    }

    /** Element value of one draw: every item priced by its element mapping, times its count. */
    private static Map<ElementType, Long> valueOf(List<ItemStack> items) {
        Map<ElementType, Long> value = new LinkedHashMap<>();
        if (items == null) return value;

        for (ItemStack stack : items) {
            if (stack == null || stack.isEmpty()) continue;
            Map<ElementType, Long> cost = Wandscape.ELEMENT_API.getBuildCost(stack);
            if (cost == null || cost.isEmpty()) continue;
            long count = stack.getCount();
            for (Map.Entry<ElementType, Long> entry : cost.entrySet()) {
                value.merge(entry.getKey(), entry.getValue() * count, Long::sum);
            }
        }
        return value;
    }
}
