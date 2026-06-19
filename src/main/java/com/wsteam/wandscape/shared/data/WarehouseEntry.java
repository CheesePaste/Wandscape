package com.wsteam.wandscape.shared.data;

import net.minecraft.nbt.CompoundTag;

public record WarehouseEntry(String itemId, CompoundTag nbt, long count) {}
