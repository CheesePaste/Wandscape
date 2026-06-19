package com.wsteam.wandscape.wand.internal;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import com.wsteam.wandscape.shared.data.BehaviorType;
import com.wsteam.wandscape.shared.data.WandBehaviorData;

record WandBehaviorDataImpl(
    String wandColor,
    Map<BehaviorType, Integer> behaviors,
    int range,
    float manaCostMultiplier
) implements WandBehaviorData {
    WandBehaviorDataImpl {
        behaviors = Collections.unmodifiableMap(new HashMap<>(behaviors));
    }
}
