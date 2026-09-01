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
}
