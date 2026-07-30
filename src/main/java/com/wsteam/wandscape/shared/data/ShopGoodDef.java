package com.wsteam.wandscape.shared.data;

import com.google.gson.annotations.SerializedName;
/** A single good type sold by a shop building. maxStock is managed per-shop in ShopStockManager. */
public record ShopGoodDef(
        @SerializedName("item_id") String itemId,
        int comfort,
        int magic,
        int wonder
) {
    /** Default max stock per good when none has been configured by the player. */
    public static final int DEFAULT_MAX_STOCK = 0;

    public ShopGoodDef {
        if (comfort < 0) comfort = 0;
        if (magic < 0) magic = 0;
        if (wonder < 0) wonder = 0;
    }
}
