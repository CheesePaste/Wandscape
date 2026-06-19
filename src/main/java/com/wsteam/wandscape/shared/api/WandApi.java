package com.wsteam.wandscape.shared.api;

import java.util.List;
import java.util.Map;

import net.minecraft.world.item.ItemStack;

import com.wsteam.wandscape.shared.data.AbilitySet;
import com.wsteam.wandscape.shared.data.BehaviorType;
import com.wsteam.wandscape.shared.data.WandBehaviorData;

public interface WandApi {
    AbilitySet computeAbilities(List<ItemStack> wands);
    WandBehaviorData getBehaviorData(ItemStack wand);
    int getBehaviorLevel(ItemStack wand, BehaviorType type);
    String getWandColor(ItemStack wand);
    float getManaCostMultiplier(ItemStack wand);
    int getRange(ItemStack wand);
}
