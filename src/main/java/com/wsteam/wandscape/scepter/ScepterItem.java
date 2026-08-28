package com.wsteam.wandscape.scepter;

import java.util.List;

import com.wsteam.wandscape.scepter.internal.ScepterService;
import com.wsteam.wandscape.shared.api.MageWandItem;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * 玩家权杖物品（和平/跟随/庇护/敌对），3D 模型 + 头部主题色染色，合成站 1 级配方产出。
 *
 * <p>右键行为经 {@link MageWandItem} 接口由 {@code WandscapeNpc.mobInteract} 转交本物品
 * （法师目标），或经 {@code ScepterInteractHandler}（EntityInteract，非法师生物目标）注入
 * {@link ScepterService}。物品本身不持任何数据——标记持久化于 {@code ScepterMarksSavedData}。
 */
public class ScepterItem extends Item implements MageWandItem {

    private final ScepterKind kind;

    public ScepterItem(Properties properties, ScepterKind kind) {
        super(properties);
        this.kind = kind;
    }

    /** 本权杖种类。 */
    public ScepterKind kind() {
        return kind;
    }

    @Override
    public void onInteractNpc(ServerPlayer player, Mob mage, InteractionHand hand) {
        ScepterService.onInteractNpc(player, mage, kind);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        tooltipComponents.add(Component.translatable("item.wandscape." + kind.itemId() + ".tooltip"));
    }
}