package com.wsteam.wandscape.content.items.charm;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.compat.curios.CuriosCompat;
import com.wsteam.wandscape.content.npc.entity.WandscapeNpc;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * 守魂护符：法师饰品（Curios charm 槽位），为法师提供「死亡不掉落」保护。
 *
 * <p>当法师在饰品槽（或背包兜底）佩戴本护符时，战死瞬间跳过掉落主手法杖、盔甲、背包与全部饰品，
 * 完整封存至 {@link com.wsteam.wandscape.content.npc.data.DeathRecord}；法师在祭坛或市政厅复活后，
 * 所有随身物品与装备原样全额归还。
 */
public class SoulwardCharmItem extends Item {

    public SoulwardCharmItem(Properties properties) {
        super(properties);
    }

    /**
     * 判断目标法师是否激活了守魂护符的死亡不掉落保护。
     * Curios 加载时检查饰品槽是否佩戴；未加载时检查法师 27 格背包中是否持有。
     */
    public static boolean isSoulwardActive(WandscapeNpc npc) {
        if (npc == null || !npc.isColonyNpc()) return false;
        Item charmItem = Wandscape.SOULWARD_CHARM.get();
        if (CuriosCompat.isLoaded()) {
            if (CuriosCompat.isEquipped(npc, charmItem)) {
                return true;
            }
        }
        // 纯净未安装 Curios 时的兜底：法师背包中持有即生效
        for (int i = 0; i < npc.inventory.getContainerSize(); i++) {
            if (npc.inventory.getItem(i).is(charmItem)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        tooltipComponents.add(Component.translatable("item.wandscape.soulward_charm.tooltip"));
        tooltipComponents.add(Component.translatable("item.wandscape.soulward_charm.tooltip_curios"));
    }
}
