package com.wsteam.wandscape.content.building.internal;
import com.wsteam.wandscape.content.task.component.Position;
import com.wsteam.wandscape.content.task.component.NpcInventory;
import com.wsteam.wandscape.content.element.data.ElementType;
import com.wsteam.wandscape.content.building.data.BuildingData;
import com.wsteam.wandscape.content.tourist.data.ShopGoodDef;
import com.wsteam.wandscape.foundation.util.ItemKey;
import com.wsteam.wandscape.content.tourist.data.ShopConfig;
import com.wsteam.wandscape.foundation.util.TickProfiler;

import com.wsteam.wandscape.content.building.data.BuildingConfig;
import com.wsteam.wandscape.impl.WandscapeEngine;
import com.wsteam.wandscape.content.colony.ColonyActivation;
import com.wsteam.wandscape.content.warehouse.system.ResourceSupplySystem;
import com.wsteam.wandscape.content.warehouse.transport.ItemTransportManager;
import com.wsteam.wandscape.api.BuildingApi;
// data imports updated
import com.wsteam.wandscape.content.tourist.event.DailySettlementEvent;
import com.wsteam.wandscape.content.tourist.event.ShopRestockedEvent;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.content.warehouse.ColonyItemBank;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages shop inventory: dynamic restock on low stock, tourist purchases.
 *
 * <p>Each shop building has its own stock map. When a purchase leaves an item's
 * stock below maxStock, an automatic restock is triggered that fills goods
 * to their maxStock by deducting element costs from the colony bank.
 *
 * <p>Stock data is persisted through {@link BuildingSavedData} so that inventory
 * and player-configured max-stock settings survive server restarts.
 *
 * <p>Stock state changes trigger contribution toggling via
 * {@link BuildingContributionRegistry#setShopHasStock(UUID, boolean)}.
 */
public final class ShopStockManager {
    private static final String TAG = "ShopStockManager";

    /** buildingId → whether the shop currently has any stock (performance cache) */
    private final Map<UUID, Boolean> hasStockCache = new ConcurrentHashMap<>();

    /** Buildings currently being restocked (prevents duplicate concurrent restocks). */
    private final Set<UUID> restockingInProgress = ConcurrentHashMap.newKeySet();

    /**
     * Shops that triggered production (synthesize) for goods the warehouse
     * couldn't supply. Retried periodically so once the produced item lands
     * in the warehouse, the shop is refilled without waiting for a purchase
     * or the next daily settlement.
     */
    private final Set<UUID> pendingRestock = ConcurrentHashMap.newKeySet();

    /** Ticks between pending-restock retries (≈5s). */
    private static final int RESTOCK_RETRY_INTERVAL_TICKS = 100;
    private int restockRetryTicks = 0;

    /** Random picker for which affordable good a tourist buys. */
    private final Random random = new Random();

    @javax.annotation.Nullable
    private static ShopStockManager active;

    private ShopStockManager() {}

    /** Returns the active instance, or null if not registered yet. */
    @javax.annotation.Nullable
    public static ShopStockManager getActive() { return active; }

    /** Initialize the singleton and register event handlers. */
    public static ShopStockManager register() {
        var instance = new ShopStockManager();
        NeoForge.EVENT_BUS.register(instance);
        active = instance;
        return instance;
    }

    // ── NpcInventory query ──

    /** Returns a snapshot of the shop's current stock (itemId → count). */
    public Map<String, Integer> getStock(UUID buildingId) {
        BuildingSavedData savedData = getSavedData();
        return savedData != null ? savedData.getShopStock(buildingId) : Map.of();
    }

    /**
     * Perform an immediate initial restock if this shop has never been stocked.
     * Call this when a player first opens a shop GUI to avoid waiting for the
     * next tick interval.
     */
    public void ensureStockInitialized(UUID buildingId) {
        BuildingSavedData savedData = getSavedData();
        if (savedData == null) return;
        if (savedData.hasShopStock(buildingId)) return; // already has stock

        ServerLevel level = getServerLevel();
        if (level == null) return;
        BuildingState state = savedData.getBuilding(buildingId);
        if (state == null || !state.isStructureIntact()) return;

        UUID colonyId = state.getColonyId();
        if (colonyId == null) return;

        BuildingConfig config = BuildingConfigLoader.getInstance()
                .get(state.getBuildingTypeId());
        if (config == null || config.shop() == null) return;

        ColonyItemBank bank = ColonyItemBank.get(level);
        if (bank == null) return;

        restock(buildingId, config.shop(), colonyId, bank);
        Log.info(TAG, "[Shop] Initial restock for building={}", buildingId.toString().substring(0, 8));
    }

    /** Returns the current stock count for a specific item. */
    public int getStockCount(UUID buildingId, String itemId) {
        BuildingSavedData savedData = getSavedData();
        if (savedData == null) return 0;
        Map<String, Integer> s = savedData.getShopStock(buildingId);
        return s.getOrDefault(itemId, 0);
    }

    /** Returns the max stock for a specific good (0–64). Defaults to 0. */
    public int getMaxStock(UUID buildingId, String itemId) {
        BuildingSavedData savedData = getSavedData();
        return savedData != null ? savedData.getShopMaxStock(buildingId, itemId) : ShopGoodDef.DEFAULT_MAX_STOCK;
    }

    /** Returns all max stocks for a building's configured goods (itemId → maxStock). */
    public Map<String, Integer> getAllMaxStocks(UUID buildingId) {
        BuildingSavedData savedData = getSavedData();
        if (savedData == null) return Map.of();

        // Collect goods from config + populate with stored settings or defaults
        BuildingState state = getBuildingState(buildingId);
        if (state == null) return Map.of();
        BuildingConfig config = BuildingConfigLoader.getInstance()
                .get(state.getBuildingTypeId());
        if (config == null || config.shop() == null) return Map.of();

        Map<String, Integer> result = new java.util.LinkedHashMap<>();
        for (ShopGoodDef good : config.shop().goods()) {
            String itemId = good.itemId();
            int max = savedData.getShopMaxStock(buildingId, itemId);
            result.put(itemId, max);
        }
        return result;
    }

    /**
     * Adjusts max stock for a specific good (clamped to 0–64).
     * If the new max is higher than current, triggers an immediate restock attempt.
     * Changes are persisted via BuildingSavedData.
     */
    public void setMaxStock(UUID buildingId, String itemId, int newMax) {
        newMax = Math.clamp(newMax, 0, 64);

        BuildingSavedData savedData = getSavedData();
        if (savedData == null) return;
        savedData.setShopMaxStock(buildingId, itemId, newMax);

        // Trigger restock if max was increased
        ServerLevel level = getServerLevel();
        if (level == null) return;
        BuildingState state = getBuildingState(buildingId);
        if (state == null || !state.isStructureIntact()) return;
        UUID colonyId = state.getColonyId();
        if (colonyId == null) return;
        BuildingConfig config = BuildingConfigLoader.getInstance()
                .get(state.getBuildingTypeId());
        if (config == null || config.shop() == null) return;
        ColonyItemBank bank = ColonyItemBank.get(level);
        if (bank == null) return;

        restock(buildingId, config.shop(), colonyId, bank);
    }

    /** Returns true if the shop has at least one item in stock. */
    public boolean hasStock(UUID buildingId) {
        return hasStockCache.getOrDefault(buildingId, false);
    }

    /**
     * Returns the sum of comfort values from all in-stock goods for a shop.
     * Only counts goods with current stock > 0.
     */
    public int getGoodsBonusComfort(UUID buildingId) {
        return sumGoodsStat(buildingId, ShopGoodDef::comfort);
    }

    /** Returns the sum of magic values from all in-stock goods for a shop. */
    public int getGoodsBonusMagic(UUID buildingId) {
        return sumGoodsStat(buildingId, ShopGoodDef::magic);
    }

    /** Returns the sum of wonder values from all in-stock goods for a shop. */
    public int getGoodsBonusWonder(UUID buildingId) {
        return sumGoodsStat(buildingId, ShopGoodDef::wonder);
    }

    @FunctionalInterface
    private interface GoodStatAccessor {
        int get(ShopGoodDef good);
    }

    private int sumGoodsStat(UUID buildingId, GoodStatAccessor accessor) {
        BuildingSavedData savedData = getSavedData();
        if (savedData == null) return 0;
        Map<String, Integer> s = savedData.getShopStock(buildingId);
        if (s.isEmpty()) return 0;
        BuildingState state = getBuildingState(buildingId);
        if (state == null) return 0;
        BuildingConfig config = BuildingConfigLoader.getInstance()
                .get(state.getBuildingTypeId());
        if (config == null || config.shop() == null) return 0;
        int total = 0;
        for (ShopGoodDef good : config.shop().goods()) {
            if (s.getOrDefault(good.itemId(), 0) > 0) {
                total += accessor.get(good);
            }
        }
        return total;
    }

    @Nullable
    private static BuildingState getBuildingState(UUID buildingId) {
        ServerLevel level = getServerLevel();
        if (level == null) return null;
        BuildingSavedData savedData = BuildingSavedData.get(level);
        if (savedData == null) return null;
        return savedData.getBuilding(buildingId);
    }

    // ── Operations ──

    /** Result of a tourist bulk purchase: what was bought, how many, and total wallet spent. */
    public record PurchaseResult(String itemId, int count, long spent) {}

    /**
     * Universal-element wallet price for one unit of a good: the sum of all
     * per-element profits the colony receives (each element × (1 + profitRate)).
     * Returns 0 for goods with no element mapping.
     */
    public static long walletPrice(ShopConfig shopConfig, ShopGoodDef good) {
        double profitRate = shopConfig != null ? shopConfig.profitRate() : 0.0;
        long total = 0;
        for (var entry : getItemElementValue(good.itemId()).entrySet()) {
            total += (long) Math.ceil(entry.getValue() * (1.0 + profitRate));
        }
        return total;
    }

    /**
     * Tourist buys from a shop with their universal-element wallet.
     *
     * <p>Each shopping trip draws a random budget fraction a ∈ [0.2, 1] of the
     * tourist's initial wallet (capped at the current balance), then selects one
     * random in-stock good whose unit price fits within that budget and buys as
     * many units as the budget allows (capped by stock). The tourist never spends
     * more than the current wallet, so a single expensive good no longer empties
     * the wallet and leaves nothing for later trips.
     *
     * @return the purchase result, or null if nothing was buyable
     */
    @Nullable
    public PurchaseResult purchaseAffordable(UUID buildingId, UUID colonyId, int wallet, int initialWallet) {
        if (wallet <= 0) return null;
        BuildingSavedData savedData = getSavedData();
        if (savedData == null) return null;
        Map<String, Integer> s = savedData.getOrCreateShopStock(buildingId);
        if (s.isEmpty()) return null;
        BuildingState state = getBuildingState(buildingId);
        if (state == null) return null;
        BuildingConfig config = BuildingConfigLoader.getInstance().get(state.getBuildingTypeId());
        if (config == null || config.shop() == null) return null;

        // Trip budget: random 20%–100% of the initial wallet, capped at what remains.
        double a = 0.2 + 0.8 * random.nextDouble();
        long budget = (long) (a * initialWallet);
        if (budget > wallet) budget = wallet;

        // Selectable = in-stock goods the tourist can afford at least one unit of
        // this trip (unit price within budget). No debt: goods beyond the budget
        // are simply not buyable now.
        List<ShopGoodDef> selectable = new ArrayList<>();
        for (ShopGoodDef good : config.shop().goods()) {
            if (s.getOrDefault(good.itemId(), 0) <= 0) continue;
            long price = walletPrice(config.shop(), good);
            if (price > 0 && price <= budget) selectable.add(good);
        }
        if (selectable.isEmpty()) return null;

        ShopGoodDef chosen = selectable.get(random.nextInt(selectable.size()));
        long price = walletPrice(config.shop(), chosen);
        int stock = s.getOrDefault(chosen.itemId(), 0);

        // Buy as many units as the trip budget allows, capped by stock. No +1: a
        // good the budget can't cover is not bought at all.
        int qty = (int) Math.min(stock, budget / price);

        int bought = purchase(buildingId, chosen.itemId(), colonyId, qty);
        if (bought <= 0) return null;
        return new PurchaseResult(chosen.itemId(), bought, price * bought);
    }

    /**
     * Tourist purchases {@code count} units of an item (or as many as are in stock).
     *
     * @return the number of units actually purchased (0 if the purchase failed)
     */
    public int purchase(UUID buildingId, String itemId, UUID colonyId, int count) {
        BuildingSavedData savedData = getSavedData();
        if (savedData == null || count <= 0) return 0;

        Map<String, Integer> s = savedData.getOrCreateShopStock(buildingId);
        int current = s.getOrDefault(itemId, 0);
        if (current <= 0) return 0;

        ServerLevel level = getServerLevel();
        if (level == null) return 0;
        BuildingState state = savedData.getBuilding(buildingId);
        if (state == null) return 0;

        BuildingConfig config = BuildingConfigLoader.getInstance()
                .get(state.getBuildingTypeId());
        if (config == null || config.shop() == null) return 0;

        ShopGoodDef good = findGood(config.shop(), itemId);
        if (good == null) return 0;

        int qty = Math.min(count, current);
        int newStock = current - qty;
        s.put(itemId, newStock);
        savedData.setDirty();
        updateHasStock(buildingId, s);

        // Deposit profit elements into colony bank (based on item's element mapping value)
        // 创始人离线时按 offlineIncomeMultiplier 折减利润：成本不变、只折利润，
        // 商店按进价出售也永不亏损（不折售价，否则商品卖出即亏本）。
        if (colonyId != null) {
            ColonyItemBank bank = ColonyItemBank.get(level);
            double profitRate = config.shop().profitRate();
            double m = ColonyActivation.getIncomeMultiplier(colonyId);
            Map<ElementType, Long> elementValue = getItemElementValue(itemId);
            for (var entry : elementValue.entrySet()) {
                long cost = entry.getValue();
                long fullRevenue = (long) Math.ceil(cost * (1.0 + profitRate));
                long perUnit = ColonyActivation.scaleProfit(cost, fullRevenue - cost, m);
                bank.addElement(colonyId, entry.getKey(), perUnit * qty);
            }
            bank.recordPurchase(colonyId);
        }

        // Dynamic restock: if stock is no longer full, trigger auto-restock
        int maxStock = getMaxStock(buildingId, itemId);
        if (maxStock > 0 && newStock < maxStock) {
            ColonyItemBank bank = ColonyItemBank.get(level);
            if (bank != null && restockingInProgress.add(buildingId)) {
                restock(buildingId, config.shop(), colonyId, bank);
                restockingInProgress.remove(buildingId);
            }
        }

        return qty;
    }

    // ── Internal ──

    private void restock(UUID buildingId, ShopConfig shopConfig,
                         UUID colonyId, ColonyItemBank bank) {
        BuildingSavedData savedData = getSavedData();
        if (savedData == null) return;
        ServerLevel level = getServerLevel();
        if (level == null) return;

        ItemTransportManager transporter = WandscapeEngine.getTransporter();
        boolean hasTransport = transporter != null && findNearestWarehouse(colonyId) != null;

        // Find warehouse position for this restock cycle
        BlockPos warehousePos = null;
        if (hasTransport) {
            warehousePos = findNearestWarehouse(colonyId);
            if (warehousePos == null) {
                hasTransport = false;
            } else {
                BuildingState shopState = savedData.getBuilding(buildingId);
                if (shopState == null) {
                    hasTransport = false;
                }
            }
        }

        boolean changed = false;
        boolean supplyPending = false;

        for (ShopGoodDef good : shopConfig.goods()) {
            Map<String, Integer> s = savedData.getOrCreateShopStock(buildingId);
            int current = s.getOrDefault(good.itemId(), 0);
            int max = getMaxStock(buildingId, good.itemId());
            int needed = max - current;
            if (needed <= 0) continue;

            // Check item availability in warehouse (not elements)
            ItemKey itemKey = ItemKey.of(good.itemId(), null);
            long availableInWarehouse = bank.available(colonyId, itemKey);
            int canAfford = (int) Math.min(needed, availableInWarehouse);

            if (canAfford <= 0) {
                // Warehouse has none of the item — request production so a
                // later retry can fill the shop once the item is synthesized.
                if (requestSynthesize(colonyId, good.itemId(), needed)) supplyPending = true;
                continue;
            }

            // Consume items from warehouse immediately (they are in transit)
            bank.consume(colonyId, itemKey, canAfford);

            if (canAfford < needed && requestSynthesize(colonyId, good.itemId(), needed - canAfford)) {
                // Partial fill — request production for the remaining shortfall.
                supplyPending = true;
            }

            if (hasTransport && warehousePos != null) {
                // Launch async transport visualization from warehouse to shop.
                // Stock is added immediately to prevent compound over-stocking
                // from repeated daily restocks before previous transports arrive.
                BuildingState shopState = savedData.getBuilding(buildingId);
                if (shopState != null) {
                    Map<String, Integer> stock = savedData.getOrCreateShopStock(buildingId);
                    stock.put(good.itemId(), stock.getOrDefault(good.itemId(), 0) + canAfford);
                    launchRestockTransport(buildingId, good.itemId(), canAfford,
                            level,
                            warehousePos, shopState.getAnchor());
                    changed = true;
                }
            } else {
                // No transport available — add stock instantly
                Map<String, Integer> stock = savedData.getOrCreateShopStock(buildingId);
                stock.put(good.itemId(), stock.getOrDefault(good.itemId(), 0) + canAfford);
                changed = true;
            }
        }

        // Keep the shop in the retry set only while future production could still
        // fill a short good; drop it once everything is stocked or unsynthesizable.
        if (supplyPending) {
            pendingRestock.add(buildingId);
        } else {
            pendingRestock.remove(buildingId);
        }

        if (changed) {
            savedData.setDirty();
            Map<String, Integer> finalStock = savedData.getOrCreateShopStock(buildingId);
            updateHasStock(buildingId, finalStock);
            NeoForge.EVENT_BUS.post(new ShopRestockedEvent(buildingId, colonyId));
        }
    }

    /**
     * Ask the engine supply chain to synthesize {@code itemId} (at a workstation)
     * because the warehouse is short. The produced item lands in the warehouse;
     * the pending-restock retry picks it up on a later tick.
     *
     * <p>The synthesize task is prepended to the workstation queue (atFront) so an
     * out-of-stock good gets crafted before building-material tasks queued earlier —
     * tourists can't buy while the good is empty, so this restock is time-critical.
     *
     * @return true if the shortfall is being handled (recipe found, task queued or
     *         already in flight); false if it cannot be synthesized right now
     */
    private boolean requestSynthesize(@Nullable UUID colonyId, String itemId, int amount) {
        try {
            return ResourceSupplySystem.enqueueSynthesize(itemId, amount, colonyId, WandscapeEngine.getWorld(), true);
        } catch (Exception e) {
            Log.warn(TAG, "[Shop] requestSynthesize({} x{} colony={}) failed: {}", itemId, amount, colonyId, e.getMessage());
            return false;
        }
    }

    // ── Pending-restock retry ──

    /** Re-attempt restock for shops awaiting produced goods, on a slow heartbeat. */
    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        try (var span = com.wsteam.wandscape.foundation.util.TickProfiler.INSTANCE.start("building.shop_stock.on_server_tick")) {
            if (pendingRestock.isEmpty()) return;
            if (++restockRetryTicks < RESTOCK_RETRY_INTERVAL_TICKS) return;
            restockRetryTicks = 0;
            retryPendingRestocks();
        }
    }

    private void retryPendingRestocks() {
        BuildingSavedData savedData = getSavedData();
        if (savedData == null) return;
        ServerLevel level = getServerLevel();
        if (level == null) return;
        ColonyItemBank bank = ColonyItemBank.get(level);
        if (bank == null) return;

        for (UUID buildingId : List.copyOf(pendingRestock)) {
            BuildingState state = savedData.getBuilding(buildingId);
            if (state == null || !state.isStructureIntact()) {
                pendingRestock.remove(buildingId);
                continue;
            }
            UUID colonyId = state.getColonyId();
            if (colonyId == null) continue;
            BuildingConfig config = BuildingConfigLoader.getInstance().get(state.getBuildingTypeId());
            if (config == null || config.shop() == null) {
                pendingRestock.remove(buildingId);
                continue;
            }
            // restock() re-derives supplyPending and updates the pending set itself.
            restock(buildingId, config.shop(), colonyId, bank);
        }
    }

    /**
     * Launch visual item transport from warehouse to shop for restocked goods.
     * Items and stock are handled at restock time; this is purely cosmetic.
     * If the building is destroyed mid-transport, items are already consumed
     * from the bank (realistic supply-loss risk).
     */
    private void launchRestockTransport(UUID buildingId, String itemId, int amount,
                                        ServerLevel level,
                                        BlockPos warehousePos, BlockPos shopPos) {
        ItemTransportManager transporter = WandscapeEngine.getTransporter();
        if (transporter == null) return;

        ItemKey key = ItemKey.of(itemId, null);

        transporter.send(key, amount, warehousePos, shopPos, level, 0)
            .thenRun(() -> onTransportArrived(buildingId, itemId, amount));

    }

    /** Called when all transport units arrive — just logs. Stock already added at restock time. */
    private void onTransportArrived(UUID buildingId, String itemId, int amount) {
        BuildingSavedData savedData = getSavedData();
        if (savedData == null) return;
        BuildingState state = savedData.getBuilding(buildingId);
        if (state == null) {
            Log.warn(TAG, "[Shop] Transport arrived but building {} gone — lost {} × {}",
                    buildingId.toString().substring(0, 8), amount, itemId);
        }
    }

    /**
     * Find the nearest storage building (warehouse) for a colony.
     * Returns the anchor position, or null if none found.
     */
    @Nullable
    private static BlockPos findNearestWarehouse(UUID colonyId) {
        BuildingApi api = getBuildingApiSilently();
        if (api == null) return null;
        var ids = api.getBuildingsByCategory(colonyId, "storage");
        if (ids == null || ids.isEmpty()) return null;
        // Return the first warehouse's position (don't need nearest for restock)
        BuildingData bd = api.getBuilding(ids.get(0));
        return bd != null ? bd.getPosition() : null;
    }

    /**
     * Get the BuildingApi silently (returns null if not loaded yet).
     */
    @Nullable
    private static BuildingApi getBuildingApiSilently() {
        try {
            return WandscapeApis.getBuildingApi();
        } catch (IllegalStateException e) {
            return null;
        }
    }

    /**
     * Look up an item's element value from element_mappings.
     * Used for profit calculation on sale.
     */
    private static Map<ElementType, Long> getItemElementValue(String itemId) {
        var loader = com.wsteam.wandscape.Wandscape.ELEMENT_MAPPING_LOADER;
        if (loader == null) return Map.of();
        return loader.getItemElementValue(itemId);
    }

    // ── Daily restock ──

    /** Restock all active shop buildings in the colony after daily settlement. */
    @SubscribeEvent
    public void onDailySettlement(DailySettlementEvent event) {
        UUID colonyId = event.getReport().colonyId();
        BuildingSavedData savedData = getSavedData();
        if (savedData == null) return;
        ServerLevel level = getServerLevel();
        if (level == null) return;
        ColonyItemBank bank = ColonyItemBank.get(level);
        if (bank == null) return;

        for (BuildingState state : savedData.getAllBuildings()) {
            if (!colonyId.equals(state.getColonyId())) continue;
            if (!state.isStructureIntact()) continue;

            BuildingConfig config = BuildingConfigLoader.getInstance()
                    .get(state.getBuildingTypeId());
            if (config == null || config.shop() == ShopConfig.NONE) continue;

            restock(state.getBuildingId(), config.shop(), colonyId, bank);
        }
    }

    private void updateHasStock(UUID buildingId, Map<String, Integer> s) {
        boolean hasAny = s.values().stream().anyMatch(v -> v > 0);
        Boolean prev = hasStockCache.put(buildingId, hasAny);
        if (prev == null || prev != hasAny) {
            // Notify contribution registry of stock state change
            onStockStateChanged(buildingId, hasAny);
        }
    }

    private void onStockStateChanged(UUID buildingId, boolean hasStock) {
        BuildingSavedData savedData = getSavedData();
        if (savedData == null) return;
        BuildingState state = savedData.getBuilding(buildingId);
        if (state == null) return;
        UUID colonyId = state.getColonyId();
        if (colonyId == null) return;

        var registry = savedData.getContributionRegistry();
        if (registry != null) {
            registry.setShopHasStock(buildingId, state.getBuildingTypeId(),
                    colonyId, hasStock);
        }
    }

    @Nullable
    private static ShopGoodDef findGood(ShopConfig shopConfig, String itemId) {
        for (ShopGoodDef good : shopConfig.goods()) {
            if (good.itemId().equals(itemId)) return good;
        }
        return null;
    }

    @Nullable
    private static BuildingSavedData getSavedData() {
        ServerLevel level = getServerLevel();
        return level != null ? BuildingSavedData.get(level) : null;
    }

    @Nullable
    private static ServerLevel getServerLevel() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server != null ? server.overworld() : null;
    }
}
