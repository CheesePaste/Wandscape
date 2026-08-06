package com.wsteam.wandscape.wand.internal;

import com.wsteam.wandscape.shared.api.WandApi;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
public class WandApiImpl implements WandApi {

    private static final String TAG_COLOR = "wand_color";

    @Override
    public String getWandColor(ItemStack wand) {
        CustomData customData = wand.get(DataComponents.CUSTOM_DATA);
        if (customData == null) return "#FFFFFF";
        String color = customData.copyTag().getString(TAG_COLOR);
        return color.isEmpty() ? "#FFFFFF" : color;
    }
}
