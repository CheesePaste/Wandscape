package com.wsteam.wandscape.content.items.wand.item;

import com.wsteam.wandscape.api.WandApi;
import com.wsteam.wandscape.api.WandscapeApis;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemAttributeModifiers;

import java.util.List;

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

    /**
     * 法杖属性只对 NPC 生效，玩家手持不生效：不再用 vanilla {@link ItemAttributeModifiers}
     * 自动结算（谁拿主手谁享属性），而是返回空。NPC 主手装备法杖时，加成由
     * {@code WandscapeNpc#syncWandAttributes} 手动桥接；玩家持法杖则无任何属性
     * （顺带避免 bastion 法杖的负移速让玩家无法行走）。
     */
    @Override
    public ItemAttributeModifiers getDefaultAttributeModifiers(ItemStack stack) {
        return ItemAttributeModifiers.EMPTY;
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
