package com.wsteam.wandscape.shared.data;

import java.util.List;

/** Wonder building configuration. Contains the list of global effects. */
public record WonderConfig(List<WonderEffect> effects) {
    public static final WonderConfig NONE = new WonderConfig(List.of());

    public WonderConfig {
        if (effects == null) effects = List.of();
    }
}
