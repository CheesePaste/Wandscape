package com.wsteam.wandscape.foundation.util;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import javax.annotation.Nullable;

/**
 * 物品自定义数据（{@code CUSTOM_DATA}）的唯一读写口。
 *
 * <p>本模组往物品上挂的状态（法杖染色 {@code wand_color} / 预设 {@code preset_id}、
 * 卷轴绑定魔法 {@code magic_id}、权杖模式 {@code mode}）全都住在 {@code CUSTOM_DATA} 里。
 * 读写这些数据的**组件 API 只在本文一处出现**——此前散在 11 个文件里各写一遍
 * {@code stack.get(DataComponents.CUSTOM_DATA)} / {@code CustomData.of(tag)}，
 * 换个 MC 版本就要逐处改；收进这里以后同类改动只落一行。
 *
 * <p>写入口径统一为「保留其余键、只动自己那一个」：读副本 → 改 → 写回，读不到数据时
 * 从空 tag 起手。空 tag 不写回（避免给纯资源物品挂一个空的 {@code CUSTOM_DATA}——
 * 那会让它在账本里不再等于默认态物品，见 {@link ItemKey}）。
 */
public final class ItemData {

    private ItemData() {}

    /** 读一份可改的自定义数据副本；无数据时返回空 tag（恒非 null）。 */
    private static CompoundTag copy(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? new CompoundTag() : data.copyTag();
    }

    /** 读字符串键；无数据或无该键 → 空串（与 {@link CompoundTag#getString} 语义一致）。 */
    public static String getString(ItemStack stack, String key) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? "" : data.copyTag().getString(key);
    }

    /** 写字符串键（栈上其余自定义数据原样保留）。 */
    public static void setString(ItemStack stack, String key, String value) {
        CompoundTag tag = copy(stack);
        tag.putString(key, value);
        setTag(stack, tag);
    }

    /** 整块写入自定义数据（拷贝入参；null 或空 tag 不写）。 */
    public static void setTag(ItemStack stack, @Nullable CompoundTag tag) {
        if (tag != null && !tag.isEmpty()) {
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag.copy()));
        }
    }
}
