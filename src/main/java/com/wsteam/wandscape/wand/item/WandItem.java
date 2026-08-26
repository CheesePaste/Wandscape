package com.wsteam.wandscape.wand.item;

import java.util.List;

import com.wsteam.wandscape.core.types.AttributeModifier;
import com.wsteam.wandscape.shared.api.WandApi;
import com.wsteam.wandscape.shared.registry.WandscapeApis;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
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
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
        WandApi api = WandscapeApis.getWandApiSilently();
        if (api == null) return;
        String presetId = api.getWandPresetId(stack);
        if (presetId == null) return; // 默认杖无加成
        List<AttributeModifier> mods = api.getWandModifiers(presetId);
        if (mods == null || mods.isEmpty()) return;
        tooltipComponents.add(Component.translatable("craft_recipe.wandscape." + presetId));
        for (AttributeModifier mod : mods) {
            tooltipComponents.add(Component.translatable("attr.wandscape." + mod.type().name().toLowerCase())
                    .append(" ")
                    .append(Component.literal(formatAmount(mod.amount()))
                            .withStyle(mod.amount() >= 0 ? ChatFormatting.GREEN : ChatFormatting.RED)));
        }
    }

    /** +40 / +0.5 / -0.18 数值格式化：整数不带小数，否则最多两位并去尾零。 */
    static String formatAmount(float amount) {
        float abs = Math.abs(amount);
        String num = (abs == Math.rint(abs))
                ? String.valueOf((int) abs)
                : String.format("%.2f", abs).replaceFirst("\\.?0+$", "");
        return (amount >= 0 ? "+" : "-") + num;
    }
}
