package com.wsteam.wandscape.warehouse;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.core.boundary.ColonyResourceAccess;
import com.wsteam.wandscape.core.types.ResourceId;
import com.wsteam.wandscape.shared.api.WarehouseApi;
import com.wsteam.wandscape.shared.data.ElementType;
import com.wsteam.wandscape.shared.data.ItemKey;
import com.wsteam.wandscape.shared.event.ResourceInsufficientEvent;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Implements both {@link WarehouseApi} and {@link ColonyResourceAccess}.
 *
 * <p>All item storage is in {@link ColonyItemBank} (Level SavedData).
 * Warehouse blocks are terminals — destruction does not lose items.
 */
public class WarehouseManager implements WarehouseApi, ColonyResourceAccess {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final Map<ResourceId, Long> lastShortageNotify = new java.util.HashMap<>();
    private static final long SHORTAGE_NOTIFY_COOLDOWN_MS = 10_000;

    private static final Map<ElementType, String> ELEMENT_TO_BLOCK = Map.ofEntries(
            Map.entry(ElementType.WOOD, "minecraft:oak_log"),
            Map.entry(ElementType.EARTH, "minecraft:dirt"),
            Map.entry(ElementType.WATER, "minecraft:water_bucket"),
            Map.entry(ElementType.FIRE, "minecraft:blaze_powder"),
            Map.entry(ElementType.WIND, "minecraft:feather"),
            Map.entry(ElementType.IRON, "minecraft:iron_ingot"),
            Map.entry(ElementType.GOLD, "minecraft:gold_ingot"),
            Map.entry(ElementType.DIAMOND, "minecraft:diamond"),
            Map.entry(ElementType.ENDER, "minecraft:ender_pearl")
    );

    // ════════════════════════════════════════════════════════════
    //  WarehouseApi — element operations
    // ════════════════════════════════════════════════════════════

    @Override
    public long getElement(UUID colonyId, ElementType type) {
        ItemKey key = elementToItemKey(type);
        if (key == null) return 0;
        ColonyItemBank bank = getBank();
        return bank != null ? bank.count(colonyId, key) : 0;
    }

    @Override
    public Map<ElementType, Long> getAllElements(UUID colonyId) {
        Map<ElementType, Long> result = new java.util.LinkedHashMap<>();
        for (ElementType type : ElementType.values()) {
            long count = getElement(colonyId, type);
            if (count > 0) result.put(type, count);
        }
        return result;
    }

    @Override
    public boolean consumeElement(UUID colonyId, ElementType type, long amount) {
        ItemKey key = elementToItemKey(type);
        if (key == null) return false;
        ColonyItemBank bank = getBank();
        return bank != null && bank.consume(colonyId, key, amount);
    }

    @Override
    public void addElement(UUID colonyId, ElementType type, long amount) {
        ItemKey key = elementToItemKey(type);
        if (key == null) return;
        ColonyItemBank bank = getBank();
        if (bank != null) bank.add(colonyId, key, amount);
    }

    // ════════════════════════════════════════════════════════════
    //  WarehouseApi — item operations
    // ════════════════════════════════════════════════════════════

    @Override
    public long getItemCount(UUID colonyId, ItemKey key) {
        ColonyItemBank bank = getBank();
        return bank != null ? bank.count(colonyId, key) : 0;
    }

    @Override
    public boolean extractItem(UUID colonyId, ItemKey key, long count, Container target) {
        ColonyItemBank bank = getBank();
        if (bank == null || count <= 0) return false;
        if (bank.available(colonyId, key) < count) return false;

        int take = (int) Math.min(count, 64);
        ItemStack stack = toItemStack(key, take);
        if (stack.isEmpty()) return false;

        int remainder = take;
        for (int slot = 0; slot < target.getContainerSize() && remainder > 0; slot++) {
            ItemStack existing = target.getItem(slot);
            if (existing.isEmpty()) {
                ItemStack toPlace = stack.copyWithCount(remainder);
                int maxStack = Math.min(target.getMaxStackSize(), toPlace.getMaxStackSize());
                toPlace.setCount(Math.min(remainder, maxStack));
                target.setItem(slot, toPlace);
                remainder -= toPlace.getCount();
            } else if (ItemStack.isSameItemSameComponents(existing, stack)) {
                int space = Math.min(target.getMaxStackSize(), existing.getMaxStackSize()) - existing.getCount();
                int add = Math.min(remainder, space);
                if (add > 0) {
                    existing.grow(add);
                    remainder -= add;
                }
            }
        }
        int taken = take - remainder;
        if (taken <= 0) return false;
        bank.consume(colonyId, key, taken);
        if (remainder > 0) bank.add(colonyId, key, remainder);
        return true;
    }

    @Override
    public void insertItems(UUID colonyId, List<ItemStack> stacks) {
        ColonyItemBank bank = getBank();
        if (bank == null) return;
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            var rl = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (rl == null) continue;
            CompoundTag nbt = extractNbt(stack);
            ItemKey key = ItemKey.of(rl.toString(), nbt);
            bank.add(colonyId, key, stack.getCount());
        }
    }

    // ════════════════════════════════════════════════════════════
    //  ColonyResourceAccess
    // ════════════════════════════════════════════════════════════

    @Override
    public boolean hasEnough(ResourceId resource, int amount) {
        ColonyItemBank bank = getBank();
        if (bank == null) return false;
        ItemKey key = resourceToItemKey(resource);
        if (key == null) return false;
        // Search all colonies for this resource
        for (UUID colonyId : bank.getColonyIds()) {
            if (bank.available(colonyId, key) >= amount) return true;
        }

        long now = System.currentTimeMillis();
        long last = lastShortageNotify.getOrDefault(resource, 0L);
        if (now - last >= SHORTAGE_NOTIFY_COOLDOWN_MS) {
            lastShortageNotify.put(resource, now);
            long avail = 0;
            for (UUID colonyId : bank.getColonyIds()) {
                avail += bank.available(colonyId, key);
            }
            NeoForge.EVENT_BUS.post(new ResourceInsufficientEvent(resource, amount, (int) avail));
        }
        return false;
    }

    @Override
    public boolean reserve(ResourceId resource, int amount) {
        ColonyItemBank bank = getBank();
        if (bank == null) return false;
        ItemKey key = resourceToItemKey(resource);
        if (key == null) return false;
        for (UUID colonyId : bank.getColonyIds()) {
            if (bank.available(colonyId, key) >= amount) {
                return bank.reserve(colonyId, key, amount);
            }
        }
        return false;
    }

    @Override
    public boolean commit(ResourceId resource, int amount) {
        ColonyItemBank bank = getBank();
        if (bank == null) return false;
        ItemKey key = resourceToItemKey(resource);
        if (key == null) return false;
        for (UUID colonyId : bank.getColonyIds()) {
            if (bank.commit(colonyId, key, amount)) return true;
        }
        return false;
    }

    @Override
    public void release(ResourceId resource, int amount) {
        ColonyItemBank bank = getBank();
        if (bank == null) return;
        ItemKey key = resourceToItemKey(resource);
        if (key == null) return;
        for (UUID colonyId : bank.getColonyIds()) {
            bank.release(colonyId, key, amount);
        }
    }

    @Override
    public int available(ResourceId resource) {
        ColonyItemBank bank = getBank();
        if (bank == null) return 0;
        ItemKey key = resourceToItemKey(resource);
        if (key == null) return 0;
        long total = 0;
        for (UUID colonyId : bank.getColonyIds()) {
            total += bank.available(colonyId, key);
        }
        return (int) total;
    }

    @Override
    public void addResource(ResourceId resource, int amount) {
        ColonyItemBank bank = getBank();
        if (bank == null) {
            LOGGER.warn("addResource({}, {}): ColonyItemBank not available", resource, amount);
            return;
        }
        ItemKey key = resourceToItemKey(resource);
        if (key == null) {
            LOGGER.warn("addResource({}, {}): cannot map resource to ItemKey", resource, amount);
            return;
        }
        // Add to the first colony that has a warehouse building registered
        var api = com.wsteam.wandscape.shared.registry.WandscapeApis.getBuildingApi();
        for (var bd : api.getColonyBuildings(null)) {
            if ("storage".equals(bd.getCategory()) && !bd.isShutdown()) {
                UUID colonyId = bd.getColonyId();
                if (colonyId == null) colonyId = new UUID(0, 0); // default colony
                bank.add(colonyId, key, amount);
                LOGGER.info("addResource: {} x{} → colony {} warehouse ({} total)",
                        resource.id(), amount, colonyId, bank.count(colonyId, key));
                return;
            }
        }
        LOGGER.warn("addResource({}, {}): no active storage building found", resource, amount);
    }

    // ════════════════════════════════════════════════════════════
    //  Bank access
    // ════════════════════════════════════════════════════════════

    @Nullable
    private ColonyItemBank getBank() {
        Level level = getServerLevel();
        return level != null ? ColonyItemBank.get(level) : null;
    }

    @Nullable
    private static Level getServerLevel() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server != null ? server.overworld() : null;
    }

    // ════════════════════════════════════════════════════════════
    //  ID mapping
    // ════════════════════════════════════════════════════════════

    @Nullable
    private ItemKey resourceToItemKey(ResourceId resource) {
        String id = resource.id();
        if (!id.contains(":")) {
            try {
                ElementType type = ElementType.valueOf(id.toUpperCase());
                return elementToItemKey(type);
            } catch (IllegalArgumentException ignored) {
            }
            return ItemKey.of("minecraft:" + id, null);
        }
        return ItemKey.of(id, null);
    }

    @Nullable
    private ItemKey elementToItemKey(ElementType type) {
        String blockId = ELEMENT_TO_BLOCK.get(type);
        return blockId != null ? ItemKey.of(blockId, null) : null;
    }

    @Nullable
    private ItemStack toItemStack(ItemKey key, int count) {
        var item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .get(net.minecraft.resources.ResourceLocation.tryParse(key.itemId()));
        if (item == null) return ItemStack.EMPTY;
        ItemStack stack = new ItemStack(item, count);
        if (key.nbt() != null && !key.nbt().isEmpty()) {
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(key.nbt().copy()));
        }
        return stack;
    }

    @Nullable
    private static CompoundTag extractNbt(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null ? data.copyTag() : null;
    }
}
