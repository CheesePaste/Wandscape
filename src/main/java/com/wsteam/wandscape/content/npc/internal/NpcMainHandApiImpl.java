package com.wsteam.wandscape.content.npc.internal;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.api.NpcMainHandApi;
import com.wsteam.wandscape.content.items.magic.wand.item.WandItem;
import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * {@link NpcMainHandApi} 实现：法师主手（法杖）槽准入的唯一裁决者，被
 * {@code NpcMenu}（槽位 mayPlace / shift 双击路由）查询。
 *
 * <p>内置放行：本模组法杖 {@link WandItem}（默认杖与 preset 杖）、{@code wandscape:mage_main_hand_tools}
 * 物品标签；铁魔法 {@code irons_spellbooks:staff}、诡厄巫法 {@code goety:wands} 标签由 compat 层
 * 在对应模组加载时经 {@link #registerAllowedItem} 预注册。
 *
 * <p>{@code registerAllowedItem} 通常在其它模组 FML 构造/初始化阶段调用；{@code CopyOnWriteArrayList}
 * 保证跨线程注册与运行时读取安全（读多写极少）。判定器须轻量（instanceof / 标签优先），它在每次
 * 槽位放置判定与 shift 路由时都会被查询；异常按 false 兜底，不打断装备操作。
 */
public final class NpcMainHandApiImpl implements NpcMainHandApi {

    private static final String TAG = "NpcMainHandApi";

    /** 纯数据入口：整合包/数据包把物品 id 加进此标签即可放行，免写 Java。 */
    private static final TagKey<Item> MAGE_MAIN_HAND_TOOLS =
            TagKey.create(Registries.ITEM,
                    ResourceLocation.fromNamespaceAndPath(Wandscape.MODID, "mage_main_hand_tools"));

    private final List<Predicate<ItemStack>> allowed = new CopyOnWriteArrayList<>();

    @Override
    public void registerAllowedItem(Predicate<ItemStack> isAllowed) {
        if (isAllowed == null) return;
        allowed.add(isAllowed);
        Log.info(TAG, "registered main-hand tool predicate (now {})", allowed.size());
    }

    @Override
    public boolean isAllowedItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        // 内置放行 1：本模组法杖
        if (stack.getItem() instanceof WandItem) return true;
        // 内置放行 2：wandscape 数据标签（标签行为空时恒 false）
        if (stack.is(MAGE_MAIN_HAND_TOOLS)) return true;
        if (allowed.isEmpty()) return false;
        for (Predicate<ItemStack> p : allowed) {
            try {
                if (p.test(stack)) return true;
            } catch (RuntimeException e) {
                Log.warn(TAG, "main-hand tool predicate failed for {}: {}",
                        stack.getHoverName().getString(), e.toString());
            }
        }
        return false;
    }
}