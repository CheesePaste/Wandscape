package com.wsteam.wandscape.content.warehouse;
import com.wsteam.wandscape.content.task.ecs.World;

import com.wsteam.wandscape.content.element.data.ElementType;
import com.wsteam.wandscape.foundation.util.ItemKey;
import com.wsteam.wandscape.content.element.event.ElementBalanceChangedEvent;
import com.wsteam.wandscape.content.warehouse.event.WarehouseElementChangedEvent;
import com.wsteam.wandscape.content.warehouse.event.WarehouseItemChangedEvent;
import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.common.NeoForge;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

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
    private static final String TAG_PLAYER_DEPOSITS = "player_deposits";
    private static final String TAG_PLAYER_SYNTHESIZES = "player_synthesizes";
    private static final String TAG_GATHER_PUBLISHES = "gather_publishes";
    private static final String TAG_PLAYER_ROAD_PLACES = "player_road_places";
    // colonyId → items
    private final Map<UUID, Map<ItemKey, Long>> storage = new ConcurrentHashMap<>();
    // colonyId → elements
    private final Map<UUID, Map<ElementType, Long>> elementStorage = new ConcurrentHashMap<>();
    /** Colonies that already received the initial element seed (persisted). */
    private final Set<UUID> seededColonies = ConcurrentHashMap.newKeySet();
    /** colonyId → cumulative tourist purchases (persisted, drives onboarding step 6). */
    private final Map<UUID, Long> purchaseCounts = new ConcurrentHashMap<>();
    /** colonyId → cumulative player-initiated warehouse deposits (persisted, onboarding step 3). */
    private final Map<UUID, Long> playerDepositCounts = new ConcurrentHashMap<>();
    /** colonyId → cumulative player-published workstation synthesize requests (onboarding step 5). */
    private final Map<UUID, Long> playerSynthesizeCounts = new ConcurrentHashMap<>();
    /** colonyId → cumulative player-published node gather tasks (onboarding step 8). */
    private final Map<UUID, Long> gatherPublishedCounts = new ConcurrentHashMap<>();
    /** colonyId → cumulative manual road placements (onboarding step 6). */
    private final Map<UUID, Long> playerRoadPlaceCounts = new ConcurrentHashMap<>();
    // In-memory reservations (not persisted)
    private final Map<UUID, Map<ItemKey, Long>> reservations = new ConcurrentHashMap<>();

    public interface ElementChangeCallback {
        void onElementChanged(UUID colonyId, ElementType type, long newAmount, long delta);
    }

    public interface ItemChangeCallback {
        void onItemChanged(UUID colonyId, ItemKey itemKey, long newCount, long delta);
    }

    /**
     * Signals element balance changes. Defaults to broadcasting NeoForge events
     * (ElementBalanceChangedEvent and WarehouseElementChangedEvent).
     */
    private static ElementChangeCallback elementChangeNotifier =
            (colonyId, type, newAmount, delta) -> {
                NeoForge.EVENT_BUS.post(new ElementBalanceChangedEvent(colonyId));
                if (type != null) {
                    NeoForge.EVENT_BUS.post(new WarehouseElementChangedEvent(colonyId, type, newAmount, delta));
                }
            };

    /**
     * Signals item stack changes. Defaults to broadcasting WarehouseItemChangedEvent.
     */
    private static ItemChangeCallback itemChangeNotifier =
            (colonyId, key, newCount, delta) -> {
                NeoForge.EVENT_BUS.post(new WarehouseItemChangedEvent(colonyId, key, newCount, delta));
            };

    /** Replace the notifier and return the previous one (test seam). */
    static Consumer<UUID> setElementBalanceNotifier(Consumer<UUID> notifier) {
        ElementChangeCallback previous = elementChangeNotifier;
        elementChangeNotifier = (colonyId, type, newAmount, delta) -> notifier.accept(colonyId);
        return colonyId -> previous.onElementChanged(colonyId, null, 0, 0);
    }

    public static void setElementChangeNotifier(ElementChangeCallback notifier) {
        elementChangeNotifier = notifier;
    }

    public static void setItemChangeNotifier(ItemChangeCallback notifier) {
        itemChangeNotifier = notifier;
    }
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

    /** Cumulative number of times a player deposited items into this colony's warehouse. */
    public long getPlayerDepositCount(UUID colonyId) {
        return playerDepositCounts.getOrDefault(colonyId, 0L);
    }

    /** Cumulative number of synthesize requests a player published at this colony's workstation. */
    public long getPlayerSynthesizeCount(UUID colonyId) {
        return playerSynthesizeCounts.getOrDefault(colonyId, 0L);
    }

    /** Cumulative number of gather tasks a player published at this colony's nodes. */
    public long getGatherPublishedCount(UUID colonyId) {
        return gatherPublishedCounts.getOrDefault(colonyId, 0L);
    }

    /** Cumulative number of roads a player manually placed for this colony. */
    public long getPlayerRoadPlaceCount(UUID colonyId) {
        return playerRoadPlaceCounts.getOrDefault(colonyId, 0L);
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
        long newAmount = elementStorage.computeIfAbsent(colonyId, k -> new ConcurrentHashMap<>())
                .merge(type, amount, Long::sum);
        setDirty();
        if (elementChangeNotifier != null) {
            elementChangeNotifier.onElementChanged(colonyId, type, newAmount, amount);
        }
        Log.info(TAG, "[BANK] AddElement:%s".formatted(type.name()));
    }

    /** Record one successful tourist purchase for the colony. */
    public void recordPurchase(UUID colonyId) {
        purchaseCounts.merge(colonyId, 1L, Long::sum);
        setDirty();
    }

    /** Record a player deposit into the colony warehouse (drives onboarding step 3). */
    public void recordPlayerDeposit(UUID colonyId) {
        playerDepositCounts.merge(colonyId, 1L, Long::sum);
        setDirty();
    }

    /** Record a player-published workstation synthesize request (onboarding step 5). */
    public void recordPlayerSynthesize(UUID colonyId) {
        playerSynthesizeCounts.merge(colonyId, 1L, Long::sum);
        setDirty();
    }

    /** Record a player-published node gather task (onboarding step 8). */
    public void recordGatherPublished(UUID colonyId) {
        gatherPublishedCounts.merge(colonyId, 1L, Long::sum);
        setDirty();
    }

    /** Record a manually placed road for the colony (onboarding step 6). */
    public void recordPlayerRoadPlace(UUID colonyId) {
        playerRoadPlaceCounts.merge(colonyId, 1L, Long::sum);
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
        if (elementChangeNotifier != null) {
            elementChangeNotifier.onElementChanged(colonyId, type, Math.max(0, remaining), -amount);
        }
        Log.info(TAG, "[BANK] consumeElement:%s".formatted(type.name()));
        return true;
    }

    public void add(UUID colonyId, ItemKey key, long amount) {
        if (amount <= 0) return;
        long newCount = storage.computeIfAbsent(colonyId, k -> new ConcurrentHashMap<>())
                .merge(key, amount, Long::sum);
        setDirty();
        if (itemChangeNotifier != null) {
            itemChangeNotifier.onItemChanged(colonyId, key, newCount, amount);
        }
    }

    public boolean consume(UUID colonyId, ItemKey key, long amount) {
        if (amount <= 0) return true;
        if (count(colonyId, key) < amount) return false;
        Map<ItemKey, Long> items = storage.get(colonyId);
        if (items == null) return false;
        long newCount = items.get(key) - amount;
        if (newCount <= 0) {
            items.remove(key);
        } else {
            items.put(key, newCount);
        }
        setDirty();
        if (itemChangeNotifier != null) {
            itemChangeNotifier.onItemChanged(colonyId, key, Math.max(0, newCount), -amount);
        }
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
        allColonies.addAll(playerDepositCounts.keySet());
        allColonies.addAll(playerSynthesizeCounts.keySet());
        allColonies.addAll(gatherPublishedCounts.keySet());
        allColonies.addAll(playerRoadPlaceCounts.keySet());

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

            // Onboarding player-action counters (saved only when non-zero)
            long deposits = playerDepositCounts.getOrDefault(colonyId, 0L);
            if (deposits > 0) colonyTag.putLong(TAG_PLAYER_DEPOSITS, deposits);
            long synthesizes = playerSynthesizeCounts.getOrDefault(colonyId, 0L);
            if (synthesizes > 0) colonyTag.putLong(TAG_PLAYER_SYNTHESIZES, synthesizes);
            long gathers = gatherPublishedCounts.getOrDefault(colonyId, 0L);
            if (gathers > 0) colonyTag.putLong(TAG_GATHER_PUBLISHES, gathers);
            long roadPlaces = playerRoadPlaceCounts.getOrDefault(colonyId, 0L);
            if (roadPlaces > 0) colonyTag.putLong(TAG_PLAYER_ROAD_PLACES, roadPlaces);

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

            // Onboarding player-action counters (absent on legacy saves → 0)
            if (colonyTag.contains(TAG_PLAYER_DEPOSITS)) {
                bank.playerDepositCounts.put(colonyId, colonyTag.getLong(TAG_PLAYER_DEPOSITS));
            }
            if (colonyTag.contains(TAG_PLAYER_SYNTHESIZES)) {
                bank.playerSynthesizeCounts.put(colonyId, colonyTag.getLong(TAG_PLAYER_SYNTHESIZES));
            }
            if (colonyTag.contains(TAG_GATHER_PUBLISHES)) {
                bank.gatherPublishedCounts.put(colonyId, colonyTag.getLong(TAG_GATHER_PUBLISHES));
            }
            if (colonyTag.contains(TAG_PLAYER_ROAD_PLACES)) {
                bank.playerRoadPlaceCounts.put(colonyId, colonyTag.getLong(TAG_PLAYER_ROAD_PLACES));
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
