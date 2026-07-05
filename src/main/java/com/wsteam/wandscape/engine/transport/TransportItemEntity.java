package com.wsteam.wandscape.engine.transport;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * A visual-only ItemEntity used by {@link ItemTransportManager} for flying item animations.
 *
 * <p>Overrides {@link #shouldBeSaved()} to return {@code false} so these items
 * are never written to disk — preventing frozen floating items after world reload.</p>
 */
public class TransportItemEntity extends ItemEntity {

    public TransportItemEntity(Level level, double x, double y, double z, ItemStack stack) {
        super(level, x, y, z, stack);
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }
}
