package com.wsteam.wandscape.api;
import com.wsteam.wandscape.content.task.ecs.World;

import com.wsteam.wandscape.content.element.data.ElementType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
public interface ElementApi {
    ElementType fromId(String id);

    /**
     * Check if the given block/item ID has a registered element mapping.
     * Blocks without element mappings are considered "free" materials
     * and should not be requested from the warehouse.
     */
    boolean hasElementMapping(String blockOrItemId);

    /**
     * True when an element mapping exists for the block/item but is explicitly
     * disabled via {@code "disabled": true} — excluded from the element economy.
     * Callers that must not silently treat a disabled block as a free material
     * (e.g. building placement) should refuse on this.
     */
    boolean isDisabled(String blockOrItemId);

    Map<ElementType, Long> getBuildCost(BlockState block);
    Map<ElementType, Long> getBuildCost(ItemStack stack);

    /**
     * Registry id of the item token representing this element (e.g.
     * {@code wandscape:element_fire}), used by JEI/recipe display and the tourist
     * bubble. Returns null if the element has no item token.
     */
    String elementItemId(ElementType type);

    /**
     * Programmatically register an element mapping for a block/item id (overrides JSON).
     * Takes effect immediately. No element-balance event is fired — this is a global
     * mapping, not a per-colony balance change. {@code buildCost} may be empty to
     * register a cost-free mapping.
     */
    void registerMapping(String blockOrItemId, Map<ElementType, Long> buildCost);

    /** Undo a {@link #registerMapping} override, falling back to the JSON registry. */
    void unregisterMapping(String blockOrItemId);

    // ── 未实现（重设计阶段声明，见 @Unimplemented）──

    /** 全部已注册映射快照（含 JSON + 运行时覆盖层；disabled 以空成本表示）。 */
    @Unimplemented("重设计阶段——待接入 ElementMappingLoader.getAllConfigs")
    default Map<String, Map<ElementType, Long>> getAllMappings() {
        throw new UnsupportedOperationException("ElementApi.getAllMappings not yet implemented");
    }

    /** 是否已有元素映射（用方块状态对象查询；disabled 返回 false，见 {@link #isDisabled}）。 */
    @Unimplemented("重设计阶段——待接入按 BlockState 查询")
    default boolean hasElementMapping(BlockState blockState) {
        throw new UnsupportedOperationException("ElementApi.hasElementMapping(BlockState) not yet implemented");
    }

    /** 对某 id 单元素成本做增量调整（非整表覆盖；叠加到当前值之上）。 */
    @Unimplemented("重设计阶段——待接入增量成本调整")
    default void adjustCost(String blockOrItemId, ElementType type, long delta) {
        throw new UnsupportedOperationException("ElementApi.adjustCost not yet implemented");
    }
}
