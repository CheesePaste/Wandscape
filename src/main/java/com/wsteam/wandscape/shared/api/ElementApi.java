package com.wsteam.wandscape.shared.api;

import java.util.List;
import java.util.Map;

import com.wsteam.wandscape.shared.data.ElementType;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
public interface ElementApi {
    ElementType fromId(String id);
    int getTier(ElementType type);
    List<ElementType> getByTier(int tier);

    Map<ElementType, Long> getBuildCost(BlockState block);
    Map<ElementType, Long> getDecomposeYield(BlockState block);
    boolean isDecomposable(BlockState block);

    Map<ElementType, Long> getBuildCost(ItemStack stack);
    Map<ElementType, Long> getDecomposeYield(ItemStack stack);
    boolean isDecomposable(ItemStack stack);
}
