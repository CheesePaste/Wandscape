package com.wsteam.wandscape.api;

import net.minecraft.world.item.ItemStack;

import java.util.function.Predicate;

/**
 * NPC 主手（法杖）装备准入 API：决定哪些物品可以放进法师装备栏的主手（法杖）槽。
 *
 * <p>法师的「法杖槽」本质是法师主手装备槽——槽内物品会真实放进 NPC 主手（原版主手装备属性
 * 自动结算），与施法逻辑解耦：我们的 {@code WandItem} 只是"加数值的槽位"，不参与释放判定。
 * 因此本接口把准入从"只认自家法杖"扩展为「注册式判定器」：其它模组/整合包在初始化阶段注册
 * 自己的施法杖判定即可让该物品可装备。
 *
 * <p>内置放行（无需注册）：
 * <ul>
 *   <li>本模组法杖 {@code WandItem}（含默认杖与疾风/堡垒等 preset 杖）；</li>
 *   <li>{@code wandscape:mage_main_hand_tools} 物品标签 —— 整合包/数据包纯 JSON 往标签里加
 *       物品即放行，无需写 Java；</li>
 *   <li>已预注册：铁魔法 {@code irons_spellbooks:staff} 标签、诡厄巫法 {@code goety:wands} 标签
 *       （对应的模组加载时由 compat 层注册）。</li>
 * </ul>
 *
 * <p>用法（在模组初始化阶段调用一次）：
 * <pre>{@code
 * WandscapeApis.getNpcMainHandApiSilently().ifPresent(api ->
 *     api.registerAllowedItem(s -> s.getItem() instanceof MyModCasterStaff));
 * }</pre>
 *
 * <p>注册的判定器在每次槽位放置判定、shift 双击路由时被查询，请保持轻量（instanceof / 标签
 * 判断优先）。
 */
public interface NpcMainHandApi {

    /** 注册一个「可放入法师主手（法杖）槽」判定器。幂等，重复注册会累加（任一命中即放行）。 */
    void registerAllowedItem(Predicate<ItemStack> isAllowed);

    /**
     * 物品是否可放入法师主手（法杖）槽（内部实现 / 槽位 mayPlace / shift 路由用）。
     * 空堆恒返回 false；外部注册判定器异常时按 false 兜底并 warn。
     */
    boolean isAllowedItem(ItemStack stack);
}