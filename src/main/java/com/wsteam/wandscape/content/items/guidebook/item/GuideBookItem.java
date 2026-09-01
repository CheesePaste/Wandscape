package com.wsteam.wandscape.content.items.guidebook.item;

import com.wsteam.wandscape.content.items.guidebook.network.GuideBookOpenPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * 指南书：右键直接打开模组教程首页（index_guide）。
 *
 * <p>服务端发 {@link GuideBookOpenPacket}，客户端用 {@code DocumentLoader}
 * 按语言加载文档并打开阅读器。合成：泥土 + 原木 + 圆石 + 小麦种子（无序）。
 */
public class GuideBookItem extends Item {

    /** 默认打开的教程首页文档。 */
    public static final String INDEX_DOC = "index_guide";

    public GuideBookItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            PacketDistributor.sendToPlayer(serverPlayer, new GuideBookOpenPacket(INDEX_DOC));
        }
        return InteractionResultHolder.success(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        tooltipComponents.add(Component.translatable("item.wandscape.guide_book.tooltip"));
    }
}
