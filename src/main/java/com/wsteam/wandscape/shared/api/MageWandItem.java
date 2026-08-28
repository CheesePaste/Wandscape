package com.wsteam.wandscape.shared.api;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;

/**
 * 玩家非潜行右键殖民地法师时的交互钩子（服务端）。
 *
 * <p>由 {@code WandscapeNpc.mobInteract} 在玩家非潜行且手持本物品时调用；实现方负责执行自身
 * 逻辑并给玩家反馈，返回后 NPC 不再打开信息菜单。接口放在 shared 层避免 {@code npc/} 反向依赖
 * 具体物品模块——玩家权杖（和平/跟随/庇护/敌对）经此直接把法师从面板操作快捷到右键一键。
 * 与 {@link NpcBindingItem}（潜行钩子）区分：本接口是非潜行右键钩子。
 */
public interface MageWandItem {

    /**
     * 服务端回调：玩家右键了一名殖民地法师，手持本物品于 {@code hand} 槽位。
     *
     * @param player 发起交互的玩家（服务端）
     * @param mage   被右键的法师实体
     * @param hand   手持本物品的那只手
     */
    void onInteractNpc(ServerPlayer player, Mob mage, InteractionHand hand);
}