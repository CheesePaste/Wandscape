package com.wsteam.wandscape.building.internal;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.shared.data.ElementType;
import com.wsteam.wandscape.shared.data.ShopGoodDef;
import com.wsteam.wandscape.shared.data.ShopConfig;
import com.wsteam.wandscape.shared.event.ShopRestockedEvent;
import com.wsteam.wandscape.warehouse.ColonyItemBank;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Manages shop inventory: daily restock, tourist purchases, unsold clearing.
 *
 * <p>Each shop building has its own stock map. Restock deducts element costs
 * from the colony bank and fills goods to their maxStock. Purchases by tourists
 * consume stock and deposit profit elements into the bank.
 *
 * <p>Stock data is persisted through {@link BuildingSavedData} so that inventory
 * and player-configured max-stock settings survive server restarts.
 *
 * <p>Stock state changes trigger contribution toggling via
 * {@link BuildingContributionRegistry#setShopHasStock(UUID, boolean)}.
 */
public final class ShopStockManager {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** buildingId → whether the shop currently has any stock (performance cache) */
    private final Map<UUID, Boolean> hasStockCache = new ConcurrentHashMap<>();

    private int tickCounter;

    @javax.annotation.Nullable
    private static ShopStockManager active;

    private ShopStockManager() {}

    /** Returns the active instance, or null if not registered yet. */
    @javax.annotation.Nullable
    public static ShopStockManager getActive() { return active; }

    /** Register with the NeoForge event bus. Returns the instance for external access. */
    public static ShopStockManager register() {
        var instance = new ShopStockManager();
        active = instance;
        NeoForge.EVENT_BUS.register(instance);
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
        LOGGER.info("[Shop] Initial restock for building={}", buildingId.toString().substring(0, 8));
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

        s.put(itemId, current - 1);
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

        LOGGER.debug("[Shop] Purchase: building={} item={} remaining={}",
                buildingId.toString().substring(0, 8), itemId, current - 1);
        return true;
    }

    // ── Heartbeat ──

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        ServerLevel level = server.overworld();
        if (level == null) return;

        tickCounter++;
        int interval = Config.SHOP_RESTOCH_INTERVAL_TICKS.get();
        if (tickCounter % interval != 0) return;

        BuildingSavedData savedData = BuildingSavedData.get(level);
        ColonyItemBank bank = ColonyItemBank.get(level);
        BuildingConfigLoader configLoader = BuildingConfigLoader.getInstance();

        boolean clearUnsold = Config.SHOP_CLEAR_UNSOLD_ON_RESTOCH.get();

        for (BuildingState state : savedData.getAllBuildings()) {
            if (state.isShutdown() || !state.isStructureIntact()) continue;
            if (!"shop".equals(state.getCategory())) continue;

            UUID buildingId = state.getBuildingId();
            UUID colonyId = state.getColonyId();
            if (colonyId == null) continue;

            BuildingConfig config = configLoader.get(state.getBuildingTypeId());
            if (config == null || config.shop() == null) continue;
            ShopConfig shopConfig = config.shop();

            if (clearUnsold) {
                clearUnsold(buildingId);
            }

            restock(buildingId, shopConfig, colonyId, bank);
        }
    }

    // ── Internal ──

    private void restock(UUID buildingId, ShopConfig shopConfig,
                         UUID colonyId, ColonyItemBank bank) {
        BuildingSavedData savedData = getSavedData();
        if (savedData == null) return;
        Map<String, Integer> s = savedData.getOrCreateShopStock(buildingId);
        boolean changed = false;

        for (ShopGoodDef good : shopConfig.goods()) {
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

            // Deduct costs and add stock
            for (var entry : costPerItem.entrySet()) {
                bank.consumeElement(colonyId, entry.getKey(),
                        entry.getValue() * canAfford);
            }
            s.put(good.itemId(), current + canAfford);
            changed = true;
        }

        if (changed) {
            savedData.setDirty();
            updateHasStock(buildingId, s);
            NeoForge.EVENT_BUS.post(new ShopRestockedEvent(buildingId, colonyId));
            LOGGER.debug("[Shop] Restocked building={}", buildingId.toString().substring(0, 8));
        }
    }

    /** Look up an item's element value from element_mappings and convert to restock cost. */
    private static Map<ElementType, Integer> inferRestockCostFromMappings(String itemId) {
        var loader = com.wsteam.wandscape.Wandscape.ELEMENT_MAPPING_LOADER;
        if (loader == null) return Map.of();

        ResourceLocation rl = ResourceLocation.tryParse(itemId);
        if (rl == null) return Map.of();

        var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(rl);
        Map<ElementType, Long> decomposeYield = loader.getItemDecomposeYield(item);
        if (decomposeYield.isEmpty()) return Map.of();

        Map<ElementType, Integer> cost = new java.util.HashMap<>();
        for (var entry : decomposeYield.entrySet()) {
            long v = entry.getValue();
            if (v > 0) cost.put(entry.getKey(), (int) v);
        }
        return cost;
    }

    private void clearUnsold(UUID buildingId) {
        BuildingSavedData savedData = getSavedData();
        if (savedData == null) return;
        Map<String, Integer> s = savedData.getOrCreateShopStock(buildingId);
        if (!s.isEmpty()) {
            s.clear();
            savedData.setDirty();
            updateHasStock(buildingId, s);
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
