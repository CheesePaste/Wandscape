package com.wsteam.wandscape.shared.data;

import java.util.Map;

public interface WandBehaviorData {
    String wandColor();
    Map<BehaviorType, Integer> behaviors();
    int range();
    float manaCostMultiplier();
}
