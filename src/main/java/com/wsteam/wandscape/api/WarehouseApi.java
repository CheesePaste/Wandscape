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

    /**
     * Add element amount to a colony's warehouse.
     *
     * @return true when the warehouse bank is ready and the amount was added;
     *         false when the bank is not initialized (colony has no warehouse /
     *         early startup) and nothing was added.
     */
    boolean addElement(UUID colonyId, ElementType type, long amount);

    /**
     * Add several element types in one batch call.
     *
     * @return true when the bank is ready and all amounts were added; false when it
     *         is not initialized and nothing was added.
     */
    boolean addAllElements(UUID colonyId, Map<ElementType, Long> amounts);

    long getItemCount(UUID colonyId, ItemKey key);
    Map<ItemKey, Long> getItemSnapshot(UUID colonyId);
    long extractItem(UUID colonyId, ItemKey key, long count, Container target);

    /**
     * Insert item stacks into a colony's warehouse.
     *
     * @return true when the bank is ready and the stacks were inserted; false when
     *         it is not initialized and nothing was inserted.
     */
    boolean insertItems(UUID colonyId, List<ItemStack> items);

    // ── 可调平衡值（委托 BalanceValues；运行时生效，不追溯已生成实体）──

    int getTransportTicksPerBlockOnRoad();
    void setTransportTicksPerBlockOnRoad(int v);
    int getTransportTicksPerBlockOffRoad();
    void setTransportTicksPerBlockOffRoad(int v);
}
