package com.wsteam.wandscape.element.internal;

import java.util.Map;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.dataconfig.internal.WandscapeDataLoader;
import com.wsteam.wandscape.shared.data.ElementType;
import com.wsteam.wandscape.shared.registry.WandscapeDataRegistry;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

public class ElementMappingLoader {
    private static final Logger LOGGER = LogUtils.getLogger();
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

    public boolean isDecomposable(BlockState state) {
        ElementMappingConfig config = findConfig(state);
        return config != null && config.decomposable();
    }

    private ElementMappingConfig findConfig(BlockState state) {
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        String id = key.toString();
        for (ElementMappingConfig config : registry.getAll().values()) {
            if (config.blockId().equals(id)) return config;
        }
        return null;
    }
}
