package com.wsteam.wandscape.shared.ui.util;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import javax.annotation.Nullable;

/**
 * ItemStack 构建 helpers：从 registry id 解析物品并附加旧式 NBT。
 * 1.21 组件系统下 NBT 统一存 CUSTOM_DATA，与仓库/WandscapeBlockInteractExecutor 的产物构建方式一致。
 * 供列表行图标与悬停 tooltip 复用（标准物品 tooltip 依赖栈上的 NBT，如法杖预设/魔法绑定）。
 */
public final class ItemStackUtil {

    private ItemStackUtil() {}

    /** 从 registry id 解析非空 ItemStack；id 为空/无法解析时返回 EMPTY。 */
    public static ItemStack fromId(@Nullable String id) {
        if (id == null || id.isBlank()) return ItemStack.EMPTY;
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return ItemStack.EMPTY;
        Item item = BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
        if (item == null || item == Items.AIR) return ItemStack.EMPTY;
        return new ItemStack(item);
    }

    /** 把旧式 NBT 作为 CUSTOM_DATA 附加到栈上（副本，不改入参）。返回同一栈。 */
    public static ItemStack withCustomNbt(ItemStack stack, @Nullable CompoundTag nbt) {
        if (nbt != null && !nbt.isEmpty()) {
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(nbt.copy()));
        }
        return stack;
    }

    /** 解析物品并附加 NBT 的便捷方法。 */
    public static ItemStack fromIdWithNbt(@Nullable String id, @Nullable CompoundTag nbt) {
        return withCustomNbt(fromId(id), nbt);
    }
}
