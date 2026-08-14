package com.wsteam.wandscape.element.internal;

import java.util.Map;

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
    public boolean hasElementMapping(String blockOrItemId) {
        return mappingLoader.hasMapping(blockOrItemId);
    }

    @Override
    public boolean isDisabled(String blockOrItemId) {
        return mappingLoader.isDisabled(blockOrItemId);
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
    public Map<ElementType, Long> getBuildCost(BlockState block) {
        return mappingLoader.getBuildCost(block);
    }

    @Override
    public Map<ElementType, Long> getBuildCost(ItemStack stack) {
        return mappingLoader.getItemBuildCost(stack.getItem());
    }
}
