package com.wsteam.wandscape.wand.internal;

import com.wsteam.wandscape.shared.api.WandApi;
import com.wsteam.wandscape.shared.registry.WandscapeConstants;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
public class WandApiImpl implements WandApi {

    private static final String TAG_COLOR = "wand_color";
    private static final String TAG_RANGE = "range";
    private static final String TAG_MANA_COST = "mana_cost_multiplier";

    @Override
    public String getWandColor(ItemStack wand) {
        CustomData customData = wand.get(DataComponents.CUSTOM_DATA);
        if (customData == null) return "#FFFFFF";
        String color = customData.copyTag().getString(TAG_COLOR);
        return color.isEmpty() ? "#FFFFFF" : color;
    }

    @Override
    public float getManaCostMultiplier(ItemStack wand) {
        CustomData customData = wand.get(DataComponents.CUSTOM_DATA);
        if (customData == null || !customData.contains(TAG_MANA_COST)) {
            return WandscapeConstants.DEFAULT_MANA_COST_MULTIPLIER;
        }
        return customData.copyTag().getFloat(TAG_MANA_COST);
    }

    @Override
    public int getRange(ItemStack wand) {
        CustomData customData = wand.get(DataComponents.CUSTOM_DATA);
        if (customData == null || !customData.contains(TAG_RANGE)) {
            return WandscapeConstants.DEFAULT_WAND_RANGE;
        }
        return customData.copyTag().getInt(TAG_RANGE);
    }
}
