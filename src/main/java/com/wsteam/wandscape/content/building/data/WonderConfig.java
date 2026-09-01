package com.wsteam.wandscape.content.building.data;

import java.util.List;
/** Wonder building configuration. Contains the list of global effects. */
public record WonderConfig(List<WonderEffect> effects) {
    public static final WonderConfig NONE = new WonderConfig(List.of());

    public WonderConfig {
        if (effects == null) effects = List.of();
    }
}
