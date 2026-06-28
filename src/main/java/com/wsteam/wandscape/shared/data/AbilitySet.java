package com.wsteam.wandscape.shared.data;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
public record AbilitySet(Map<BehaviorType, Integer> abilities) {
    public AbilitySet {
        abilities = Map.copyOf(abilities);
    }

    public static final AbilitySet EMPTY = new AbilitySet(Map.of());

    public static AbilitySet merge(List<WandBehaviorData> wands) {
        Map<BehaviorType, Integer> result = new HashMap<>();
        for (WandBehaviorData wand : wands) {
            for (var entry : wand.behaviors().entrySet()) {
                result.merge(entry.getKey(), entry.getValue(), Math::max);
            }
        }
        return new AbilitySet(result);
    }

    public boolean satisfies(BehaviorType type, int requiredLevel) {
        return abilities.getOrDefault(type, 0) >= requiredLevel;
    }

    public int getLevel(BehaviorType type) {
        return abilities.getOrDefault(type, 0);
    }
}
