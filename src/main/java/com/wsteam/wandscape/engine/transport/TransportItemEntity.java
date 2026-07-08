package com.wsteam.wandscape.engine.transport;

import com.wsteam.wandscape.Wandscape;
import net.minecraft.world.entity.EntityType;
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

    public TransportItemEntity(EntityType<? extends ItemEntity> type, Level level) {
        super(type, level);
    }

    public TransportItemEntity(Level level, double x, double y, double z, ItemStack stack) {
        super(Wandscape.TRANSPORT_ITEM.get(), level);
        this.setPos(x, y, z);
        this.setYRot(this.random.nextFloat() * 360.0F);
        this.setItem(stack);
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }
}
