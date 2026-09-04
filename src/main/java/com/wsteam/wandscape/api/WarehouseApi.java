package com.wsteam.wandscape.api;
import com.wsteam.wandscape.content.task.ecs.World;

import com.wsteam.wandscape.content.element.data.ElementType;
import com.wsteam.wandscape.foundation.util.ItemKey;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Public API for a colony's warehouse (items + elements).
 *
 * <p>Notable events broadcast to {@code NeoForge.EVENT_BUS} (subscribe directly):
 * <ul>
 *   <li>{@link com.wsteam.wandscape.content.warehouse.event.WarehouseItemChangedEvent}
 *       — 物品入库/出仓/消耗，携带 colonyId、itemKey、newCount、delta，供附属做增量同步；</li>
 *   <li>{@link com.wsteam.wandscape.content.warehouse.event.WarehouseElementChangedEvent}
 *       — 元素充入/消耗，携带 colonyId、elementType、newAmount、delta。</li>
 * </ul>
 */
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
     * <p>Capacity-gated: each item counts 1 against the colony warehouse capacity
     * (unstackable items included). When the whole batch would exceed the remaining
     * capacity, nothing is inserted.
     *
     * @return true when the bank is ready and the whole batch was inserted; false
     *         when the bank is not initialized, or the warehouse lacks capacity for
     *         the batch (batch left untouched).
     */
    boolean insertItems(UUID colonyId, List<ItemStack> items);

    // ── 物品/元素 增删清（colony 级；管理/整合包用，给最大自由度）──

    /**
     * 向殖民地仓库追加一件物品（<b>不设容量门槛</b>——这是给管理/整合包直接授权物品的语义；
     * 正常运行的生产/建造产出请走 {@link #insertItems}(容量门控)，task 资源供给走 ColonyResourceAccess）。
     *
     * @return true 当账本就绪且已入账；false 账本未初始化
     */
    boolean addItem(UUID colonyId, ItemKey key, long amount);

    /**
     * 从殖民地仓库移除一件物品（消耗）；不足时不移除任何并返回 false。
     *
     * @return true 已足量移除；false 不足或账本未就绪
     */
    boolean removeItem(UUID colonyId, ItemKey key, long amount);

    /** 清空殖民地仓库的全部物品。返回 true 当账本就绪。 */
    boolean clearItems(UUID colonyId);

    /** 清空殖民地仓库的全部元素。返回 true 当账本就绪。 */
    boolean clearElements(UUID colonyId);

    /** 清空殖民地仓库的全部物品与元素（不可逆）。返回 true 当账本就绪。 */
    boolean clearAll(UUID colonyId);

    /** 殖民地物品容量上限（0 = 机制关闭/不限）。 */
    long getItemCapacity(UUID colonyId);

    /** 殖民地当前已占用物品容量。 */
    long getUsedItemCapacity(UUID colonyId);

    // ── 可调平衡值（委托 BalanceValues；运行时生效，不追溯已生成实体）──

    int getTransportTicksPerBlockOnRoad();
    void setTransportTicksPerBlockOnRoad(int v);
    int getTransportTicksPerBlockOffRoad();
    void setTransportTicksPerBlockOffRoad(int v);

    // ── 未实现（重设计阶段声明，见 @Unimplemented）──

    /**
     * 跨殖民地原子转账元素（A 扣除 + B 到账，失败整体回滚）。
     *
     * @return 全部成功 true；B 未就绪等失败 false 且 A 不动
     */
    @Unimplemented("重设计阶段——待接入跨殖民地原子转账")
    default boolean transferElements(UUID fromColonyId, UUID toColonyId, Map<ElementType, Long> amounts) {
        throw new UnsupportedOperationException("WarehouseApi.transferElements not yet implemented");
    }
}
