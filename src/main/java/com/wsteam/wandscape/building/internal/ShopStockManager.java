package com.wsteam.wandscape.building.internal;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.road.core.RouteSegment;
import com.wsteam.wandscape.road.engine.RoadRoutingHelper;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.engine.transport.ItemTransportManager;
import com.wsteam.wandscape.shared.api.BuildingApi;
import com.wsteam.wandscape.shared.data.BuildingData;
import com.wsteam.wandscape.shared.data.ElementType;
import com.wsteam.wandscape.shared.data.ItemKey;
import com.wsteam.wandscape.shared.data.ShopGoodDef;
import com.wsteam.wandscape.shared.data.ShopConfig;
import com.wsteam.wandscape.shared.event.ShopRestockedEvent;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import com.wsteam.wandscape.warehouse.ColonyItemBank;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Manages shop inventory: dynamic restock on low stock, tourist purchases.
 *
 * <p>Each shop building has its own stock map. When a purchase drops an item's
 * stock below maxStock/3, an automatic restock is triggered that fills goods
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

    @javax.annotation.Nullable
    private static ShopStockManager active;

    private ShopStockManager() {}

    /** Returns the active instance, or null if not registered yet. */
    @javax.annotation.Nullable
    public static ShopStockManager getActive() { return active; }

    /** Initialize the singleton. */
    public static ShopStockManager register() {
        var instance = new ShopStockManager();
        active = instance;
        return instance;
    }

    // ── Inventory query ──

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
        if (state == null || state.isShutdown() || !state.isStructureIntact()) return;

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
        if (state == null || state.isShutdown() || !state.isStructureIntact()) return;
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

    /**
     * Tourist purchases one unit of an item.
     *
     * @return true if purchase succeeded (stock was available)
     */
    public boolean purchase(UUID buildingId, String itemId, UUID colonyId) {
        BuildingSavedData savedData = getSavedData();
        if (savedData == null) return false;

        Map<String, Integer> s = savedData.getOrCreateShopStock(buildingId);
        int current = s.getOrDefault(itemId, 0);
        if (current <= 0) return false;

        ServerLevel level = getServerLevel();
        if (level == null) return false;
        BuildingState state = savedData.getBuilding(buildingId);
        if (state == null) return false;

        BuildingConfig config = BuildingConfigLoader.getInstance()
                .get(state.getBuildingTypeId());
        if (config == null || config.shop() == null) return false;

        ShopGoodDef good = findGood(config.shop(), itemId);
        if (good == null) return false;

        int newStock = current - 1;
        s.put(itemId, newStock);
        savedData.setDirty();
        updateHasStock(buildingId, s);

        // Deposit profit elements into colony bank
        if (colonyId != null) {
            ColonyItemBank bank = ColonyItemBank.get(level);
            double profitRate = config.shop().profitRate();
            for (var entry : good.restockCost().entrySet()) {
                long profit = (long) Math.ceil(entry.getValue() * (1.0 + profitRate));
                bank.addElement(colonyId, entry.getKey(), profit);
            }
        }

        // Dynamic restock: if stock dropped below 1/3 of max, trigger auto-restock
        int maxStock = getMaxStock(buildingId, itemId);
        if (maxStock > 0 && newStock < maxStock / 3) {
            ColonyItemBank bank = ColonyItemBank.get(level);
            if (bank != null && restockingInProgress.add(buildingId)) {
                restock(buildingId, config.shop(), colonyId, bank);
                restockingInProgress.remove(buildingId);
            }
        }

        Log.debug(TAG, "[Shop] Purchase: building={} item={} remaining={}",
                buildingId.toString().substring(0, 8), itemId, newStock);
        return true;
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

        // Find warehouse position and plan route once for this restock cycle
        BlockPos warehousePos = null;
        List<RouteSegment> route = null;
        if (hasTransport) {
            warehousePos = findNearestWarehouse(colonyId);
            if (warehousePos == null) {
                hasTransport = false;
            } else {
                BuildingState shopState = savedData.getBuilding(buildingId);
                if (shopState != null && !shopState.isShutdown()) {
                    route = planRestockRoute(colonyId, warehousePos, shopState.getAnchor(), level);
                } else {
                    hasTransport = false;
                }
            }
        }

        boolean changed = false;

        for (ShopGoodDef good : shopConfig.goods()) {
            Map<String, Integer> s = savedData.getOrCreateShopStock(buildingId);
            int current = s.getOrDefault(good.itemId(), 0);
            int max = getMaxStock(buildingId, good.itemId());
            int needed = max - current;
            if (needed <= 0) continue;

            // Use explicit restock_cost if specified, otherwise infer from element_mappings
            Map<ElementType, Integer> costPerItem = good.restockCost();
            if (costPerItem.isEmpty()) {
                costPerItem = inferRestockCostFromMappings(good.itemId());
            }
            if (costPerItem.isEmpty()) continue;

            // Check if bank can afford the restock cost per item
            int canAfford = needed;
            for (var entry : costPerItem.entrySet()) {
                long available = bank.countElement(colonyId, entry.getKey());
                int perItem = entry.getValue();
                if (perItem > 0) {
                    canAfford = (int) Math.min(canAfford, available / perItem);
                }
            }
            if (canAfford <= 0) continue;

            if (hasTransport && warehousePos != null) {
                // Launch async transport visualization from warehouse to shop.
                // Elements are deducted only when transport arrives (deferred deduction).
                BuildingState shopState = savedData.getBuilding(buildingId);
                if (shopState != null && !shopState.isShutdown()) {
                    launchRestockTransport(buildingId, good.itemId(), canAfford,
                            costPerItem, colonyId, level,
                            warehousePos, shopState.getAnchor(), route);
                    changed = true;
                }
            } else {
                // No transport available — deduct and add stock instantly (fallback)
                for (var entry : costPerItem.entrySet()) {
                    bank.consumeElement(colonyId, entry.getKey(),
                            entry.getValue() * canAfford);
                }
                Map<String, Integer> stock = savedData.getOrCreateShopStock(buildingId);
                stock.put(good.itemId(), stock.getOrDefault(good.itemId(), 0) + canAfford);
                changed = true;
            }
        }

        if (changed) {
            savedData.setDirty();
            Map<String, Integer> finalStock = savedData.getOrCreateShopStock(buildingId);
            updateHasStock(buildingId, finalStock);
            NeoForge.EVENT_BUS.post(new ShopRestockedEvent(buildingId, colonyId));
            Log.debug(TAG, "[Shop] Restocked building={}", buildingId.toString().substring(0, 8));
        }
    }

    /**
     * Launch visual item transport from warehouse to shop for restocked goods.
     * Each unit becomes one flying ItemEntity. When the transport arrives,
     * element costs are deducted from the bank and stock is added to the shop.
     * If the building is destroyed mid-transport, no elements were deducted
     * so no refund is needed.
     */
    private void launchRestockTransport(UUID buildingId, String itemId, int amount,
                                        Map<ElementType, Integer> costPerItem,
                                        UUID colonyId, ServerLevel level,
                                        BlockPos warehousePos, BlockPos shopPos,
                                        @Nullable List<RouteSegment> route) {
        ItemTransportManager transporter = WandscapeEngine.getTransporter();
        if (transporter == null) return;

        ItemKey key = ItemKey.of(itemId, null);

        transporter.send(key, amount, warehousePos, shopPos, level, 0, route, false)
            .thenRun(() -> {
                for (int i = 0; i < amount; i++) {
                    // Deduct elements and add 1 unit to shop stock on arrival
                    addStockOnTransportArrival(buildingId, itemId, colonyId,
                            costPerItem, i == amount - 1 /* fire event only for last unit */);
                }
            });

        Log.debug(TAG, "[Shop] Transport: {} × {} from {} → {} (route={} segs)",
                amount, itemId, warehousePos.toShortString(),
                shopPos.toShortString(),
                route != null ? route.size() : 0);
    }

    /**
     * Called when a single transport unit arrives at the shop.
     * Deducts one unit's element cost from the colony bank and adds one
     * unit of stock. If the building no longer exists, skips silently
     * (no elements were deducted yet — safe).
     */
    private void addStockOnTransportArrival(UUID buildingId, String itemId,
                                            UUID colonyId,
                                            Map<ElementType, Integer> costPerItem,
                                            boolean fireEvent) {
        BuildingSavedData savedData = getSavedData();
        if (savedData == null) return;
        ServerLevel level = getServerLevel();
        if (level == null) return;

        // Check if building still exists
        BuildingState state = savedData.getBuilding(buildingId);
        if (state == null || state.isShutdown()) {
            Log.warn(TAG, "[Shop] Transport arrived but building {} gone — discarding 1 × {}",
                    buildingId.toString().substring(0, 8), itemId);
            return;
        }

        // Deduct element cost for this unit (deferred deduction)
        ColonyItemBank bank = ColonyItemBank.get(level);
        if (bank == null) return;
        for (var entry : costPerItem.entrySet()) {
            if (!bank.consumeElement(colonyId, entry.getKey(), entry.getValue())) {
                // Bank ran out mid-transport — skip this unit
                Log.warn(TAG, "[Shop] Cannot afford {} for restock of {} at {} — skipping",
                        entry.getKey(), itemId, buildingId.toString().substring(0, 8));
                return;
            }
        }

        // Add 1 unit of stock
        Map<String, Integer> stock = savedData.getOrCreateShopStock(buildingId);
        stock.put(itemId, stock.getOrDefault(itemId, 0) + 1);
        savedData.setDirty();
        updateHasStock(buildingId, stock);

        if (fireEvent) {
            NeoForge.EVENT_BUS.post(new ShopRestockedEvent(buildingId, colonyId));
        }

        Log.debug(TAG, "[Shop] Transport arrived: {} × 1 → {} (total={})",
                itemId, buildingId.toString().substring(0, 8),
                stock.getOrDefault(itemId, 0));
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
        return bd != null && !bd.isShutdown() ? bd.getPosition() : null;
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
     * Plan a transport route from warehouse to shop using the road network.
     * Returns empty list if no road network — caller falls back to direct transport.
     */
    private static List<RouteSegment> planRestockRoute(UUID colonyId,
                                                        BlockPos from, BlockPos to,
                                                        net.minecraft.world.level.Level level) {
        return RoadRoutingHelper.planWithRoads(
                WandscapeApis.getRoadApi(), level, colonyId, from, to);
    }

    /** Look up an item's element value from element_mappings and convert to restock cost. */
    private static Map<ElementType, Integer> inferRestockCostFromMappings(String itemId) {
        var loader = com.wsteam.wandscape.Wandscape.ELEMENT_MAPPING_LOADER;
        if (loader == null) return Map.of();

        ResourceLocation rl = ResourceLocation.tryParse(itemId);
        if (rl == null) return Map.of();

        var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(rl);

        // Prefer decompose_yield (what you get back when decomposing); fall back to build_cost
        Map<ElementType, Long> source = loader.getItemDecomposeYield(item);
        if (source.isEmpty()) {
            source = loader.getItemBuildCost(item);
        }
        if (source.isEmpty()) return Map.of();

        Map<ElementType, Integer> cost = new java.util.HashMap<>();
        for (var entry : source.entrySet()) {
            long v = entry.getValue();
            if (v > 0) cost.put(entry.getKey(), (int) v);
        }
        return cost;
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
