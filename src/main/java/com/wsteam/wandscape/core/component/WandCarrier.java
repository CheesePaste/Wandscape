package com.wsteam.wandscape.core.component;

import com.wsteam.wandscape.core.types.BehaviourLevel;
import com.wsteam.wandscape.core.types.BehaviourTag;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Pre-computed capability union of all wands an NPC carries.
 * Recalculated when wands are equipped/unequipped.
 * Stores the unioned capabilities, not the raw wand list.
 */
public record WandCarrier(
        Map<BehaviourTag, BehaviourLevel> capabilities,
        float bestManaEfficiency,
        int maxRange
) {

    public WandCarrier {
        capabilities = Collections.unmodifiableMap(new HashMap<>(capabilities));
    }

    /** Get the highest level for a given behaviour tag, or 0 if absent. */
    public int level(BehaviourTag tag) {
        BehaviourLevel lv = capabilities.get(tag);
        return lv != null ? lv.value() : 0;
    }

    /** Check whether this carrier satisfies a set of requirements. */
    public boolean satisfies(Map<BehaviourTag, BehaviourLevel> requirements) {
        for (var entry : requirements.entrySet()) {
            if (level(entry.getKey()) < entry.getValue().value()) {
                return false;
            }
        }
        return true;
    }

    /** Empty carrier - used for NPCs with no wand. */
    public static final WandCarrier EMPTY = new WandCarrier(
            Collections.emptyMap(), 1.0f, 0
    );
}
