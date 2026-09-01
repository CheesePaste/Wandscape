package com.wsteam.wandscape.content.items.ring;

import com.wsteam.wandscape.content.items.ring.client.OathRingClientData;
import com.wsteam.wandscape.content.items.ring.internal.OathRingService;
import com.wsteam.wandscape.api.NpcBindingItem;
import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 盟誓戒指：shift+右键本殖民地法师将其存入戒指，右键地面/空气放出。
 *
 * <p>同一玩家的所有盟誓戒指共享同一固定槽存储空间（{@code OathRingSavedData}），
 * 档位（{@link RingTier}）决定本戒指可存取的槽位前缀数量。无殖民地玩家禁止使用。
 * 本物品不持有任何法师数据——存储按玩家 UUID 全局落盘。
 */
public class OathRingItem extends Item implements NpcBindingItem {

    private static final String TAG = "OathRingItem";

    private final RingTier tier;

    public OathRingItem(Properties properties, RingTier tier) {
        super(properties);
        this.tier = tier;
    }

    /** 本戒指档位。 */
    public RingTier tier() {
        return tier;
    }

    // ── 存入：shift+右键本殖民地法师（WandscapeNpc.mobInteract 转交）──

    @Override
    public void onShiftClickNpc(ServerPlayer player, Mob npc, InteractionHand hand) {
        if (npc instanceof com.wsteam.wandscape.content.npc.entity.WandscapeNpc mage) {
            OathRingService.tryStore(player, mage, tier);
        } else {
            // 当前仅潜行右键法师会走到这里；其它生物不可绑（不反馈，防止误报）
            Log.warn(TAG, "onShiftClickNpc received non-mage entity {}", npc.getType());
        }
    }

    // ── 放出：右键方块在点击面旁放，右键空气在玩家附近放 ──

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (!level.isClientSide && context.getPlayer() instanceof ServerPlayer sp) {
            BlockPos start = context.getClickedPos().relative(context.getClickedFace());
            OathRingService.tryRelease(sp, start, tier);
            return InteractionResult.CONSUME;
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide && player instanceof ServerPlayer sp) {
            // 空气右键：以玩家面朝方向前一格为锚点搜索安全落点，避免法师刷在玩家身上
            BlockPos anchor = sp.blockPosition().relative(sp.getDirection());
            OathRingService.tryRelease(sp, anchor, tier);
            return InteractionResultHolder.consume(player.getItemInHand(hand));
        }
        return InteractionResultHolder.success(player.getItemInHand(hand));
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        // 已存 x/y：x = 本档位可存取槽内已占数，y = 档位容量（数值来自服务端同步的占用掩码）
        int stored = OathRingClientData.reachable(tier.capacity());
        tooltipComponents.add(Component.translatable("item.wandscape.oath_ring.tooltip",
                stored, tier.capacity()));
    }
}