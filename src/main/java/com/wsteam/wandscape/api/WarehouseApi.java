package com.wsteam.wandscape.api;
import com.wsteam.wandscape.content.task.ecs.World;

import com.wsteam.wandscape.content.element.data.ElementType;
import com.wsteam.wandscape.foundation.util.ItemKey;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.UUID;
public interface WarehouseApi {
    long getElement(UUID colonyId, ElementType type);
    Map<ElementType, Long> getAllElements(UUID colonyId);
    boolean consumeElement(UUID colonyId, ElementType type, long amount);
    void addElement(UUID colonyId, ElementType type, long amount);
    long getItemCount(UUID colonyId, ItemKey key);
    Map<ItemKey, Long> getItemSnapshot(UUID colonyId);
    long extractItem(UUID colonyId, ItemKey key, long count, Container target);
    void insertItems(UUID colonyId, List<ItemStack> items);
}
