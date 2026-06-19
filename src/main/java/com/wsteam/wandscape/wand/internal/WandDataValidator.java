package com.wsteam.wandscape.wand.internal;

import com.wsteam.wandscape.shared.data.BehaviorType;
import net.minecraft.nbt.CompoundTag;

public final class WandDataValidator {
    private WandDataValidator() {}

    public static boolean isValid(CompoundTag tag) {
        if (!tag.contains("wand_color")) return false;
        String color = tag.getString("wand_color");
        if (!color.matches("#[0-9A-Fa-f]{6}")) return false;

        CompoundTag behaviors = tag.getCompound("behaviors");
        if (behaviors.isEmpty()) return false;
        for (String key : behaviors.getAllKeys()) {
            if (BehaviorType.fromId(key) == null) return false;
            if (behaviors.getInt(key) < 1) return false;
        }

        if (tag.contains("range")) {
            int range = tag.getInt("range");
            if (range < 1 || range > 5) return false;
        }

        if (tag.contains("mana_cost_multiplier")) {
            float mult = tag.getFloat("mana_cost_multiplier");
            if (mult < 0.3f || mult > 1.0f) return false;
        }

        return true;
    }
}
