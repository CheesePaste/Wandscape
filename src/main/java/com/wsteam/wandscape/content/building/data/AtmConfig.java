package com.wsteam.wandscape.content.building.data;

import com.google.gson.annotations.SerializedName;

/**
 * ATM 建筑模式预设（category = atm）：游客交互后从旅行总旅费 travelFund 取现补钱包。
 * 单次取现上限 withdrawAmount；池子上限 = travelFund，防无限取现。
 */
public record AtmConfig(
        @SerializedName("withdraw_amount") int withdrawAmount,
        @SerializedName("interaction_duration_ticks") int interactionDurationTicks
) {
    public static final AtmConfig NONE = new AtmConfig(0, 0);

    public AtmConfig {
        if (withdrawAmount < 0) withdrawAmount = 0;
        if (interactionDurationTicks < 0) interactionDurationTicks = 0;
    }
}
