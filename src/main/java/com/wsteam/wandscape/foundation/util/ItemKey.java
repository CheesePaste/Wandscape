package com.wsteam.wandscape.foundation.util;

import net.minecraft.nbt.CompoundTag;
public record ItemKey(String itemId, CompoundTag nbt) {
    public static ItemKey of(String itemId, CompoundTag nbt) {
        return new ItemKey(itemId, nbt != null ? nbt.copy() : null);
    }
}
