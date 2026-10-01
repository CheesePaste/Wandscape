package com.wsteam.wandscape.content.npc.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 守魂护符封存的随身装备与物品快照。
 * 包含主手自定义法杖、4 件盔甲、27 格背包以及 Curios 饰品。
 */
public record PreservedInventory(
        ItemStack mainHandWand,
        List<ItemStack> armor,
        List<ItemStack> backpack,
        @Nullable CompoundTag curiosTag
) {
    public PreservedInventory {
        mainHandWand = mainHandWand == null ? ItemStack.EMPTY : mainHandWand.copy();
        armor = armor == null ? List.of() : List.copyOf(armor);
        backpack = backpack == null ? List.of() : List.copyOf(backpack);
        curiosTag = curiosTag == null ? null : curiosTag.copy();
    }

    public CompoundTag toNbt(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        if (!mainHandWand.isEmpty()) {
            tag.put("mainHand", mainHandWand.saveOptional(registries));
        }
        if (!armor.isEmpty()) {
            ListTag armorList = new ListTag();
            for (ItemStack s : armor) {
                armorList.add(s.saveOptional(registries));
            }
            tag.put("armor", armorList);
        }
        if (!backpack.isEmpty()) {
            ListTag bpList = new ListTag();
            for (ItemStack s : backpack) {
                bpList.add(s.saveOptional(registries));
            }
            tag.put("backpack", bpList);
        }
        if (curiosTag != null && !curiosTag.isEmpty()) {
            tag.put("curios", curiosTag.copy());
        }
        return tag;
    }

    public static PreservedInventory fromNbt(CompoundTag tag, HolderLookup.Provider registries) {
        ItemStack mainHand = tag.contains("mainHand")
                ? ItemStack.parseOptional(registries, tag.getCompound("mainHand"))
                : ItemStack.EMPTY;

        List<ItemStack> armor = new ArrayList<>();
        if (tag.contains("armor")) {
            ListTag armorList = tag.getList("armor", Tag.TAG_COMPOUND);
            for (int i = 0; i < armorList.size(); i++) {
                armor.add(ItemStack.parseOptional(registries, armorList.getCompound(i)));
            }
        }

        List<ItemStack> backpack = new ArrayList<>();
        if (tag.contains("backpack")) {
            ListTag bpList = tag.getList("backpack", Tag.TAG_COMPOUND);
            for (int i = 0; i < bpList.size(); i++) {
                backpack.add(ItemStack.parseOptional(registries, bpList.getCompound(i)));
            }
        }

        CompoundTag curiosTag = tag.contains("curios") ? tag.getCompound("curios").copy() : null;

        return new PreservedInventory(mainHand, armor, backpack, curiosTag);
    }
}
