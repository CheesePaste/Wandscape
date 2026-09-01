package com.wsteam.wandscape.element.internal;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wsteam.wandscape.dataconfig.internal.WandscapeDataLoader;
import com.wsteam.wandscape.shared.data.ElementType;
import com.wsteam.wandscape.shared.registry.WandscapeDataRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
public class ElementMappingLoader {
    private static final String TAG = "ElementMappingLoader";
    private static final String CATEGORY = "element_mappings";

    private final WandscapeDataRegistry<ElementMappingConfig> registry;

    /** Seed values loaded from element_seeds.json — base-material values, kept for reporting/count. */
    private final Map<String, Map<ElementType, Long>> seedValues = new LinkedHashMap<>();

    public ElementMappingLoader(WandscapeDataLoader dataLoader) {
        this.registry = dataLoader.register(CATEGORY, ElementMappingConfig::fromJson);
    }

    public Map<ElementType, Long> getBuildCost(BlockState state) {
        ElementMappingConfig config = findConfig(state);
        return config != null && !config.disabled() ? config.buildCost() : Map.of();
    }

    /** Find a representative block ID for an element type (for visual transport). */
    @javax.annotation.Nullable
    public String getRepresentativeBlock(ElementType element) {
        for (ElementMappingConfig config : getAllConfigs()) {
            if (config.buildCost().containsKey(element)) {
                return config.blockId();
            }
        }
        return null;
    }

    public Map<ElementType, Long> getItemBuildCost(Item item) {
        ElementMappingConfig config = findConfigByItem(item);
        return config != null && !config.disabled() ? config.buildCost() : Map.of();
    }

    /**
     * Canonical element value of an item — its build_cost.
     * Shared by shop sale profit and workstation decomposition.
     */
    public Map<ElementType, Long> getItemElementValue(String itemId) {
        ResourceLocation rl = ResourceLocation.tryParse(itemId);
        if (rl == null) return Map.of();
        Item item = BuiltInRegistries.ITEM.get(rl);
        return getItemBuildCost(item);
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
        ElementMappingConfig config = findConfigByItemId(blockOrItemId);
        return config != null && !config.disabled();
    }

    /** True when an element mapping exists and is explicitly disabled via {@code "disabled": true}. */
    public boolean isDisabled(String blockOrItemId) {
        ElementMappingConfig config = findConfigByItemId(blockOrItemId);
        return config != null && config.disabled();
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

    public int getSeedCount() {
        return seedValues.size();
    }

    public Map<ElementType, Long> getBuildCostByItemId(String itemId) {
        ElementMappingConfig config = findConfigByItemId(itemId);
        if (config != null && !config.disabled()) return config.buildCost();
        // Try block ID match too
        for (ElementMappingConfig c : getAllConfigs()) {
            if (itemId.equals(c.blockId())) return c.buildCost();
        }
        return Map.of();
    }

    /**
     * Active (non-disabled) configs only. Disabled mappings are excluded from the element
     * economy: no synthesize recipes, no decompose, no representative transport, no audit
     * coverage. Lookups that need to distinguish "disabled" from "absent" use the raw registry.
     */
    public Collection<ElementMappingConfig> getAllConfigs() {
        return registry.getAll().values().stream()
                .filter(c -> !c.disabled())
                .toList();
    }
}
