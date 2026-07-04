package com.wsteam.wandscape.shared.api;

import net.minecraft.world.item.ItemStack;
public interface WandApi {
    String getWandColor(ItemStack wand);
    float getManaCostMultiplier(ItemStack wand);
    int getRange(ItemStack wand);
}
