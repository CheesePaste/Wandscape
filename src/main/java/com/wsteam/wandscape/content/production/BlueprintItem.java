package com.wsteam.wandscape.content.production;

import com.wsteam.wandscape.content.colony.ownership.ColonyOwnership;
import com.wsteam.wandscape.content.production.network.RecipeBookDataPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.UUID;

/**
 * 物品图纸：记录着失落炼金图式。
 * 右键使用时直接打开配方图鉴面板；在面板中消耗 1 张图纸可自选解锁任意一个合成配方。
 */
public class BlueprintItem extends Item {

    public BlueprintItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && player instanceof ServerPlayer sp) {
            UUID colonyId = ColonyOwnership.ownColony(sp);
            if (colonyId == null) {
                sp.displayClientMessage(
                        Component.translatable("message.wandscape.blueprint.no_colony"), true);
                return InteractionResultHolder.fail(stack);
            }
            RecipeBookDataPacket.sendTo(sp, colonyId);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        tooltipComponents.add(Component.translatable("item.wandscape.item_blueprint.tooltip"));
    }
}
