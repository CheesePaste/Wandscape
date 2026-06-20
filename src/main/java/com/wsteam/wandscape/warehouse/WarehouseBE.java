package com.wsteam.wandscape.warehouse;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.building.be.AbstractWandscapeBE;
import com.wsteam.wandscape.shared.data.ItemKey;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Warehouse block entity — colony item bank.
 *
 * <p>Holds {@code Map<ItemKey, Long>} for item storage and a separate
 * {@code reserved} map for pending async reservations. Data is full-saved
 * via NBT; dirty flag triggers save on chunk-unload and periodic timer.
 *
 * <p>Extends {@link AbstractWandscapeBE} to reuse colony/shutdown/NBT
 * infrastructure, but does NOT use the work queue (capacity=0).
 */
public class WarehouseBE extends AbstractWandscapeBE {

    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String TYPE_ID = "warehouse";

    // ── NBT key constants ──
    private static final String TAG_ITEMS = "warehouse_items";
    private static final String TAG_ENTRY_KEY = "key";
    private static final String TAG_ENTRY_NBT = "nbt";
    private static final String TAG_ENTRY_COUNT = "count";

    // ── Item storage ──
    private final Map<ItemKey, Long> items = new LinkedHashMap<>();
    private boolean dirty = false;

    // ── Reservation (not persisted) ──
    private final Map<ItemKey, Long> reserved = new HashMap<>();

    // ── Constructors ──

    public WarehouseBE(BlockPos pos, BlockState blockState) {
        this(Wandscape.WAREHOUSE_BE.get(), pos, blockState);
    }

    public WarehouseBE(BlockEntityType<?> type, BlockPos pos, BlockState blockState) {
        super(type, pos, blockState);
    }

    @Override
    protected String getBuildingTypeId() {
        return TYPE_ID;
    }

    // ════════════════════════════════════════════════════════════
    //  Item API
    // ════════════════════════════════════════════════════════════

    /** Total count including reserved. */
    public long count(ItemKey key) {
        return items.getOrDefault(key, 0L);
    }

    /** Available = count - reserved. */
    public long available(ItemKey key) {
        long total = count(key);
        long res = reserved.getOrDefault(key, 0L);
        return Math.max(0, total - res);
    }

    /** Add items to storage. Marks dirty. */
    public void add(ItemKey key, long amount) {
        if (amount <= 0) return;
        items.merge(key, amount, Long::sum);
        dirty = true;
        setChanged();
        LOGGER.debug("Warehouse +{} {} → total={}", amount, key.itemId(), items.get(key));
    }

    /** Consume available items. Returns false if insufficient. */
    public boolean consume(ItemKey key, long amount) {
        if (amount <= 0) return true;
        if (available(key) < amount) return false;
        long newCount = items.get(key) - amount;
        if (newCount <= 0) {
            items.remove(key);
        } else {
            items.put(key, newCount);
        }
        dirty = true;
        setChanged();
        LOGGER.debug("Warehouse -{} {} → total={}", amount, key.itemId(),
                items.getOrDefault(key, 0L));
        return true;
    }

    // ════════════════════════════════════════════════════════════
    //  Reservation API (ColonyResourceAccess)
    // ════════════════════════════════════════════════════════════

    public boolean reserve(ItemKey key, long amount) {
        if (available(key) < amount) return false;
        reserved.merge(key, amount, Long::sum);
        return true;
    }

    public boolean commit(ItemKey key, long amount) {
        long res = reserved.getOrDefault(key, 0L);
        if (res < amount) return false;
        boolean ok = consume(key, amount);
        if (ok) {
            long newRes = res - amount;
            if (newRes <= 0) reserved.remove(key);
            else reserved.put(key, newRes);
        }
        return ok;
    }

    public void release(ItemKey key, long amount) {
        long res = reserved.getOrDefault(key, 0L);
        if (res <= amount) {
            reserved.remove(key);
        } else {
            reserved.put(key, res - amount);
        }
    }

    // ════════════════════════════════════════════════════════════
    //  GUI
    // ════════════════════════════════════════════════════════════

    @Override
    public boolean onActivate(Player player) {
        openGui(player);
        return true; // consumed
    }

    /** Open the warehouse GUI for the given player. */
    public void openGui(Player player) {
        if (level == null || level.isClientSide) return;
        var menuProvider = WarehouseMenu.createMenuProvider(this);
        player.openMenu(menuProvider);
    }

    /** Snapshot of all items for network sync. */
    public Map<ItemKey, Long> getItemsSnapshot() {
        return Map.copyOf(items);
    }

    // ════════════════════════════════════════════════════════════
    //  Dirty flag
    // ════════════════════════════════════════════════════════════

    public boolean isDirty() { return dirty; }
    public void markClean() { dirty = false; }

    // ════════════════════════════════════════════════════════════
    //  NBT persistence
    // ════════════════════════════════════════════════════════════

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        items.clear();
        if (tag.contains(TAG_ITEMS)) {
            ListTag list = tag.getList(TAG_ITEMS, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = list.getCompound(i);
                String key = entry.getString(TAG_ENTRY_KEY);
                CompoundTag nbt = entry.contains(TAG_ENTRY_NBT)
                        ? entry.getCompound(TAG_ENTRY_NBT) : null;
                long count = entry.getLong(TAG_ENTRY_COUNT);
                items.put(ItemKey.of(key, nbt), count);
            }
        }
        reserved.clear(); // never persisted
        dirty = false;
        LOGGER.debug("Warehouse loaded: {} item types", items.size());
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ListTag list = new ListTag();
        for (var entry : items.entrySet()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putString(TAG_ENTRY_KEY, entry.getKey().itemId());
            if (entry.getKey().nbt() != null) {
                entryTag.put(TAG_ENTRY_NBT, entry.getKey().nbt());
            }
            entryTag.putLong(TAG_ENTRY_COUNT, entry.getValue());
            list.add(entryTag);
        }
        tag.put(TAG_ITEMS, list);
        dirty = false;
    }
}
