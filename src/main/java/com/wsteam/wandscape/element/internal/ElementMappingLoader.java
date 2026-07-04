package com.wsteam.wandscape.element.internal;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wsteam.wandscape.dataconfig.internal.WandscapeDataLoader;
import com.wsteam.wandscape.shared.data.ElementType;
import com.wsteam.wandscape.shared.registry.WandscapeDataRegistry;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
public class ElementMappingLoader {
    private static final String TAG = "ElementMappingLoader";
    private static final String CATEGORY = "element_mappings";

    private final WandscapeDataRegistry<ElementMappingConfig> registry;

    /** Seed values loaded from element_seeds.json — used for Workstation decomposition. */
    private final Map<String, Map<ElementType, Long>> seedValues = new LinkedHashMap<>();

    public ElementMappingLoader(WandscapeDataLoader dataLoader) {
        this.registry = dataLoader.register(CATEGORY, ElementMappingConfig::fromJson);
    }

    public Map<ElementType, Long> getBuildCost(BlockState state) {
        ElementMappingConfig config = findConfig(state);
        return config != null ? config.buildCost() : Map.of();
    }

    public Map<ElementType, Long> getDecomposeYield(BlockState state) {
        ElementMappingConfig config = findConfig(state);
        return config != null ? config.decomposeYield() : Map.of();
    }

    /** Find a representative block ID for an element type (for visual transport). */
    @javax.annotation.Nullable
    public String getRepresentativeBlock(ElementType element) {
        for (ElementMappingConfig config : registry.getAll().values()) {
            if (config.decomposeYield().containsKey(element)) {
                return config.blockId();
            }
        }
        return null;
    }

    public boolean isDecomposable(BlockState state) {
        ElementMappingConfig config = findConfig(state);
        return config != null && config.decomposable();
    }

    public Map<ElementType, Long> getItemDecomposeYield(Item item) {
        ElementMappingConfig config = findConfigByItem(item);
        return config != null ? config.decomposeYield() : Map.of();
    }

    public Map<ElementType, Long> getItemBuildCost(Item item) {
        ElementMappingConfig config = findConfigByItem(item);
        return config != null ? config.buildCost() : Map.of();
    }

    private ElementMappingConfig findConfig(BlockState state) {
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        String blockId = key.toString();
        for (ElementMappingConfig config : registry.getAll().values()) {
            if (blockId.equals(config.blockId())) return config;
        }
        // Fallback: check if an item mapping exists for this block's item form
        String itemId = blockId; // blocks and their items share the same ID
        return findConfigByItemId(itemId);
    }

    private ElementMappingConfig findConfigByItem(Item item) {
        String id = BuiltInRegistries.ITEM.getKey(item).toString();
        return findConfigByItemId(id);
    }

    private ElementMappingConfig findConfigByItemId(String itemId) {
        for (ElementMappingConfig config : registry.getAll().values()) {
            if (itemId.equals(config.itemId())||itemId.equals(config.blockId())) return config;
        }
        return null;
    }

    public boolean hasMapping(String blockOrItemId) {
        return findConfigByItemId(blockOrItemId) != null;
    }

    // ── Seed values (from element_seeds.json) ──

    /** Parse and load seed values from element_seeds.json content. */
    public void loadSeedValues(String jsonContent) {
        seedValues.clear();
        JsonObject root = JsonParser.parseString(jsonContent).getAsJsonObject();
        for (var elem : root.getAsJsonArray("seeds")) {
            JsonObject obj = elem.getAsJsonObject();
            String itemId = obj.get("item").getAsString();
            Map<ElementType, Long> values = new LinkedHashMap<>();
            if (obj.has("values")) {
                JsonObject valObj = obj.getAsJsonObject("values");
                for (var entry : valObj.entrySet()) {
                    ElementType type = ElementType.valueOf(entry.getKey().toUpperCase());
                    values.put(type, entry.getValue().getAsLong());
                }
            }
            if (!values.isEmpty()) {
                seedValues.put(itemId, values);
            }
        }
    }

    /** Check if an item has seed values (can be decomposed at a Workstation). */
    public boolean hasSeedValue(String itemId) {
        return seedValues.containsKey(itemId);
    }

    /** Get the element values for a seed item. */
    public Map<ElementType, Long> getSeedValues(String itemId) {
        return seedValues.getOrDefault(itemId, Map.of());
    }

    public int getSeedCount() {
        return seedValues.size();
    }

    public Map<ElementType, Long> getBuildCostByItemId(String itemId) {
        ElementMappingConfig config = findConfigByItemId(itemId);
        if (config != null) return config.buildCost();
        // Try block ID match too
        for (ElementMappingConfig c : registry.getAll().values()) {
            if (itemId.equals(c.blockId())) return c.buildCost();
        }
        return Map.of();
    }

    public Collection<ElementMappingConfig> getAllConfigs() {
        return registry.getAll().values();
    }
}
