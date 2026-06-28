package com.wsteam.wandscape.shared.data;

import java.util.List;

import com.google.gson.annotations.SerializedName;
/**
 * Shop building configuration.
 * Goods are fixed by JSON; players adjust maxStock per good via GUI.
 */
public record ShopConfig(
        List<ShopGoodDef> goods,
        @SerializedName("profit_rate") double profitRate
) {
    public static final ShopConfig NONE = new ShopConfig(List.of(), 0.0);

    public ShopConfig {
        if (goods == null) goods = List.of();
        if (profitRate < 0.0) profitRate = 0.0;
    }
}
