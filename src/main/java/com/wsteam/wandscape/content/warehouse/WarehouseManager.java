package com.wsteam.wandscape.content.warehouse;
import com.wsteam.wandscape.impl.EngineBootstrap;
import com.wsteam.wandscape.content.task.ecs.World;

import com.wsteam.wandscape.content.task.boundary.ColonyResourceAccess;
import com.wsteam.wandscape.content.task.boundary.ResourceAddedListener;
import com.wsteam.wandscape.content.task.types.ResourceId;
import com.wsteam.wandscape.api.WarehouseApi;
import com.wsteam.wandscape.foundation.util.BalanceValues;
import com.wsteam.wandscape.content.element.data.ElementType;
import com.wsteam.wandscape.foundation.util.ItemKey;
import com.wsteam.wandscape.content.warehouse.event.ResourceInsufficientEvent;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.log.LogCategory;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Implements both {@link WarehouseApi} and {@link ColonyResourceAccess}.
 *
 * <p>Items and elements are stored separately in {@link ColonyItemBank}.
 * Warehouse blocks are terminals — destruction does not lose items.
 */
public class WarehouseManager implements WarehouseApi, ColonyResourceAccess {

    private static final String TAG = "WarehouseManager";

    /** Callback invoked when resources are added to the warehouse. */
    @Nullable
    private ResourceAddedListener resourceAddedListener;

    private final Map<ResourceId, Long> lastShortageNotify = new java.util.HashMap<>();
    private static final long SHORTAGE_NOTIFY_COOLDOWN_MS = 10_000;

    /** Set by EngineBootstrap. */
    public void setResourceAddedListener(@Nullable ResourceAddedListener listener) {
        this.resourceAddedListener = listener;
    }

    // ════════════════════════════════════════════════════════════
    //  WarehouseApi — element operations
    // ════════════════════════════════════════════════════════════

    @Override
    public long getElement(UUID colonyId, ElementType type) {
        ColonyItemBank bank = getBank();
        return bank != null ? bank.countElement(colonyId, type) : 0;
    }

    @Override
    public Map<ElementType, Long> getAllElements(UUID colonyId) {
        ColonyItemBank bank = getBank();
        return bank != null ? bank.getElementSnapshot(colonyId) : Map.of();
    }

    @Override
    public boolean consumeElement(UUID colonyId, ElementType type, long amount) {
        ColonyItemBank bank = getBank();
        return bank != null && bank.consumeElement(colonyId, type, amount);
    }

    @Override
    public boolean addElement(UUID colonyId, ElementType type, long amount) {
        ColonyItemBank bank = getBank();
        if (bank == null) return false;
        bank.addElement(colonyId, type, amount);
        return true;
    }

    @Override
    public boolean addAllElements(UUID colonyId, Map<ElementType, Long> amounts) {
        ColonyItemBank bank = getBank();
        if (bank == null) return false;
        for (var e : amounts.entrySet()) {
            if (e.getValue() == null || e.getValue() <= 0) continue;
            bank.addElement(colonyId, e.getKey(), e.getValue());
        }
        return true;
    }

    // ── 可调平衡值（委托 BalanceValues；运行时生效，不追溯已生成实体）──
    @Override public int getTransportTicksPerBlockOnRoad() { return BalanceValues.transportTicksPerBlockOnRoad(); }
    @Override public void setTransportTicksPerBlockOnRoad(int v) { BalanceValues.setTransportTicksPerBlockOnRoad(v); }
    @Override public int getTransportTicksPerBlockOffRoad() { return BalanceValues.transportTicksPerBlockOffRoad(); }
    @Override public void setTransportTicksPerBlockOffRoad(int v) { BalanceValues.setTransportTicksPerBlockOffRoad(v); }

    // ════════════════════════════════════════════════════════════
    //  WarehouseApi — item operations
    // ════════════════════════════════════════════════════════════

    @Override
    public long getItemCount(UUID colonyId, ItemKey key) {
        ColonyItemBank bank = getBank();
        return bank != null ? bank.count(colonyId, key) : 0;
    }

    @Override
    public Map<ItemKey, Long> getItemSnapshot(UUID colonyId) {
        ColonyItemBank bank = getBank();
        return bank != null ? bank.getSnapshot(colonyId) : Map.of();
    }

    @Override
    public long extractItem(UUID colonyId, ItemKey key, long count, Container target) {
        ColonyItemBank bank = getBank();
        if (bank == null || count <= 0) return 0;
        long avail = bank.available(colonyId, key);
        if (avail <= 0) return 0;

        long toExtract = Math.min(count, avail);
        long remaining = toExtract;

        ItemStack sample = toItemStack(key, 1);
        if (sample.isEmpty()) return 0;
        int maxStackSize = sample.getMaxStackSize();

        for (int slot = 0; slot < target.getContainerSize() && remaining > 0; slot++) {
            ItemStack existing = target.getItem(slot);
            if (existing.isEmpty()) {
                int placeCount = (int) Math.min(remaining, Math.min(target.getMaxStackSize(), maxStackSize));
                ItemStack toPlace = sample.copyWithCount(placeCount);
                target.setItem(slot, toPlace);
                remaining -= placeCount;
            } else if (ItemStack.isSameItemSameComponents(existing, sample)) {
                int space = Math.min(target.getMaxStackSize(), existing.getMaxStackSize()) - existing.getCount();
                int add = (int) Math.min(remaining, space);
                if (add > 0) {
                    existing.grow(add);
                    remaining -= add;
                }
            }
        }

        long actualTaken = toExtract - remaining;
        if (actualTaken > 0) {
            bank.consume(colonyId, key, actualTaken);
        }
        return actualTaken;
    }

    @Override
    public boolean insertItems(UUID colonyId, List<ItemStack> stacks) {
        ColonyItemBank bank = getBank();
        if (bank == null) return false;
        Set<String> emitted = new HashSet<>();
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            var rl = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (rl == null) continue;
            CompoundTag nbt = extractNbt(stack);
            ItemKey key = ItemKey.of(rl.toString(), nbt);
            bank.add(colonyId, key, stack.getCount());
            // Notify listener once per unique resource type
            if (resourceAddedListener != null && emitted.add(rl.toString())) {
                resourceAddedListener.onResourceAdded(new ResourceId(rl.toString()), stack.getCount());
            }
        }
        return true;
    }

    // ════════════════════════════════════════════════════════════
    //  ColonyResourceAccess
    // ════════════════════════════════════════════════════════════

    @Override
    public boolean hasEnough(ResourceId resource, int amount) {
        ColonyItemBank bank = getBank();
        if (bank == null) return false;

        // Element resource (no colon)
        ElementType elem = tryParseElement(resource);
        if (elem != null) {
            for (UUID colonyId : bank.getColonyIds()) {
                if (bank.countElement(colonyId, elem) >= amount) return true;
            }
            notifyElementShortage(resource, amount, elem, bank);
            return false;
        }

        // Item resource
        ItemKey key = ItemKey.of(resource.stripBlockStateSuffix().id(), null);
        for (UUID colonyId : bank.getColonyIds()) {
            if (bank.available(colonyId, key) >= amount) return true;
        }
        notifyItemShortage(resource, amount, key, bank);
        return false;
    }

    @Override
    public boolean reserve(ResourceId resource, int amount) {
        ColonyItemBank bank = getBank();
        if (bank == null) return false;

        ElementType elem = tryParseElement(resource);
        if (elem != null) {
            // Elements don't have reservations — just check availability
            for (UUID colonyId : bank.getColonyIds()) {
                if (bank.countElement(colonyId, elem) >= amount) return true;
            }
            return false;
        }

        ItemKey key = ItemKey.of(resource.stripBlockStateSuffix().id(), null);
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

        ElementType elem = tryParseElement(resource);
        if (elem != null) {
            for (UUID colonyId : bank.getColonyIds()) {
                if (bank.countElement(colonyId, elem) >= amount) {
                    return bank.consumeElement(colonyId, elem, amount);
                }
            }
            return false;
        }

        ItemKey key = ItemKey.of(resource.stripBlockStateSuffix().id(), null);
        for (UUID colonyId : bank.getColonyIds()) {
            if (bank.commit(colonyId, key, amount)) return true;
        }
        return false;
    }

    @Override
    public void release(ResourceId resource, int amount) {
        ColonyItemBank bank = getBank();
        if (bank == null) return;

        // Elements: no reservations, no-op
        if (tryParseElement(resource) != null) return;

        ItemKey key = ItemKey.of(resource.stripBlockStateSuffix().id(), null);
        for (UUID colonyId : bank.getColonyIds()) {
            bank.release(colonyId, key, amount);
        }
    }

    @Override
    public int available(ResourceId resource) {
        ColonyItemBank bank = getBank();
        if (bank == null) return 0;

        ElementType elem = tryParseElement(resource);
        if (elem != null) {
            long total = 0;
            for (UUID colonyId : bank.getColonyIds()) {
                total += bank.countElement(colonyId, elem);
            }
            return (int) total;
        }

        ItemKey key = ItemKey.of(resource.stripBlockStateSuffix().id(), null);
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
            Log.warn(TAG, "addResource({}, {}): ColonyItemBank not available", resource, amount);
            return;
        }

        ElementType elem = tryParseElement(resource);
        if (elem != null) {
            UUID colonyId = findStorageColony();
            bank.addElement(colonyId, elem, amount);
            Log.debug(LogCategory.WAREHOUSE, "storage", "addResource: {} x{} → colony {} warehouse ({} total)",
                    resource.id(), amount, colonyId, bank.countElement(colonyId, elem));
        } else {
            ItemKey key = ItemKey.of(resource.stripBlockStateSuffix().id(), null);
            UUID colonyId = findStorageColony();
            bank.add(colonyId, key, amount);
            Log.debug(LogCategory.WAREHOUSE, "storage", "addResource: {} x{} → colony {} warehouse ({} total)",
                    resource.id(), amount, colonyId, bank.count(colonyId, key));
        }

        // Notify listener to wake any AWAITING_RESOURCES tasks
        if (resourceAddedListener != null) {
            resourceAddedListener.onResourceAdded(resource, amount);
        }
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
    //  Helpers
    // ════════════════════════════════════════════════════════════

    /** Try to parse a ResourceId as an ElementType. Returns null if not an element. */
    @Nullable
    private static ElementType tryParseElement(ResourceId resource) {
        String id = resource.id();
        if (id.contains(":")) return null;
        try {
            return ElementType.valueOf(id.toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /** Find the first storage building's colony ID, or fallback to default. */
    private static UUID findStorageColony() {
        var api = com.wsteam.wandscape.api.WandscapeApis.getBuildingApi();
        for (var bd : api.getColonyBuildings(null)) {
            if ("storage".equals(bd.getCategory())) {
                UUID cid = bd.getColonyId();
                return cid != null ? cid : new UUID(0, 0);
            }
        }
        return new UUID(0, 0);
    }

    private void notifyElementShortage(ResourceId resource, int amount, ElementType elem, ColonyItemBank bank) {
        long now = System.currentTimeMillis();
        long last = lastShortageNotify.getOrDefault(resource, 0L);
        if (now - last < SHORTAGE_NOTIFY_COOLDOWN_MS) return;
        lastShortageNotify.put(resource, now);
        long avail = 0;
        for (UUID colonyId : bank.getColonyIds()) {
            avail += bank.countElement(colonyId, elem);
        }
        NeoForge.EVENT_BUS.post(new ResourceInsufficientEvent(resource, amount, (int) avail));
    }

    private void notifyItemShortage(ResourceId resource, int amount, ItemKey key, ColonyItemBank bank) {
        long now = System.currentTimeMillis();
        long last = lastShortageNotify.getOrDefault(resource, 0L);
        if (now - last < SHORTAGE_NOTIFY_COOLDOWN_MS) return;
        lastShortageNotify.put(resource, now);
        long avail = 0;
        for (UUID colonyId : bank.getColonyIds()) {
            avail += bank.available(colonyId, key);
        }
        NeoForge.EVENT_BUS.post(new ResourceInsufficientEvent(resource, amount, (int) avail));
    }

    @Nullable
    /** Build an {@link ItemStack} (count may exceed 64) from a bank entry key. */
    public static ItemStack toItemStack(ItemKey key, int count) {
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
