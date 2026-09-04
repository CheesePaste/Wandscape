package com.wsteam.wandscape.foundation.util;

import com.mojang.serialization.DataResult;
import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import javax.annotation.Nullable;

/**
 * 物品键：殖民地物品账本的身份标识 = 物品 ID + 数量无关的完整物品数据。
 *
 * <p>1.21 组件时代，物品状态分散在任意 DataComponent 里（附魔/耐久损伤/成书内容/
 * Patchouli 书 id/染料色等），仓库账本若只保留 CUSTOM_DATA，这些物品"存入→取出"即丢数据
 * （见 docs/bugs：书变回普通 GuideBook）。故键必须整叠保真，模型照 AE2：
 * <ul>
 *   <li>{@code nbt == null}：默认态物品（无任何非默认组件），即纯资源；
 *   <li>{@code nbt != null}：{@link DataComponentPatch} 经 {@link DataComponentPatch#CODEC} +
 *       {@link RegistryOps} 编码成的 NBT（数量无关，含全部非默认组件）。
 * </ul>
 * 两条目相等 = 同物品且完整组件相同（数量无关），照 {@link ItemStack#isSameItemSameComponents} 语义。
 *
 * <p>编码/解码都需要 {@link HolderLookup.Provider}（服务端用 level/Server registryAccess，
 * 客户端用 Minecraft.level registryAccess）。纯资源构造仍用 {@link #of(String, CompoundTag)} 传 null。
 */
public record ItemKey(String itemId, @Nullable CompoundTag nbt) {

    /** 直接构造（nbt 为已标准化的账本载荷；null = 纯资源）。会拷贝 nbt。 */
    public static ItemKey of(String itemId, @Nullable CompoundTag nbt) {
        return new ItemKey(itemId, nbt != null ? nbt.copy() : null);
    }

    /**
     * 把"旧式配方/产物描述"（物品 ID + 自定义数据 tag，即 legacy CUSTOM_DATA 内容）物化为
     * 完整保真键。null/空 tag 等价于纯资源。返回与"把该产物真正物化成栈再存入"一致的键。
     */
    public static ItemKey fromLegacy(String itemId, @Nullable CompoundTag customTag,
                                     HolderLookup.Provider registries) {
        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(itemId));
        if (item == null || item == Items.AIR) {
            return ItemKey.of(itemId, null);
        }
        ItemStack stack = new ItemStack(item, 1);
        if (customTag != null && !customTag.isEmpty()) {
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(customTag.copy()));
        }
        return fromStack(stack, registries);
    }

    /** 任意物理物品栈 → 完整保真键（默认态 → null nbt）。调用方保证 stack 非空。 */
    public static ItemKey fromStack(ItemStack stack, HolderLookup.Provider registries) {
        ItemStack proto = stack.copyWithCount(1);
        String itemId = String.valueOf(BuiltInRegistries.ITEM.getKey(proto.getItem()));
        DataComponentPatch patch = proto.getComponentsPatch();
        if (patch.isEmpty()) {
            return new ItemKey(itemId, null);
        }
        try {
            DataResult<Tag> encoded = DataComponentPatch.CODEC.encodeStart(registryOps(registries), patch);
            if (encoded.isError() || encoded.result().isEmpty()) {
                Log.warn("ItemKey", "fromStack: failed to encode components for '{}', treating as plain", itemId);
                return new ItemKey(itemId, null);
            }
            Tag tag = encoded.result().get();
            return new ItemKey(itemId, tag instanceof CompoundTag ct ? ct : null);
        } catch (RuntimeException e) {
            Log.warn("ItemKey", "fromStack: encode threw for '{}': {}", itemId, e.getMessage());
            return new ItemKey(itemId, null);
        }
    }

    /** 该键代表物品的完整数据是否等于指定栈（数量无关）。 */
    public boolean matches(ItemStack stack, HolderLookup.Provider registries) {
        return !stack.isEmpty() && equals(fromStack(stack, registries));
    }

    /** 重建该键对应的物理栈（count 任意；解析失败时返回 EMPTY 并告警，由调用方兜底）。 */
    public ItemStack toStack(int count, HolderLookup.Provider registries) {
        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(itemId));
        if (item == null || item == Items.AIR) return ItemStack.EMPTY;
        if (nbt == null) return new ItemStack(item, count);
        try {
            DataResult<DataComponentPatch> decoded = DataComponentPatch.CODEC
                    .parse(registryOps(registries), nbt);
            if (decoded.isError() || decoded.result().isEmpty()) {
                Log.warn("ItemKey", "toStack: failed to restore '{}' payload, returning EMPTY", itemId);
                return ItemStack.EMPTY;
            }
            ItemStack stack = new ItemStack(item, count);
            stack.applyComponents(decoded.result().get());
            return stack;
        } catch (RuntimeException e) {
            Log.warn("ItemKey", "toStack: decode threw for '{}': {}", itemId, e.getMessage());
            return ItemStack.EMPTY;
        }
    }

    private static RegistryOps<Tag> registryOps(HolderLookup.Provider registries) {
        return RegistryOps.create(NbtOps.INSTANCE, registries);
    }
}
