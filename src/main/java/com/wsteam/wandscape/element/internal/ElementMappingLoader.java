package com.wsteam.wandscape.element.internal;

import java.util.Collection;
import java.util.Map;

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
            if (itemId.equals(config.itemId())) return config;
        }
        return null;
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
