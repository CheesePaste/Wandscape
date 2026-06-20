package com.wsteam.wandscape.warehouse;

import java.util.Collections;
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
import com.wsteam.wandscape.shared.registry.WandscapeApis;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Container;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.common.NeoForge;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Implements both {@link WarehouseApi} and {@link ColonyResourceAccess}.
 *
 * <p>All operations delegate to the colony's {@link WarehouseBE}, found via
 * {@code BuildingApi.getColonyBuildings()} filtering by category=storage.
 * ResourceId ↔ ItemKey mapping uses a simple static table for MVP.
 */
public class WarehouseManager implements WarehouseApi, ColonyResourceAccess {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String CATEGORY_STORAGE = "storage";

    // ── Event throttle: avoid flooding ResourceInsufficientEvent ──
    private final Map<ResourceId, Long> lastShortageNotify = new java.util.HashMap<>();
    private static final long SHORTAGE_NOTIFY_COOLDOWN_MS = 10_000; // 10 seconds per resource

    // ── MVP: Element → block item mapping ──
    // Full version will use ElementMappingLoader for reverse lookup.
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
    //  WarehouseApi — element operations (derived from items)
    // ════════════════════════════════════════════════════════════

    @Override
    public long getElement(UUID colonyId, ElementType type) {
        ItemKey key = elementToItemKey(type);
        if (key == null) return 0;
        WarehouseBE be = findWarehouse(colonyId);
        return be != null ? be.count(key) : 0;
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
        WarehouseBE be = findWarehouse(colonyId);
        if (be == null) return false;
        ItemKey key = elementToItemKey(type);
        return key != null && be.consume(key, amount);
    }

    @Override
    public void addElement(UUID colonyId, ElementType type, long amount) {
        WarehouseBE be = findWarehouse(colonyId);
        if (be == null) return;
        ItemKey key = elementToItemKey(type);
        if (key != null) be.add(key, amount);
    }

    // ════════════════════════════════════════════════════════════
    //  WarehouseApi — item operations
    // ════════════════════════════════════════════════════════════

    @Override
    public long getItemCount(UUID colonyId, ItemKey key) {
        WarehouseBE be = findWarehouse(colonyId);
        return be != null ? be.count(key) : 0;
    }

    @Override
    public boolean extractItem(UUID colonyId, ItemKey key, long count, Container target) {
        WarehouseBE be = findWarehouse(colonyId);
        if (be == null || count <= 0) return false;
        if (be.available(key) < count) return false;

        int take = (int) Math.min(count, 64);
        ItemStack stack = toItemStack(key, take);
        if (stack.isEmpty()) return false;

        // Try to fit into target using vanilla Container contract
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
        be.consume(key, taken);
        if (remainder > 0) be.add(key, remainder); // refund
        return true;
    }

    @Override
    public void insertItems(UUID colonyId, List<ItemStack> stacks) {
        WarehouseBE be = findWarehouse(colonyId);
        if (be == null) return;
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            var rl = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (rl == null) continue;
            CompoundTag nbt = extractNbt(stack);
            ItemKey key = ItemKey.of(rl.toString(), nbt);
            be.add(key, stack.getCount());
        }
        LOGGER.debug("Warehouse insert: {} stacks", stacks.size());
    }

    // ════════════════════════════════════════════════════════════
    //  ColonyResourceAccess
    // ════════════════════════════════════════════════════════════

    @Override
    public boolean hasEnough(ResourceId resource, int amount) {
        WarehouseBE be = findAnyWarehouse();
        if (be == null) return false;
        ItemKey key = resourceToItemKey(resource);
        if (key == null) return false;
        long avail = be.available(key);
        if (avail >= amount) return true;

        // Fire event with cooldown throttle
        long now = System.currentTimeMillis();
        long last = lastShortageNotify.getOrDefault(resource, 0L);
        if (now - last >= SHORTAGE_NOTIFY_COOLDOWN_MS) {
            lastShortageNotify.put(resource, now);
            NeoForge.EVENT_BUS.post(new ResourceInsufficientEvent(resource, amount, (int) avail));
        }
        return false;
    }

    @Override
    public boolean reserve(ResourceId resource, int amount) {
        WarehouseBE be = findAnyWarehouse();
        if (be == null) return false;
        ItemKey key = resourceToItemKey(resource);
        return key != null && be.reserve(key, amount);
    }

    @Override
    public boolean commit(ResourceId resource, int amount) {
        WarehouseBE be = findAnyWarehouse();
        if (be == null) return false;
        ItemKey key = resourceToItemKey(resource);
        return key != null && be.commit(key, amount);
    }

    @Override
    public void release(ResourceId resource, int amount) {
        WarehouseBE be = findAnyWarehouse();
        if (be == null) return;
        ItemKey key = resourceToItemKey(resource);
        if (key != null) be.release(key, amount);
    }

    @Override
    public int available(ResourceId resource) {
        WarehouseBE be = findAnyWarehouse();
        if (be == null) return 0;
        ItemKey key = resourceToItemKey(resource);
        return key != null ? (int) be.available(key) : 0;
    }

    // ════════════════════════════════════════════════════════════
    //  Lookup helpers
    // ════════════════════════════════════════════════════════════

    @Nullable
    private WarehouseBE findWarehouse(@Nullable UUID colonyId) {
        var api = WandscapeApis.getBuildingApi();
        for (var bd : api.getColonyBuildings(colonyId)) {
            if (CATEGORY_STORAGE.equals(bd.getCategory()) && !bd.isShutdown()) {
                var be = getBeAt(bd.getPosition());
                if (be instanceof WarehouseBE wbe) return wbe;
            }
        }
        return null;
    }

    @Nullable
    private WarehouseBE findAnyWarehouse() {
        return findWarehouse(null);
    }

    @Nullable
    private net.minecraft.world.level.block.entity.BlockEntity getBeAt(
            net.minecraft.core.BlockPos pos) {
        var level = getServerLevel();
        if (level == null) return null;
        return level.getBlockEntity(pos);
    }

    @Nullable
    private static net.minecraft.world.level.Level getServerLevel() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return null;
        return server.overworld();
    }

    // ════════════════════════════════════════════════════════════
    //  ID mapping
    // ════════════════════════════════════════════════════════════

    @Nullable
    private ItemKey resourceToItemKey(ResourceId resource) {
        String id = resource.id();
        if (!id.contains(":")) {
            // Try ElementType match first
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

    /** Extract NBT from an ItemStack using 1.21.1 DataComponents API. */
    @Nullable
    private static CompoundTag extractNbt(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null ? data.copyTag() : null;
    }
}
