package com.wsteam.wandscape.content.building.data;

import com.google.gson.annotations.SerializedName;

/**
 * 放松建筑模式预设（category = relax）：游客交互后回复精力。
 * 这是精力循环里「白天恢复建筑」的载体（餐厅/澡堂/歇脚处）。
 */
public record RelaxConfig(
        @SerializedName("energy_restore") int energyRestore,
        @SerializedName("interaction_duration_ticks") int interactionDurationTicks
) {
    public static final RelaxConfig NONE = new RelaxConfig(0, 0);

    public RelaxConfig {
        if (energyRestore < 0) energyRestore = 0;
        if (interactionDurationTicks < 0) interactionDurationTicks = 0;
    }
}
