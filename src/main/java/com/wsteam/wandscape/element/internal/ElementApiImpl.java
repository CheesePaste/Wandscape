package com.wsteam.wandscape.element.internal;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.wsteam.wandscape.building.client.ShopScreen;
import com.wsteam.wandscape.shared.api.ElementApi;
import com.wsteam.wandscape.shared.data.ElementType;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
public class ElementApiImpl implements ElementApi {

    private final ElementMappingLoader mappingLoader;

    public ElementApiImpl(ElementMappingLoader mappingLoader) {
        this.mappingLoader = mappingLoader;
    }

    @Override
    public ElementType fromId(String id) {
        try {
            return ElementType.valueOf(id.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public int getTier(ElementType type) {
        return type.getTier();
    }

    @Override
    public List<ElementType> getByTier(int tier) {
        return Arrays.stream(ElementType.values())
            .filter(e -> e.getTier() == tier)
            .toList();
    }

    @Override
    public Map<ElementType, Long> getBuildCost(BlockState block) {
        return mappingLoader.getBuildCost(block);
    }

    @Override
    public Map<ElementType, Long> getDecomposeYield(BlockState block) {
        return mappingLoader.getDecomposeYield(block);
    }

    @Override
    public boolean isDecomposable(BlockState block) {
        return mappingLoader.isDecomposable(block);
    }

    @Override
    public Map<ElementType, Long> getBuildCost(ItemStack stack) {
        return mappingLoader.getItemBuildCost(stack.getItem());
    }

    @Override
    public Map<ElementType, Long> getDecomposeYield(ItemStack stack) {
        return mappingLoader.getItemDecomposeYield(stack.getItem());
    }

    @Override
    public boolean isDecomposable(ItemStack stack) {
        var yield = mappingLoader.getItemDecomposeYield(stack.getItem());
        return !yield.isEmpty();
    }
}
