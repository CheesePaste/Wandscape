package com.wsteam.wandscape.content.items.wand.item;
import com.wsteam.wandscape.content.task.ecs.World;

import com.wsteam.wandscape.api.WandApi;
import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.content.npc.WandscapeAttributes;
import com.wsteam.wandscape.content.npc.types.NpcAttributeModifier;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemAttributeModifiers;

import java.util.List;
import java.util.Locale;

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

        // 法杖属性只对 NPC 生效，玩家手持无加成——但属性值仍应可查阅：默认
        // getDefaultAttributeModifiers 返回空，MC 不会自动列出属性，故在此显式渲染主手属性块。
        List<NpcAttributeModifier> mods = api.getWandModifiers(presetId);
        if (mods == null || mods.isEmpty()) return;

        tooltipComponents.add(CommonComponents.EMPTY);
        tooltipComponents.add(Component.translatable("item.modifiers." + EquipmentSlotGroup.MAINHAND.getSerializedName())
                .withStyle(ChatFormatting.GRAY));
        for (NpcAttributeModifier mod : mods) {
            Holder<Attribute> vanillaAttr = WandscapeAttributes.toVanilla(mod.type());
            if (vanillaAttr == null) continue;
            net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation op =
                    switch (mod.operation()) {
                        case ADDITION -> net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE;
                        case MULTIPLY_BASE -> net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_BASE;
                    };
            ResourceLocation modId = ResourceLocation.fromNamespaceAndPath(
                    Wandscape.MODID, "wand_" + presetId + "_" + mod.type().name().toLowerCase(Locale.ROOT));
            net.minecraft.world.entity.ai.attributes.AttributeModifier mcMod =
                    new net.minecraft.world.entity.ai.attributes.AttributeModifier(modId, mod.amount(), op);
            tooltipComponents.add(vanillaAttr.value().toComponent(mcMod, tooltipFlag));
        }
    }
}
