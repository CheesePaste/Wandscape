package com.wsteam.wandscape.element.internal;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.shared.api.ElementApi;
import com.wsteam.wandscape.shared.data.ElementType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
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

    @Override
    public String elementItemId(ElementType type) {
        return ResourceLocation.fromNamespaceAndPath(Wandscape.MODID, "element_" + type.getId()).toString();
    }
}
