package com.wsteam.wandscape.wand.item;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public class WandItem extends Item {
    public WandItem(Properties properties) {
        super(properties);
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return false;
    }

    @Override
    public boolean isDamageable(ItemStack stack) {
        return false;
    }
}
