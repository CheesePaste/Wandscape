package com.wsteam.wandscape.content.building.internal;

import java.util.UUID;

/**
 * Handles tourist interaction with shop buildings.
 *
 * <p>Tourist AI calls {@link #interact(ShopStockManager, UUID, UUID, UUID, int, int)}
 * to attempt a purchase with their universal-element wallet. A random in-stock
 * good the tourist can afford within their trip budget is chosen and as many
 * units as the budget allows are bought in one visit — cheap goods sell in bulk,
 * expensive ones singly, and nothing is bought if the budget can't cover a unit.
 * This is programmatic — the player-facing shop management GUI is separate.
 */
public final class ShopInteractionHandler {
    private static final String TAG = "ShopInteractionHandler";

    private ShopInteractionHandler() {}

    /**
     * Tourist attempts to buy from a shop with their universal-element wallet.
     *
     * @param stockManager  the shop stock manager instance
     * @param touristId     the tourist entity UUID (for logging)
     * @param buildingId    the shop building UUID
     * @param colonyId      the colony the shop belongs to
     * @param wallet        the tourist's current universal-element wallet balance
     * @param initialWallet the wallet the tourist arrived with (caps each trip's budget)
     * @return the purchase result (item, count, total spent), or null if nothing was bought
     */
    public static ShopStockManager.PurchaseResult interact(ShopStockManager stockManager,
                                  UUID touristId, UUID buildingId, UUID colonyId,
                                  int wallet, int initialWallet) {
        ShopStockManager.PurchaseResult result =
                stockManager.purchaseAffordable(buildingId, colonyId, wallet, initialWallet);
        if (result != null) {
        } else {
        }
        return result;
    }
}
