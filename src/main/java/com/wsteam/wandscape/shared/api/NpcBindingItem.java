package com.wsteam.wandscape.shared.api;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;

/**
 * 玩家潜行右键殖民地法师时的交互钩子（服务端）。
 *
 * <p>由 {@code WandscapeNpc.mobInteract} 在玩家潜行且手持本物品时调用；实现方负责
 * 执行自身逻辑并给玩家反馈，返回后 NPC 不再打开信息菜单。接口放在 shared 层避免
 * {@code npc/} 反向依赖具体物品模块——潜行交互的语义由实现物品（如盟誓戒指）决定。
 */
public interface NpcBindingItem {

    /**
     * 服务端回调：玩家潜行右键了一名殖民地法师，手持本物品于 {@code hand} 槽位。
     *
     * @param player 发起交互的玩家（服务端）
     * @param npc    被右键的法师实体
     * @param hand   手持本物品的那只手
     */
    void onShiftClickNpc(ServerPlayer player, Mob npc, InteractionHand hand);
}