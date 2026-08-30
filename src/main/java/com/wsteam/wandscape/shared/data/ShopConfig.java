package com.wsteam.wandscape.shared.data;

import com.google.gson.annotations.SerializedName;

import java.util.List;
/**
 * Shop building configuration.
 * Goods are fixed by JSON; players adjust maxStock per good via GUI.
 */
public record ShopConfig(
        List<ShopGoodDef> goods,
        @SerializedName("profit_rate") double profitRate,
        @SerializedName("interaction_duration_ticks") int interactionDurationTicks
) {
    public static final ShopConfig NONE = new ShopConfig(List.of(), 0.0, 0);

    public ShopConfig {
        if (goods == null) goods = List.of();
        if (profitRate < 0.0) profitRate = 0.0;
        if (interactionDurationTicks < 0) interactionDurationTicks = 0;
    }
}
