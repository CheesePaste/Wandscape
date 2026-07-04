package com.wsteam.wandscape.shared.api;

import java.util.Map;

import com.wsteam.wandscape.shared.data.ElementType;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
public interface ElementApi {
    ElementType fromId(String id);

    /**
     * Check if the given block/item ID has a registered element mapping.
     * Blocks without element mappings are considered "free" materials
     * and should not be requested from the warehouse.
     */
    boolean hasElementMapping(String blockOrItemId);

    Map<ElementType, Long> getBuildCost(BlockState block);
    Map<ElementType, Long> getDecomposeYield(BlockState block);
    boolean isDecomposable(BlockState block);

    Map<ElementType, Long> getBuildCost(ItemStack stack);
    Map<ElementType, Long> getDecomposeYield(ItemStack stack);
    boolean isDecomposable(ItemStack stack);
}
