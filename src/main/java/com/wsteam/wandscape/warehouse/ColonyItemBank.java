package com.wsteam.wandscape.warehouse;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.wsteam.wandscape.shared.data.ElementType;
import com.wsteam.wandscape.shared.data.ItemKey;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Per-level persistent item bank shared by all colonies.
 *
 * <p>Items survive warehouse block destruction — the warehouse is just a
 * terminal; the bank is the source of truth.
 *
 * <p>Reservations are in-memory only (not persisted across restart).
 */
public class ColonyItemBank extends SavedData {
    private static final String TAG = "ColonyItemBank";
    private static final String DATA_NAME = "wandscape_colony_items";

    // NBT keys
    private static final String TAG_COLONIES = "colonies";
    private static final String TAG_COLONY_ID = "colony_id";
    private static final String TAG_ITEMS = "items";
    private static final String TAG_ENTRY_KEY = "key";
    private static final String TAG_ENTRY_NBT = "nbt";
    private static final String TAG_ENTRY_COUNT = "count";
    private static final String TAG_ELEMENTS = "elements";
    private static final String TAG_ELEMENT_TYPE = "type";
    private static final String TAG_ELEMENT_AMOUNT = "amount";
    private static final String TAG_SEEDED = "seeded";
    private static final String TAG_SEEDED_ID = "id";
    private static final String TAG_PURCHASE_COUNT = "purchases";
    // colonyId → items
    private final Map<UUID, Map<ItemKey, Long>> storage = new ConcurrentHashMap<>();
    // colonyId → elements
    private final Map<UUID, Map<ElementType, Long>> elementStorage = new ConcurrentHashMap<>();
    /** Colonies that already received the initial element seed (persisted). */
    private final Set<UUID> seededColonies = ConcurrentHashMap.newKeySet();
    /** colonyId → cumulative tourist purchases (persisted, drives onboarding step 6). */
    private final Map<UUID, Long> purchaseCounts = new ConcurrentHashMap<>();
    // In-memory reservations (not persisted)
    private final Map<UUID, Map<ItemKey, Long>> reservations = new ConcurrentHashMap<>();
    // ── Factory ──

    public static final Factory<ColonyItemBank> FACTORY = new Factory<>(
            ColonyItemBank::new,
            ColonyItemBank::load,
            null
    );

    public static ColonyItemBank get(Level level) {
        return level.getServer().overworld()
                .getDataStorage()
                .computeIfAbsent(FACTORY, DATA_NAME);
    }

    // ════════════════════════════════════════════════════════════
    //  Query
    // ════════════════════════════════════════════════════════════

    public long count(UUID colonyId, ItemKey key) {
        Map<ItemKey, Long> items = storage.get(colonyId);
        return items != null ? items.getOrDefault(key, 0L) : 0L;
    }

    public long available(UUID colonyId, ItemKey key) {
        long total = count(colonyId, key);
        Map<ItemKey, Long> res = reservations.get(colonyId);
        long reserved = res != null ? res.getOrDefault(key, 0L) : 0L;
        return Math.max(0, total - reserved);
    }

    public Map<ItemKey, Long> getSnapshot(UUID colonyId) {
        Map<ItemKey, Long> items = storage.get(colonyId);
        return items != null ? Map.copyOf(items) : Map.of();
    }

    /** All colonies that have items stored. */
    public Set<UUID> getColonyIds() {
        Set<UUID> ids = new HashSet<>(storage.keySet());
        ids.addAll(elementStorage.keySet());
        return Set.copyOf(ids);
    }

    // ── Element query ──

    public long countElement(UUID colonyId, ElementType type) {
        Map<ElementType, Long> map = elementStorage.get(colonyId);
        return map != null ? map.getOrDefault(type, 0L) : 0L;
    }

    public Map<ElementType, Long> getElementSnapshot(UUID colonyId) {
        Map<ElementType, Long> map = elementStorage.get(colonyId);
        return map != null ? Map.copyOf(map) : Map.of();
    }

    /** Cumulative number of items tourists have purchased from this colony's shops. */
    public long getPurchaseCount(UUID colonyId) {
        return purchaseCounts.getOrDefault(colonyId, 0L);
    }

    /** Whether this colony has already received the initial element seed. */
    public boolean isSeeded(UUID colonyId) {
        return seededColonies.contains(colonyId);
    }

    /** Mark a colony as seeded so it never receives the starter elements twice. */
    public void markSeeded(UUID colonyId) {
        seededColonies.add(colonyId);
        setDirty();
    }

    // ════════════════════════════════════════════════════════════
    //  Mutate
    // ════════════════════════════════════════════════════════════

    /** Add amount of element type to colony storage. */
    public void addElement(UUID colonyId, ElementType type, long amount) {
        if (amount <= 0) return;
        elementStorage.computeIfAbsent(colonyId, k -> new ConcurrentHashMap<>())
                .merge(type, amount, Long::sum);
        setDirty();
        Log.info(TAG, "[BANK] AddElement:%s".formatted(type.name()));
    }

    /** Record one successful tourist purchase for the colony. */
    public void recordPurchase(UUID colonyId) {
        purchaseCounts.merge(colonyId, 1L, Long::sum);
        setDirty();
    }

    /** Consume amount of element type. Returns false if insufficient. */
    public boolean consumeElement(UUID colonyId, ElementType type, long amount) {
        if (amount <= 0) return true;
        Map<ElementType, Long> map = elementStorage.get(colonyId);
        if (map == null) return false;
        long current = map.getOrDefault(type, 0L);
        if (current < amount) return false;
        long remaining = current - amount;
        if (remaining <= 0) {
            map.remove(type);
        } else {
            map.put(type, remaining);
        }
        setDirty();
        Log.info(TAG, "[BANK] consumeElement:%s".formatted(type.name()));
        return true;
    }

    public void add(UUID colonyId, ItemKey key, long amount) {
        if (amount <= 0) return;
        storage.computeIfAbsent(colonyId, k -> new ConcurrentHashMap<>())
                .merge(key, amount, Long::sum);
        setDirty();
    }

    public boolean consume(UUID colonyId, ItemKey key, long amount) {
        if (amount <= 0) return true;
        if (available(colonyId, key) < amount) return false;
        Map<ItemKey, Long> items = storage.get(colonyId);
        if (items == null) return false;
        long newCount = items.get(key) - amount;
        if (newCount <= 0) {
            items.remove(key);
        } else {
            items.put(key, newCount);
        }
        setDirty();
        return true;
    }

    // ════════════════════════════════════════════════════════════
    //  Reservation (in-memory)
    // ════════════════════════════════════════════════════════════

    public boolean reserve(UUID colonyId, ItemKey key, long amount) {
        if (available(colonyId, key) < amount) return false;
        Log.info(TAG, "[BANK] reserve:%s".formatted(key.itemId()));
        reservations.computeIfAbsent(colonyId, k -> new ConcurrentHashMap<>())
                .merge(key, amount, Long::sum);
        return true;
    }

    public boolean commit(UUID colonyId, ItemKey key, long amount) {
        Map<ItemKey, Long> res = reservations.get(colonyId);
        long reserved = res != null ? res.getOrDefault(key, 0L) : 0L;
        if (reserved < amount) return false;
        boolean ok = consume(colonyId, key, amount);
        if (ok && res != null) {
            long newRes = reserved - amount;
            if (newRes <= 0) res.remove(key);
            else res.put(key, newRes);
        }
        Log.info(TAG, "[BANK] commit:%s".formatted(key.itemId()));
        return ok;
    }

    public void release(UUID colonyId, ItemKey key, long amount) {
        Map<ItemKey, Long> res = reservations.get(colonyId);
        if (res == null) return;
        long reserved = res.getOrDefault(key, 0L);
        if (reserved <= amount) {
            res.remove(key);
        } else {
            res.put(key, reserved - amount);
        }
    }

    // ════════════════════════════════════════════════════════════
    //  NBT persistence
    // ════════════════════════════════════════════════════════════

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag coloniesTag = new ListTag();

        Set<UUID> allColonies = new HashSet<>(storage.keySet());
        allColonies.addAll(elementStorage.keySet());
        allColonies.addAll(purchaseCounts.keySet());

        for (UUID colonyId : allColonies) {
            CompoundTag colonyTag = new CompoundTag();
            colonyTag.putUUID(TAG_COLONY_ID, colonyId);

            // Items
            Map<ItemKey, Long> items = storage.get(colonyId);
            if (items != null && !items.isEmpty()) {
                ListTag itemsTag = new ListTag();
                for (var itemEntry : items.entrySet()) {
                    CompoundTag itemTag = new CompoundTag();
                    itemTag.putString(TAG_ENTRY_KEY, itemEntry.getKey().itemId());
                    if (itemEntry.getKey().nbt() != null) {
                        itemTag.put(TAG_ENTRY_NBT, itemEntry.getKey().nbt());
                    }
                    itemTag.putLong(TAG_ENTRY_COUNT, itemEntry.getValue());
                    itemsTag.add(itemTag);
                }
                colonyTag.put(TAG_ITEMS, itemsTag);
            }

            // Elements
            Map<ElementType, Long> elements = elementStorage.get(colonyId);
            if (elements != null && !elements.isEmpty()) {
                ListTag elementsTag = new ListTag();
                for (var elemEntry : elements.entrySet()) {
                    CompoundTag elemTag = new CompoundTag();
                    elemTag.putString(TAG_ELEMENT_TYPE, elemEntry.getKey().name());
                    elemTag.putLong(TAG_ELEMENT_AMOUNT, elemEntry.getValue());
                    elementsTag.add(elemTag);
                }
                colonyTag.put(TAG_ELEMENTS, elementsTag);
            }

            // Cumulative tourist purchases
            long purchases = purchaseCounts.getOrDefault(colonyId, 0L);
            if (purchases > 0) {
                colonyTag.putLong(TAG_PURCHASE_COUNT, purchases);
            }

            coloniesTag.add(colonyTag);
        }
        tag.put(TAG_COLONIES, coloniesTag);

        // Persist seeded markers so each colony receives the starter elements exactly once.
        if (!seededColonies.isEmpty()) {
            ListTag seededTag = new ListTag();
            for (UUID id : seededColonies) {
                CompoundTag entry = new CompoundTag();
                entry.putUUID(TAG_SEEDED_ID, id);
                seededTag.add(entry);
            }
            tag.put(TAG_SEEDED, seededTag);
        }
        return tag;
    }

    private static ColonyItemBank load(CompoundTag tag, HolderLookup.Provider registries) {
        ColonyItemBank bank = new ColonyItemBank();
        ListTag coloniesTag = tag.getList(TAG_COLONIES, Tag.TAG_COMPOUND);
        for (int i = 0; i < coloniesTag.size(); i++) {
            CompoundTag colonyTag = coloniesTag.getCompound(i);
            UUID colonyId = colonyTag.getUUID(TAG_COLONY_ID);

            // Items
            Map<ItemKey, Long> items = new ConcurrentHashMap<>();
            if (colonyTag.contains(TAG_ITEMS)) {
                ListTag itemsTag = colonyTag.getList(TAG_ITEMS, Tag.TAG_COMPOUND);
                for (int j = 0; j < itemsTag.size(); j++) {
                    CompoundTag itemTag = itemsTag.getCompound(j);
                    String key = itemTag.getString(TAG_ENTRY_KEY);
                    CompoundTag nbt = itemTag.contains(TAG_ENTRY_NBT)
                            ? itemTag.getCompound(TAG_ENTRY_NBT) : null;
                    long count = itemTag.getLong(TAG_ENTRY_COUNT);
                    items.put(ItemKey.of(key, nbt), count);
                }
                bank.storage.put(colonyId, items);
            }

            // Elements
            if (colonyTag.contains(TAG_ELEMENTS)) {
                Map<ElementType, Long> elements = new ConcurrentHashMap<>();
                ListTag elementsTag = colonyTag.getList(TAG_ELEMENTS, Tag.TAG_COMPOUND);
                for (int j = 0; j < elementsTag.size(); j++) {
                    CompoundTag elemTag = elementsTag.getCompound(j);
                    ElementType type = ElementType.valueOf(elemTag.getString(TAG_ELEMENT_TYPE));
                    long amount = elemTag.getLong(TAG_ELEMENT_AMOUNT);
                    elements.put(type, amount);
                }
                bank.elementStorage.put(colonyId, elements);
            }

            // Cumulative tourist purchases (absent on legacy saves → 0)
            if (colonyTag.contains(TAG_PURCHASE_COUNT)) {
                bank.purchaseCounts.put(colonyId, colonyTag.getLong(TAG_PURCHASE_COUNT));
            }
        }

        // Read persisted seeded markers, then backfill from existing element ledgers:
        // migrates colonies seeded by the old session flag (avoids a one-off double seed)
        // and keeps spent-to-zero colonies seeded (the colonyId ledger key is never removed).
        if (tag.contains(TAG_SEEDED)) {
            ListTag seededTag = tag.getList(TAG_SEEDED, Tag.TAG_COMPOUND);
            for (int i = 0; i < seededTag.size(); i++) {
                CompoundTag entry = seededTag.getCompound(i);
                if (entry.hasUUID(TAG_SEEDED_ID)) {
                    bank.seededColonies.add(entry.getUUID(TAG_SEEDED_ID));
                }
            }
        }
        bank.seededColonies.addAll(bank.elementStorage.keySet());

        Log.info(TAG, "[BANK] Loaded {} colony item banks", bank.storage.size());
        return bank;
    }
}
