package com.wsteam.wandscape.integration.jei;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.wsteam.wandscape.element.internal.ElementMappingConfig;
import com.wsteam.wandscape.production.data.BrewPotionRecipe;
import com.wsteam.wandscape.production.data.CraftSpellRecipe;
import com.wsteam.wandscape.production.data.CraftWandRecipe;
import com.wsteam.wandscape.shared.data.ElementType;

import net.minecraft.nbt.CompoundTag;

/**
 * 从各配方数据源收集 JEI 展示用配方（纯逻辑，零 mezz 引用，可单测）。
 *
 * <ul>
 *   <li>元素映射：每个有非空 buildCost 且未 disabled 的映射生成工作站「合成」+「分解」两条。</li>
 *   <li>法杖配方：只生成合成站「合成」（法杖不可分解）。</li>
 *   <li>药剂配方：只生成合成站「合成」（带额外原料，随配方 craft_station 数据驱动定位）。</li>
 *   <li>魔法卷轴配方：生成魔法工坊「合成」。</li>
 * </ul>
 */
public final class ElementRecipeCollector {

    public static final String STATION_WORKSTATION = "workstation";
    public static final String STATION_CRAFTING = "crafting_station";
    public static final String STATION_MAGIC = "magic_station";

    private ElementRecipeCollector() {}

    /** 工作站合成 / 分解配方（来自元素映射 buildCost）。 */
    public static List<ElementRecipe> fromElementMappings(Collection<ElementMappingConfig> configs) {
        List<ElementRecipe> recipes = new ArrayList<>();
        for (ElementMappingConfig config : configs) {
            if (config.disabled() || config.buildCost().isEmpty()) continue;
            String itemId = config.itemId() != null ? config.itemId() : config.blockId();
            if (itemId == null) continue;
            long value = sum(config.buildCost());
            recipes.add(new ElementRecipe(itemId, ElementRecipeKind.SYNTHESIZE,
                    STATION_WORKSTATION, itemId, null, config.buildCost(), List.of(), 0));
            recipes.add(new ElementRecipe(itemId, ElementRecipeKind.DECOMPOSE,
                    STATION_WORKSTATION, itemId, null, config.buildCost(), List.of(), value));
        }
        return recipes;
    }

    /** 合成站法杖配方（不可分解，只生成合成；携带 preset NBT 供 JEI 显示具体变体）。 */
    public static List<ElementRecipe> fromCraftWandRecipes(Collection<CraftWandRecipe> recipes) {
        List<ElementRecipe> result = new ArrayList<>();
        for (CraftWandRecipe r : recipes) {
            if (r.cost().isEmpty()) continue;
            String station = r.craftStation() != null ? r.craftStation() : STATION_CRAFTING;
            result.add(new ElementRecipe(r.id(), ElementRecipeKind.SYNTHESIZE, station,
                    r.outputItem(), r.outputNbt(), r.cost(), List.of(), 0));
        }
        return result;
    }

    /** 药剂配方（仅合成，带额外原料；随配方 craft_station 归属合成站）。 */
    public static List<ElementRecipe> fromBrewPotionRecipes(Collection<BrewPotionRecipe> recipes) {
        List<ElementRecipe> result = new ArrayList<>();
        for (BrewPotionRecipe r : recipes) {
            if (r.cost().isEmpty()) continue;
            String station = r.craftStation() != null ? r.craftStation() : STATION_CRAFTING;
            result.add(new ElementRecipe(r.id(), ElementRecipeKind.SYNTHESIZE, station,
                    r.outputItem(), null, r.cost(), r.inputItems(), 0));
        }
        return result;
    }

    /** 魔法卷轴配方（仅合成，魔法工坊；携带 magic_id NBT 供 JEI 显示绑定魔法）。 */
    public static List<ElementRecipe> fromCraftSpellRecipes(Collection<CraftSpellRecipe> recipes) {
        List<ElementRecipe> result = new ArrayList<>();
        for (CraftSpellRecipe r : recipes) {
            if (r.cost().isEmpty()) continue;
            String station = r.craftStation() != null ? r.craftStation() : STATION_MAGIC;
            CompoundTag nbt = new CompoundTag();
            nbt.putString("magic_id", r.magicId());
            result.add(new ElementRecipe(r.id(), ElementRecipeKind.SYNTHESIZE, station,
                    r.outputItem(), nbt, r.cost(), List.of(), 0));
        }
        return result;
    }

    /** 聚合四个来源为完整配方列表。 */
    public static List<ElementRecipe> collectAll(Collection<ElementMappingConfig> mappings,
                                                 Collection<CraftWandRecipe> wands,
                                                 Collection<BrewPotionRecipe> potions,
                                                 Collection<CraftSpellRecipe> spells) {
        List<ElementRecipe> all = fromElementMappings(mappings);
        all.addAll(fromCraftWandRecipes(wands));
        all.addAll(fromBrewPotionRecipes(potions));
        all.addAll(fromCraftSpellRecipes(spells));
        return all;
    }

    private static long sum(Map<ElementType, Long> cost) {
        long total = 0;
        for (long v : cost.values()) total += v;
        return total;
    }

    /**
     * 去掉 {@code minecraft:} 前缀后比较物品 id，与
     * {@code ProductionRecipeLoader.findSynthesizeRecipe} 的匹配逻辑保持一致。
     */
    public static boolean itemIdEquals(String a, String b) {
        if (a == null || b == null) return false;
        return stripMcPrefix(a).equals(stripMcPrefix(b));
    }

    @Nullable
    private static String stripMcPrefix(String id) {
        return id != null && id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }
}
