package com.wsteam.wandscape.building.internal;

import java.util.Map;
import java.util.UUID;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Handles tourist interaction with shop buildings.
 *
 * <p>Tourist AI calls {@link #interact(ShopStockManager, UUID, UUID, UUID)}
 * to attempt a purchase. The tourist picks an in-stock item and buys one unit.
 * This is programmatic — the player-facing shop management GUI is separate.
 */
public final class ShopInteractionHandler {
    private static final String TAG = "ShopInteractionHandler";

    private ShopInteractionHandler() {}

    /**
     * Tourist attempts to buy from a shop.
     *
     * @param stockManager the shop stock manager instance
     * @param touristId    the tourist entity UUID (for logging)
     * @param buildingId   the shop building UUID
     * @param colonyId     the colony the shop belongs to
     * @return the itemId purchased, or null if purchase failed
     */
    public static String interact(ShopStockManager stockManager,
                                  UUID touristId, UUID buildingId, UUID colonyId) {
        Map<String, Integer> stock = stockManager.getStock(buildingId);
        if (stock.isEmpty()) {
            Log.debug(TAG, "[ShopInteract] Tourist {} tried shop {} — out of stock",
                    shortId(touristId), shortId(buildingId));
            return null;
        }

        // Pick the first available in-stock item
        String chosenItem = null;
        for (var entry : stock.entrySet()) {
            if (entry.getValue() > 0) {
                chosenItem = entry.getKey();
                break;
            }
        }
        if (chosenItem == null) return null;

        boolean success = stockManager.purchase(buildingId, chosenItem, colonyId);
        if (success) {
            Log.debug(TAG, "[ShopInteract] Tourist {} bought {} from shop {}",
                    shortId(touristId), chosenItem, shortId(buildingId));
            return chosenItem;
        }
        return null;
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }
}
