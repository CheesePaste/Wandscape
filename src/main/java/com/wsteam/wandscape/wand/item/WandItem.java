package com.wsteam.wandscape.wand.item;

import java.util.List;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.shared.api.WandApi;
import com.wsteam.wandscape.shared.registry.WandscapeApis;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemAttributeModifiers;

public class WandItem extends Item {

    public WandItem(Properties properties) {
        super(properties);
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return false;
    }

    @Override
    public boolean isDamageable(ItemStack stack) {
        return false;
    }

    @Override
    public ItemAttributeModifiers getDefaultAttributeModifiers(ItemStack stack) {
        WandApi api = WandscapeApis.getWandApiSilently();
        if (api != null) {
            String presetId = api.getWandPresetId(stack);
            if (presetId != null && Wandscape.WAND_PRESET_LOADER != null) {
                var preset = Wandscape.WAND_PRESET_LOADER.getPreset(presetId);
                if (preset != null && preset.itemAttributeModifiers() != null) {
                    return preset.itemAttributeModifiers();
                }
            }
        }
        return super.getDefaultAttributeModifiers(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
        WandApi api = WandscapeApis.getWandApiSilently();
        if (api == null) return;
        String presetId = api.getWandPresetId(stack);
        if (presetId == null) return;
        tooltipComponents.add(Component.translatable("craft_recipe.wandscape." + presetId));
    }
}
