package com.wsteam.wandscape.content.items.magic.wand.internal;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.content.npc.types.NpcAttributeModifier;
import com.wsteam.wandscape.api.WandApi;
import com.wsteam.wandscape.content.items.magic.wand.item.WandItem;
import com.wsteam.wandscape.content.items.magic.wand.internal.WandPresetLoader.WandPreset;
import com.wsteam.wandscape.foundation.util.ItemData;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.List;
public class WandApiImpl implements WandApi {

    /** 无染色时的默认色（法杖预设 JSON 未写 {@code wand_color} 时也用这个）。 */
    public static final String DEFAULT_COLOR = "#FFFFFF";

    @Override
    public String getWandColor(ItemStack wand) {
        String color = WandItem.colorHex(wand);
        return color.isEmpty() ? DEFAULT_COLOR : color;
    }

    @Override
    @Nullable
    public String getWandPresetId(ItemStack stack) {
        String preset = ItemData.getString(stack, WandItem.PRESET_KEY);
        return preset.isEmpty() ? null : preset;
    }

    @Override
    @Nullable
    public List<NpcAttributeModifier> getWandModifiers(String presetId) {
        if (presetId == null) return null;
        WandPreset preset = Wandscape.WAND_PRESET_LOADER.getPreset(presetId);
        return preset == null ? null : preset.attributes();
    }
}
