package com.wsteam.wandscape.warehouse;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.wsteam.wandscape.core.boundary.ColonyResourceAccess;
import com.wsteam.wandscape.core.boundary.ResourceAddedListener;
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
import com.wsteam.wandscape.shared.log.Log;

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
    public void addElement(UUID colonyId, ElementType type, long amount) {
        ColonyItemBank bank = getBank();
        if (bank != null) bank.addElement(colonyId, type, amount);
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
    }

    // ════════════════════════════════════════════════════════════
    //  ColonyResourceAccess
    // ════════════════════════════════════════════════════════════

    @Override
    public boolean hasColonies() {
        ColonyItemBank bank = getBank();
        return bank != null && !bank.getColonyIds().isEmpty();
    }

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
        ItemKey key = ItemKey.of(resource.getFuckPureResourceId_NotContainFuckedNBT().id(), null);
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

        ItemKey key = ItemKey.of(resource.getFuckPureResourceId_NotContainFuckedNBT().id(), null);
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

        ItemKey key = ItemKey.of(resource.getFuckPureResourceId_NotContainFuckedNBT().id(), null);
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

        ItemKey key = ItemKey.of(resource.getFuckPureResourceId_NotContainFuckedNBT().id(), null);
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

        ItemKey key = ItemKey.of(resource.getFuckPureResourceId_NotContainFuckedNBT().id(), null);
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
            Log.info(TAG, "addResource: {} x{} → colony {} warehouse ({} total)",
                    resource.id(), amount, colonyId, bank.countElement(colonyId, elem));
        } else {
            ItemKey key = ItemKey.of(resource.getFuckPureResourceId_NotContainFuckedNBT().id(), null);
            UUID colonyId = findStorageColony();
            bank.add(colonyId, key, amount);
            Log.info(TAG, "addResource: {} x{} → colony {} warehouse ({} total)",
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
        var api = com.wsteam.wandscape.shared.registry.WandscapeApis.getBuildingApi();
        for (var bd : api.getColonyBuildings(null)) {
            if ("storage".equals(bd.getCategory()) && !bd.isShutdown()) {
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
    private static ItemStack toItemStack(ItemKey key, int count) {
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
