package com.wsteam.wandscape.scepter;

import java.util.List;
import java.util.Locale;

import com.wsteam.wandscape.scepter.internal.ScepterService;
import com.wsteam.wandscape.shared.api.MageWandItem;
import com.wsteam.wandscape.shared.api.NpcBindingItem;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

/**
 * 万能权杖：一杖四模式（和平/跟随/庇护/敌对），shift+右键循环模式，右键执行当前模式。
 *
 * <p>模式存于物品 {@link DataComponents#CUSTOM_DATA}（键 {@link #MODE_KEY}，值为 {@link ScepterKind} 名），
 * 默认 {@link ScepterKind#PEACE}；客户端 tint/tooltip 读它 → 颜色与提示随模式变化，靠
 * {@code player.setItemInHand} 触发玩家每 tick 的 inventoryMenu 广播同步（同皮革染色的物品 NBT 渲染语义）。
 *
 * <p>交互分流复用既有 seam，不改 {@code mobInteract}：本殖民地法师非潜行经 {@link MageWandItem}
 * 执行、潜行经 {@link NpcBindingItem} 循环；非法师生物经 {@link ScepterInteractHandler}；空气/方块经
 * {@link #use}。执行逻辑全部复用 {@link ScepterService}，无第二套业务。
 */
public class OmniScepterItem extends Item implements MageWandItem, NpcBindingItem {

    /** {@link DataComponents#CUSTOM_DATA} 中存当前模式的键。 */
    public static final String MODE_KEY = "mode";

    public OmniScepterItem(Properties properties) {
        super(properties);
    }

    /** 读当前模式；无记录/非法值回退默认 {@link ScepterKind#PEACE}。 */
    public static ScepterKind getMode(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data != null && data.contains(MODE_KEY)) {
            String name = data.copyTag().getString(MODE_KEY);
            try {
                return ScepterKind.valueOf(name);
            } catch (IllegalArgumentException ignored) {
                // 非法值回落默认
            }
        }
        return ScepterKind.PEACE;
    }

    /** 写入当前模式到物品 NBT（就地修改传入的 stack）。 */
    public static void setMode(ItemStack stack, ScepterKind mode) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag tag = data != null ? data.copyTag() : new CompoundTag();
        tag.putString(MODE_KEY, mode.name());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    /** 服务端循环到下一模式并同步手持槽（客户端颜色/tooltip 当 tick 更新），上屏提示。 */
    public static void cycleMode(ServerPlayer player, ItemStack stack, InteractionHand hand) {
        ScepterKind[] kinds = ScepterKind.values();
        ScepterKind next = kinds[(getMode(stack).ordinal() + 1) % kinds.length];
        setMode(stack, next);
        player.setItemInHand(hand, stack);
        player.displayClientMessage(modeInfo(next), false);
    }

    /** 模式显示名组件（lang {@code mode.wandscape.scepter.<name>}）。 */
    public static Component modeText(ScepterKind mode) {
        return Component.translatable("mode.wandscape.scepter." + mode.name().toLowerCase(Locale.ROOT));
    }

    /** "当前模式：%s" 组件。 */
    public static Component modeInfo(ScepterKind mode) {
        return Component.translatable("item.wandscape.omni_scepter.mode_current", modeText(mode));
    }

    /** 非潜行右键本殖民地法师：按当前模式执行（和平/跟随/庇护/敌对）。 */
    @Override
    public void onInteractNpc(ServerPlayer player, Mob mage, InteractionHand hand) {
        ScepterService.onInteractNpc(player, mage, getMode(player.getItemInHand(hand)));
    }

    /** 潜行右键本殖民地法师：循环模式（复用 NpcBindingItem 潜行 seam，mobInteract 已保证服务端）。 */
    @Override
    public void onShiftClickNpc(ServerPlayer player, Mob npc, InteractionHand hand) {
        cycleMode(player, player.getItemInHand(hand), hand);
    }

    /** 右键空气/方块：潜行则循环模式，否则放行（执行需目标：法师/生物走各自交互 seam）。 */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!player.isShiftKeyDown()) {
            return InteractionResultHolder.pass(stack);
        }
        if (!level.isClientSide && player instanceof ServerPlayer sp) {
            cycleMode(sp, stack, hand);
        }
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        tooltipComponents.add(modeInfo(getMode(stack)));
        tooltipComponents.add(Component.translatable("item.wandscape.omni_scepter.tooltip"));
    }
}
