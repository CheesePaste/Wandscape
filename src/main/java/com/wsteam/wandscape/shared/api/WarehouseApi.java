package com.wsteam.wandscape.shared.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import com.wsteam.wandscape.shared.data.ElementType;
import com.wsteam.wandscape.shared.data.ItemKey;

public interface WarehouseApi {
    long getElement(UUID colonyId, ElementType type);
    Map<ElementType, Long> getAllElements(UUID colonyId);
    boolean consumeElement(UUID colonyId, ElementType type, long amount);
    void addElement(UUID colonyId, ElementType type, long amount);
    long getItemCount(UUID colonyId, ItemKey key);
    boolean extractItem(UUID colonyId, ItemKey key, long count, Container target);
    void insertItems(UUID colonyId, List<ItemStack> items);
}
