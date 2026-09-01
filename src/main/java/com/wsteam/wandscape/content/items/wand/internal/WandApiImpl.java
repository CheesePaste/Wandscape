package com.wsteam.wandscape.content.items.wand.internal;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.core.types.AttributeModifier;
import com.wsteam.wandscape.api.WandApi;
import com.wsteam.wandscape.content.items.wand.internal.WandPresetLoader.WandPreset;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import javax.annotation.Nullable;
import java.util.List;
public class WandApiImpl implements WandApi {

    private static final String TAG_COLOR = "wand_color";
    private static final String TAG_PRESET = "preset_id";

    @Override
    public String getWandColor(ItemStack wand) {
        CustomData customData = wand.get(DataComponents.CUSTOM_DATA);
        if (customData == null) return "#FFFFFF";
        String color = customData.copyTag().getString(TAG_COLOR);
        return color.isEmpty() ? "#FFFFFF" : color;
    }

    @Override
    @Nullable
    public String getWandPresetId(ItemStack stack) {
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null) return null;
        String preset = customData.copyTag().getString(TAG_PRESET);
        return preset.isEmpty() ? null : preset;
    }

    @Override
    @Nullable
    public List<AttributeModifier> getWandModifiers(String presetId) {
        if (presetId == null) return null;
        WandPreset preset = Wandscape.WAND_PRESET_LOADER.getPreset(presetId);
        return preset == null ? null : preset.attributes();
    }
}
